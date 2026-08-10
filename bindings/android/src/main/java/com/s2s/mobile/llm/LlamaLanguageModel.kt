package com.s2s.mobile.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
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
        loaded = true
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    override fun generate(messages: List<ChatMessage>, sink: TokenSink) {
        if (!loaded) {
            sink.onError("Model not loaded")
            return
        }
        try {
            LlamaBridge.generateStream(
                prompt = buildPrompt(messages),
                callback = object : GenStream {
                    override fun onDelta(text: String) = sink.onToken(text)
                    override fun onComplete() = sink.onComplete()
                    override fun onError(message: String) = sink.onError(message)
                },
            )
        } catch (e: Throwable) {
            Log.e(TAG, "generation failed", e)
            sink.onError(e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun cancel() {
        runCatching { LlamaBridge.nativeCancelGenerate() }
            .onFailure { Log.w(TAG, "cancel failed", it) }
    }

    override fun release() {
        loaded = false
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
