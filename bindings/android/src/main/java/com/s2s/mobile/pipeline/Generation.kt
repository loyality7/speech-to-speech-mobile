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

    fun generate(messages: List<ChatMessage>, sink: TokenSink)

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

/**
 * Splits a token stream into speakable sentences.
 *
 * Sentence one is synthesised while the model is still writing sentence two,
 * which is what keeps time-to-first-audio short on long replies.
 */
interface TextChunker {
    /** Feeds a token. Returns any sentences that just became complete. */
    fun accept(token: String): List<String>

    /** Returns whatever is left when generation ends. */
    fun flush(): String?

    fun reset()
}
