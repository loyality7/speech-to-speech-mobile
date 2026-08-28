package com.s2s.mobile.pipeline

/** One turn of the conversation. [role] is `system`, `user` or `assistant`. */
data class ChatMessage(val role: String, val content: String)

/**
 * Per-turn sampling overrides, applied on top of the [LanguageModel] implementation's
 * own session-wide defaults for a single [LanguageModel.generate] call — e.g. "be
 * more deterministic for this one factual query." Null fields fall back to
 * whatever the backend would otherwise use.
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

    /**
     * One-shot, non-streaming generation constrained to match [jsonSchema] —
     * grammar-constrained decoding, not prompt-based "please reply with
     * JSON" hoping. Exists specifically so a caller (e.g. an agent harness
     * deciding whether a turn needs a tool call) can get a reliably-shaped
     * answer instead of parsing free text and hoping it matches.
     *
     * Default implementation fails — most [LanguageModel] backends (remote
     * HTTP APIs without a schema parameter, or a local backend whose runtime
     * has no schema-constrained decoding) have no way to honor this, and a
     * caller must be able to detect that and fall back to normal [generate]
     * rather than silently getting unconstrained text back under a
     * structured-sounding name.
     */
    fun generateStructured(messages: List<ChatMessage>, jsonSchema: String, overrides: GenerationOverrides? = null): Result<String> =
        Result.failure(UnsupportedOperationException("${this::class.simpleName} does not support generateStructured"))

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

