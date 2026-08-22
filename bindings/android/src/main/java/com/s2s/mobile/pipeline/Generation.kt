package com.s2s.mobile.pipeline

/** One turn of the conversation. [role] is `system`, `user` or `assistant`. */
data class ChatMessage(val role: String, val content: String)

/**
 * On-device text generation runtimes the bundled [LanguageModel] implementations
 * support. Each expects a different model file format, so switching backend
 * means switching model file, not just a flag.
 */
enum class LlmBackend {
    /** llama.cpp via Llamatik. GGUF Q4/Q5/Q8 checkpoints. */
    LLAMA_CPP,

    /** Google's LiteRT-LM. Single `.litertlm` checkpoints. */
    LITERT,
}

/**
 * Per-turn sampling overrides, applied on top of [com.s2s.mobile.config.LlmConfig]'s
 * session-wide defaults for a single [LanguageModel.generate] call — e.g. "be more
 * deterministic for this one factual query." Null fields fall back to the config.
 */
data class GenerationOverrides(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val repeatPenalty: Float? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
)

/** Callbacks for a streaming generation. Invoked on the caller's thread. */
interface TokenSink {
    fun onToken(text: String)
    fun onComplete()
    fun onError(message: String, cause: Throwable? = null)
}

/**
 * Streaming text generation.
 *
 * [generate] blocks until the reply is finished, cancelled or fails; drive it
 * from a worker thread. [cancel] must return promptly from another thread —
 * barge-in latency depends on it.
 */
interface LanguageModel {
    fun initialize(): Result<Unit>

    fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides? = null)

    fun cancel()

    /**
     * Tells the model that the conversation was rewritten and any cached state
     * from previous turns no longer matches the prompt it will be given.
     *
     * Needed because an implementation may keep a KV cache across turns; without
     * this it would answer from text the user has since replaced.
     */
    fun resetContext()

    /**
     * Optional memory trimming hook. Trims non-essential KV cache buffers when the OS
     * signals memory pressure (e.g. ComponentCallbacks2.onTrimMemory).
     */
    fun trimMemory() {}

    fun release()
}

