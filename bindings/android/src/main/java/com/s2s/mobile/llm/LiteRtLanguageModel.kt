package com.s2s.mobile.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.s2s.mobile.config.LlmConfig
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch

/**
 * Streaming generation on Google's LiteRT-LM, for `.litertlm` checkpoints.
 *
 * Unlike [LlamaLanguageModel], LiteRT-LM's [Conversation] keeps its own message
 * history natively rather than accepting the full prompt on every call — so
 * this reuses [CacheAnchor] the other way round: instead of finding where a KV
 * cache ends, it finds whether the current [Conversation] already holds every
 * message except the newest one, and only replays history when it does not
 * (a barge-in, a continued turn, or the very first call).
 */
class LiteRtLanguageModel(
    private val config: LlmConfig,
    private val modelPath: String,
) : LanguageModel {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** True once initialize() has loaded a model; generate() refuses to run without it. */
    @Volatile
    private var loaded = false

    /**
     * Set on [cancel] and [resetContext].
     *
     * LiteRT-LM documents the native session as unsafe to reuse after
     * `cancelProcess()` — the conversation is considered poisoned — so this
     * forces the next [generate] to close it and open a fresh one rather than
     * trying to keep talking to a session that may no longer be consistent.
     */
    @Volatile
    private var contextDirty = false

    /** Content of the last message handed to the active [Conversation]. See [CacheAnchor]. */
    @Volatile
    private var cacheTail: String? = null

    @Volatile
    private var generating = false

    override fun initialize(): Result<Unit> = runCatching {
        val model = File(modelPath)
        require(model.isFile) { "LiteRT-LM model not found: ${model.absolutePath}" }

        val eng = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(threadCount = config.numThreads),
                maxNumTokens = config.contextLength,
            ),
        )
        eng.initialize()
        engine = eng
        loaded = true
    }.onFailure {
        Log.e(TAG, "initialize failed", it)
        runCatching { engine?.close() }
        engine = null
        loaded = false
    }

    override fun generate(messages: List<ChatMessage>, sink: TokenSink) {
        val eng = engine
        if (!loaded || eng == null) {
            sink.onError("Model not loaded")
            return
        }
        val last = messages.lastOrNull()
        if (last == null) {
            sink.onComplete()
            return
        }

        try {
            // Reusable only when the active conversation already holds every
            // message up to but not including this one — anything else (a
            // rewritten history, a dropped turn, a cancelled generation) means
            // the native history and our list have diverged, so the whole
            // conversation must be rebuilt rather than trusted.
            val anchor = CacheAnchor.indexIn(messages, cacheTail)
            val reusable = conversation != null && !contextDirty && anchor == messages.size - 2

            val active = if (reusable) {
                conversation!!
            } else {
                runCatching { conversation?.close() }
                eng.createConversation(buildConfig(messages.dropLast(1))).also { conversation = it }
            }
            contextDirty = false
            cacheTail = last.content.trim()

            val latch = CountDownLatch(1)
            val generated = StringBuilder()
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Each callback is a delta chunk, not the accumulated reply so far.
                    val text = message.contents.toString()
                    if (text.isEmpty()) return
                    generated.append(text)
                    sink.onToken(text)
                }

                override fun onDone() {
                    // The cache now ends at the assistant's reply, not the user
                    // message that triggered it — same reasoning as CacheAnchor's
                    // use in LlamaLanguageModel.
                    generated.toString().trim().ifBlank { null }?.let { cacheTail = it }
                    sink.onComplete()
                    latch.countDown()
                }

                override fun onError(throwable: Throwable) {
                    // A cancellation is barge-in doing its job, not a failure —
                    // TurnGuard has already moved on by the time this arrives,
                    // so reporting it as an error would just log noise.
                    if (throwable !is CancellationException) {
                        sink.onError(throwable.message ?: throwable.javaClass.simpleName, throwable)
                    }
                    latch.countDown()
                }
            }

            generating = true
            active.sendMessageAsync(Message.user(last.content), callback)
            latch.await()
        } catch (e: Throwable) {
            Log.e(TAG, "generation failed", e)
            sink.onError(e.message ?: e.javaClass.simpleName, e)
        } finally {
            generating = false
        }
    }

    /**
     * [history] excludes the newest message — that one is sent fresh via
     * [Conversation.sendMessageAsync] once the conversation exists, matching
     * how LiteRT-LM expects to receive it.
     */
    private fun buildConfig(history: List<ChatMessage>): ConversationConfig {
        val systemText = history.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }

        val turns = history.filterNot { it.role == "system" }
            .map { if (it.role == "assistant") Message.model(it.content) else Message.user(it.content) }

        return ConversationConfig(
            systemInstruction = systemText?.let { Contents.of(it) },
            initialMessages = turns,
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP.toDouble(),
                temperature = config.temperature.toDouble(),
            ),
            maxOutputToken = config.maxTokens,
        )
    }

    override fun cancel() {
        // Documented as unsafe to keep talking to afterward — see contextDirty.
        runCatching { conversation?.cancelProcess() }
        contextDirty = true
    }

    override fun resetContext() {
        contextDirty = true
    }

    override fun trimMemory() {
        if (generating) return // matches LlamaLanguageModel: never touch state mid-stream
        runCatching { conversation?.close() }
        conversation = null
        contextDirty = true
    }

    override fun release() {
        loaded = false
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        contextDirty = false
    }

    private companion object {
        const val TAG = "S2S-LiteRt"
    }
}
