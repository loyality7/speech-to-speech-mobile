package com.s2s.mobile.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.LlamaSession
import com.s2s.mobile.config.LlmConfig
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import java.io.File

/**
 * Streaming generation on llama.cpp, through the Llamatik runtime.
 *
 * Every entry point is wrapped: these are native calls, and a missing symbol or
 * a bad model surfaces as an `Error` rather than an `Exception`. Catching only
 * `Exception` here is what let a bad load take the whole process down.
 */
class LlamaLanguageModel(
    private val config: LlmConfig,
    private val modelPath: String,
) : LanguageModel {

    @Volatile
    private var loaded = false

    /**
     * Session that keeps llama.cpp's KV cache warm between turns.
     *
     * Without it every turn re-prefills the whole conversation. Measured on a
     * mid-range device that costs ~5.9 ms per prompt character — linear in
     * conversation length, so a chat that starts at 2.6 s reaches 8.9 s by the
     * time the prompt is 1400 characters. With the session only the new user
     * text is prefilled, so cost stops growing with the conversation.
     */
    private var session: LlamaSession? = null

    /**
     * Set when the conversation was rewritten behind the cache — the user
     * continued a half-finished question, or a turn was cancelled mid-generation.
     * The cache then holds text that is no longer in the prompt, so it has to be
     * rebuilt once before reuse is safe again.
     */
    @Volatile
    private var contextDirty = false

    /**
     * Content of the last message the session ingested, used to find where the
     * cache ends in a possibly-trimmed history.
     *
     * Matching on content rather than a counter survives ChatHistory trimming
     * old turns, and stays correct when a cancelled reply is committed as a
     * partial assistant message.
     */
    @Volatile
    private var cacheTail: String? = null

    override fun initialize(): Result<Unit> = runCatching {
        val gguf = File(modelPath)
        require(gguf.isFile) { "LLM model not found: ${gguf.absolutePath}" }

        LlamaBridge.updateGenerateParams(
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            topK = config.topK,
            repeatPenalty = config.repeatPenalty,
            contextLength = config.contextLength,
            numThreads = config.numThreads,
            useMmap = config.useMmap,
            flashAttention = config.flashAttention,
            batchSize = config.batchSize,
            gpuLayers = config.gpuLayers,
        )
        require(LlamaBridge.initGenerateModel(gguf.absolutePath)) {
            "llama.cpp refused the model: ${gguf.absolutePath}"
        }

        if (config.reuseKvCache) {
            session = runCatching { LlamaBridge.createSession("s2s") }
                .onFailure { Log.w(TAG, "session unavailable, falling back to full prefill", it) }
                .getOrNull()
            contextDirty = false
        }
        loaded = true
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    override fun generate(messages: List<ChatMessage>, sink: TokenSink) {
        if (!loaded) {
            sink.onError("Model not loaded")
            return
        }
        try {
            val entered = System.currentTimeMillis()
            val active = session

            // The cache ends at whatever was last fed to the session, so the delta
            // is everything after it. Anchoring on content rather than a count
            // survives history trimming and a cancelled reply being committed as a
            // partial assistant message.
            val anchor = cacheTail?.let { tail -> messages.indexOfLast { it.content == tail } } ?: -1
            val reusable = active != null && !contextDirty && anchor >= 0
            val outgoing = if (reusable) messages.drop(anchor + 1) else messages

            if (!reusable) {
                Log.i(
                    TAG,
                    "full prefill — session=${active != null}, dirty=$contextDirty, " +
                        "anchor=$anchor, messages=${messages.size}",
                )
            }

            if (active != null && !reusable) {
                runCatching { LlamaBridge.sessionReset() }
                contextDirty = false
            }

            val prompt = buildPrompt(outgoing)
            val built = System.currentTimeMillis()
            var first = true

            // Everything submitted is now in the cache, and so is whatever the
            // model generates from it.
            cacheTail = outgoing.lastOrNull()?.content ?: cacheTail
            val generated = StringBuilder()

            val callback = object : GenStream {
                override fun onDelta(text: String) {
                    generated.append(text)
                    if (first) {
                        first = false
                        Log.i(
                            TAG,
                            "prompt ${prompt.length} chars (${if (reusable) "session" else "full"}), " +
                                "built in ${built - entered}ms, " +
                                "first token ${System.currentTimeMillis() - built}ms after submit",
                        )
                    }
                    sink.onToken(text)
                }

                override fun onComplete() {
                    cacheTail = generated.toString().ifBlank { cacheTail }
                    sink.onComplete()
                }

                override fun onError(message: String) = sink.onError(message)
            }

            if (active != null) {
                active.stream(prompt, callback)
            } else {
                LlamaBridge.generateStream(prompt = prompt, callback = callback)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "generation failed", e)
            sink.onError(e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun cancel() {
        runCatching { session?.cancel() }
        runCatching { LlamaBridge.nativeCancelGenerate() }
            .onFailure { Log.w(TAG, "cancel failed", it) }
    }

    override fun resetContext() {
        contextDirty = true
    }

    override fun release() {
        loaded = false
        runCatching { session?.close() }
        session = null
        contextDirty = false
        runCatching { LlamaBridge.shutdown() }
            .onFailure { Log.w(TAG, "shutdown failed", it) }
    }

    /**
     * Prefers the chat template baked into the GGUF, since a mismatched template
     * is a common cause of rambling or empty replies. Falls back to a generic
     * ChatML-ish layout only when the model carries none.
     */
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val pairs = messages.map { it.role to it.content }
        val templated = runCatching {
            LlamaBridge.applyChatTemplate(pairs, addAssistantPrefix = true)
        }.getOrNull()
        if (!templated.isNullOrBlank()) return templated

        return buildString {
            messages.forEach { (role, content) ->
                append("<|").append(role).append("|>\n").append(content).append("\n<|end|>\n")
            }
            append("<|assistant|>\n")
        }
    }

    private companion object {
        const val TAG = "S2S-Llm"
    }
}
