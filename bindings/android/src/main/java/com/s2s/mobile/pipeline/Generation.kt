package com.s2s.mobile.pipeline

/** One turn of the conversation. [role] is `system`, `user` or `assistant`. */
data class ChatMessage(val role: String, val content: String)

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
