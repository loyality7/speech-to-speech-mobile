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
 * A fresh [Conversation] is created for every [generate] call, seeded with the
 * full history via [ConversationConfig.initialMessages] — matching the only
 * verified-working pattern for this API in this workspace (the sibling
 * `speech-android/control-demo` project's `LiteRtLmRuntime`). An earlier version
 * of this class tried to keep one [Conversation] alive across turns and feed it
 * only the newest message, on the theory that its native history tracks
 * [S2SEngine]'s own; that broke on the second turn on a real device with
 * "Conversation roles must alternate" even though the first turn's reply had
 * already streamed to completion — so whatever the reply-commit timing on the
 * native side actually is, it cannot be relied on here. This costs the KV/session
 * reuse LlamaLanguageModel gets from keeping one session warm; it does not have
 * a documented equivalent that survives close()/createConversation() in the
 * Kotlin API.
 */
class LiteRtLanguageModel(
    private val config: LlmConfig,
    private val modelPath: String,
) : LanguageModel {

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null

    /** True once initialize() has loaded a model; generate() refuses to run without it. */
    @Volatile
    private var loaded = false

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
            runCatching { activeConversation?.close() }
            val active = eng.createConversation(buildConfig(messages.dropLast(1)))
            activeConversation = active

            val latch = CountDownLatch(1)
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Each callback is a delta chunk, not the accumulated reply so far.
                    val text = message.contents.toString()
                    if (text.isEmpty()) return
                    sink.onToken(text)
                }

                override fun onDone() {
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
        // Each turn gets a fresh Conversation anyway (see class doc), so there
        // is no session-poisoning concern here — just stop the current one.
        runCatching { activeConversation?.cancelProcess() }
    }

    /** No-op: every turn already rebuilds its Conversation from S2SEngine's own history. */
    override fun resetContext() = Unit

    override fun trimMemory() {
        if (generating) return // matches LlamaLanguageModel: never touch state mid-stream
        runCatching { activeConversation?.close() }
        activeConversation = null
    }

    override fun release() {
        loaded = false
        runCatching { activeConversation?.close() }
        activeConversation = null
        runCatching { engine?.close() }
        engine = null
    }

    private companion object {
        const val TAG = "S2S-LiteRt"
    }
}
