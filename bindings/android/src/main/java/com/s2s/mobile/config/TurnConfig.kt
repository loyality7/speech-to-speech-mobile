package com.s2s.mobile.config

/**
 * When the user's conversational turn is considered finished.
 *
 * Distinct from [VadConfig], and the distinction is the point:
 *
 * - [VadConfig.minSilenceSeconds] decides where an **acoustic segment** is
 *   cut. That is a signal-processing question — was there sound, then silence?
 * - This class decides when a **conversational turn** is over. That is a
 *   different question: has the user finished the thought they intended to
 *   express? Natural speech pauses for thinking, breathing and word-finding
 *   far longer than any sane segment gap, so the answers differ constantly.
 *
 * Conflating the two is what produced one user message and one LLM request per
 * *pause* rather than per *thought*. See
 * [com.s2s.mobile.internal.TurnAggregator].
 *
 * Honest naming: this is **adaptive endpoint timeout**, not semantic end-of-turn
 * detection. [baseDelayMs] is the decision; the rest of these values lengthen or
 * shorten it based on cheap local checks of the transcript. Replacing it with a
 * model-based predictor would mean replacing the delay calculation only.
 */
data class TurnConfig(
    /**
     * Baseline wait after a segment LANDS before the turn commits, in ms.
     *
     * Sizing this needs care, because a segment does not arrive when the user
     * stops speaking — it arrives considerably later. Measured on a real
     * device, per segment:
     *
     * ```
     * user stops → VadConfig.minSilenceSeconds (350ms) → decode (237–620ms)
     *            → segment offered to the aggregator
     * ```
     *
     * So ~600–1000 ms of wall clock is already gone before this delay even
     * starts counting. The first version of this value was 500 ms, which had
     * already expired by the time the next frame ran `commitIfDue()` — every
     * segment committed instantly and the aggregator did nothing at all. The
     * unit tests missed it because a fake clock advances only when told to and
     * therefore models no decode latency.
     *
     * Real inter-segment gaps for one sentence spoken with natural thinking
     * pauses, same device: **1135 ms and 1838 ms**. This must comfortably
     * exceed the first of those to hold such a sentence together, while
     * staying short enough that a genuinely finished turn is not left hanging.
     * 1400 ms sits above the observed short gap and below the long one, so a
     * brief hesitation merges and a real stop still commits promptly.
     *
     * The user-perceived wait is this value MINUS the decode time that already
     * elapsed, so it feels shorter than it reads.
     */
    val baseDelayMs: Long = 1_400,

    /**
     * Extra wait when the transcript ends on a word that cannot end a clause —
     * "and", "to", "um", "the". Someone who stopped there is thinking, not
     * finished, so the strongest available signal says keep waiting.
     *
     * Sized against the same real-device measurement as [baseDelayMs]: the
     * long observed thinking gap was 1838 ms, so base + this must clear it.
     */
    val incompleteGraceMs: Long = 900,

    /**
     * How many times [incompleteGraceMs] may extend one turn.
     *
     * A hard cap, because some people genuinely do end sentences on "and…"
     * and without a limit they would never receive an answer. On hitting the
     * cap the turn falls back to [baseDelayMs] and commits.
     */
    val maxIncompleteExtensions: Int = 3,

    /**
     * Extra wait for a transcript of at most [shortUtteranceWords] words.
     *
     * Two words are much more likely to be the opening of a sentence than a
     * whole turn. Short genuine utterances ("yes", "stop") do exist and pay
     * this delay — an acceptable trade against fragmenting every sentence
     * whose first pause lands after two words.
     */
    val shortUtteranceGraceMs: Long = 600,

    /** Word count at or below which [shortUtteranceGraceMs] applies. */
    val shortUtteranceWords: Int = 3,

    /**
     * Wait used when the transcript ends in terminal punctuation, in ms.
     *
     * A recogniser that emits "?" or "." has already judged the sentence
     * complete, which is better evidence than any timing heuristic — so trust
     * it and respond faster. Recognisers that emit no punctuation never reach
     * this path, so it is safe to leave enabled.
     */
    val completeUtteranceDelayMs: Long = 700,

    /** Whether to trust terminal punctuation as an end-of-turn signal at all. Disable for a recogniser whose punctuation is unreliable. */
    val trustTerminalPunctuation: Boolean = true,

    /**
     * Hard ceiling on one turn's accumulation, in ms.
     *
     * Commits regardless of every other signal. Guarantees the system always
     * eventually answers: without it, a monologue (or a stuck
     * [incompleteGraceMs] loop) accumulates forever and the user is never
     * replied to, which is a worse failure than committing mid-sentence.
     * Should stay comfortably under [VadConfig.maxSpeechSeconds] × a few
     * segments' worth of speech.
     */
    val maxTurnDurationMs: Long = 30_000,

    /**
     * What to do when the user starts a new turn while the assistant is still
     * THINKING about the previous one. See [ThinkingInterruptionPolicy].
     */
    val thinkingInterruptionPolicy: ThinkingInterruptionPolicy =
        ThinkingInterruptionPolicy.CANCEL_AND_REPLACE,
)

/**
 * How a new user turn behaves when one is already being answered.
 *
 * The realistic case: *"What's the capital of France?"* … assistant starts
 * thinking … *"Actually never mind, what's the capital of Germany?"* The user
 * plainly wants the second question answered, not the first.
 */
enum class ThinkingInterruptionPolicy {
    /**
     * Cancel the in-flight response and answer the new turn instead.
     *
     * The default, because it is what the user visibly wants, and because it
     * only fires on a **committed** turn — the aggregator has already waited
     * out its endpoint window, so this cannot be triggered by one stray audio
     * frame or a cough. That distinction is what makes cancellation safe here:
     * the evidence is a finished sentence, not an energy spike.
     */
    CANCEL_AND_REPLACE,

    /**
     * Let the in-flight response finish, then answer the new turn.
     *
     * Rarely what anyone wants in speech — the user hears an answer to a
     * question they already retracted — but correct for a host that must not
     * abandon work in progress (a tool call with side effects, say).
     */
    QUEUE_AFTER_CURRENT,
}
