package com.s2s.mobile.pipeline

/**
 * Cleans a raw speech-recognition transcript into written text.
 *
 * Raw ASR output is not written language: it has no punctuation, spells
 * numbers out, keeps filler words, and preserves the speaker's
 * self-corrections ("friday no wait thursday"). A normalizer turns that
 * into what the person meant to write.
 *
 * Deliberately generic. This contract names no model, no runtime and no
 * vendor — a normalizer may be a local model, a remote service, or a set
 * of rules, and the speech engine must not be able to tell the
 * difference. It sits at the same level as [LanguageModel]/[Tools]/
 * [ContextEngine]: a capability the host composes and supplies.
 *
 * Called on the speech pipeline's own worker, between a final transcript
 * and whatever consumes it. Implementations must therefore be fast and
 * must never throw — see [normalize].
 */
interface TextNormalizer {

    /**
     * Returns [rawTranscript] cleaned, or the input unchanged if it cannot
     * be improved.
     *
     * MUST NOT throw and MUST NOT return null or empty for meaningful
     * input. A normalizer is an enhancement on the critical path of a
     * voice turn: if it fails, is slow, or produces nonsense, the correct
     * outcome is the user's original words reaching the agent — losing the
     * turn entirely is far worse than an unpolished transcript. The
     * fallback belongs inside the implementation, because only it knows
     * what "failed" means for its own runtime.
     */
    fun normalize(rawTranscript: String, options: TextNormalizationOptions = TextNormalizationOptions()): String

    /**
     * Frees whatever the normalizer holds (a loaded model, a binding, a
     * connection). Default no-op for a stateless implementation.
     */
    fun release() {}
}

/**
 * How the output should read.
 *
 * These are presentation choices about the *same* utterance, not
 * instructions to change its meaning. They exist as a small closed set
 * rather than free text so the speech layer can pass them through without
 * understanding any particular normalizer's protocol.
 */
data class TextNormalizationOptions(
    val styling: Styling = Styling.SEMI_CASUAL,
    val structure: Structure = Structure.PROSE,
    val context: Context = Context.GENERAL,
) {
    enum class Styling { CASUAL, SEMI_CASUAL, SEMI_FORMAL, FORMAL }
    enum class Structure { PROSE, LISTS }
    enum class Context { GENERAL, EMAIL }
}

/**
 * When normalization should run at all.
 *
 * Exists because normalization costs real time on the voice path, and
 * whether that cost is worth paying depends on the device and the
 * utterance — a decision that belongs to the host's configuration, not
 * buried in an implementation.
 */
enum class TextNormalizationPolicy {
    /** Normalize every final transcript. */
    ALWAYS,

    /**
     * Normalize only transcripts that look like they would benefit —
     * filler words, repeated words, self-corrections, spelled-out
     * numbers. Short clean commands ("call John Smith") skip the model
     * and cost nothing.
     */
    AUTO,

    /** Never normalize; the raw transcript is used as-is. */
    DISABLED,
}
