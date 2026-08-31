package com.s2s.mobile.internal

import com.s2s.mobile.config.TurnConfig

/**
 * Collects the acoustic segments of one conversational turn and decides when
 * the user has actually finished speaking.
 *
 * ## The problem this exists to solve
 *
 * A VAD closes a segment after a fixed amount of trailing silence
 * ([com.s2s.mobile.config.VadConfig.minSilenceSeconds], 350 ms by default).
 * That is an **acoustic** fact: "there was sound, then there was not."
 *
 * The end of a conversational **turn** is a different question: "has this
 * person finished the thought they intended to express?" Natural speech
 * contains pauses for thinking, breathing and word-finding that are far longer
 * than 350 ms, so the two questions have different answers constantly.
 *
 * Before this class existed, the engine treated every segment as a finished
 * turn. Saying *"Set a reminder … for tomorrow morning … to call the dentist"*
 * with two thinking pauses produced three permanent user messages and three
 * LLM requests, two of which were cancelled mid-flight and thrown away.
 *
 * ## What this actually is
 *
 * **Segment aggregation plus an adaptive endpoint timeout.** Named honestly:
 * the timeout is the final decision mechanism. The transcript signals in
 * [endOfTurnDelayMs] only make that timeout longer or shorter — they do not
 * independently decide anything, and this is not semantic turn detection. A
 * model-based end-of-turn predictor would replace [endOfTurnDelayMs] alone;
 * everything else here would stay as it is.
 *
 * ## Not a live-transcription mechanism
 *
 * With an offline recogniser (`OfflineVadRecognizer`, the default MOONSHINE
 * path) text arrives only when a segment closes, so [Snapshot.transcript]
 * updates once per pause — not per word. Speaking continuously for ten
 * seconds shows nothing until the first pause. Fixing that requires a
 * streaming recogniser that emits `Transcript.Partial`; this class works with
 * either, but it cannot invent partials the recogniser never produced.
 *
 * ## Threading
 *
 * Every method is called from the single audio thread that owns
 * `S2SEngine.onFrame`, except [commitIfDue] which that same thread calls once
 * per frame. No internal synchronisation, deliberately: adding locks would
 * imply a concurrency this class does not have.
 */
internal class TurnAggregator(
    private val config: TurnConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One turn's accumulated state. A new instance per turn; never mutated after commit. */
    private class ActiveTurn(val startedAtMs: Long) {
        val segments = mutableListOf<String>()

        /** When the most recent segment landed — the moment the user's pause began. */
        var lastSegmentAtMs = 0L

        /** How many times [TurnAggregator.endOfTurnDelayMs] has extended this turn's window. Capped; see [TurnConfig.maxIncompleteExtensions]. */
        var extensions = 0

        /** Set the instant this turn commits, so a second commit is impossible. */
        var committed = false

        val transcript: String get() = segments.joinToString(" ")
    }

    private var active: ActiveTurn? = null

    /** What the caller needs to render or dispatch. [isFinal] true exactly once per turn. */
    data class Snapshot(val transcript: String, val isFinal: Boolean)

    /** Whether a turn is currently accumulating — the engine's USER_TURN_ACTIVE/TURN_PENDING_CONFIRMATION condition. */
    val hasActiveTurn: Boolean get() = active != null

    /**
     * Accepts one decoded segment.
     *
     * Opens a turn if none is active, appends otherwise, and re-arms the
     * endpoint window either way. Returns the text to display, always
     * non-final: a segment arriving is never itself proof the turn is over,
     * which is the entire point of this class.
     *
     * Returns null for blank input rather than opening an empty turn — the
     * recogniser can return an empty string for a segment that decoded to
     * nothing.
     */
    fun offer(segmentText: String): Snapshot? {
        val text = segmentText.trim()
        if (text.isEmpty()) return null

        val turn = active ?: ActiveTurn(startedAtMs = now()).also { active = it }
        turn.segments += text
        turn.lastSegmentAtMs = now()
        // A new segment means the user was still talking, so any pending
        // commit is cancelled implicitly: commitIfDue() measures from
        // lastSegmentAtMs, which just moved.
        return Snapshot(turn.transcript, isFinal = false)
    }

    /**
     * Commits the active turn if its endpoint window has elapsed, or if it has
     * run past [TurnConfig.maxTurnDurationMs].
     *
     * Returns the final snapshot exactly once per turn; null on every other
     * call. The caller dispatches its single request on a non-null return and
     * nothing else — that makes the commit idempotent by construction rather
     * than by the caller remembering to guard it.
     */
    fun commitIfDue(): Snapshot? {
        val turn = active ?: return null
        if (turn.committed) return null

        val elapsedSincePause = now() - turn.lastSegmentAtMs
        val turnDuration = now() - turn.startedAtMs

        // The hard ceiling wins over every extension. Without it, a user who
        // habitually trails off with "and…" is never answered at all, which is
        // a worse failure than committing slightly early.
        val overrunTurn = turnDuration >= config.maxTurnDurationMs
        if (!overrunTurn && elapsedSincePause < endOfTurnDelayMs(turn)) return null

        turn.committed = true
        val snapshot = Snapshot(turn.transcript, isFinal = true)
        active = null
        return snapshot
    }

    /**
     * Abandons the active turn without committing — for barge-in, focus loss,
     * or engine stop. Returns whatever had accumulated so a caller that wants
     * to keep it (a confirmed interruption should not discard the words the
     * user already said) can, while a caller that does not can ignore it.
     */
    fun abandon(): String? {
        val text = active?.transcript?.takeIf { it.isNotBlank() }
        active = null
        return text
    }

    /**
     * How long to wait after the user stops before treating the turn as
     * finished, for this specific turn.
     *
     * This is the adaptive part, and every input is a local string check — no
     * model call, nothing on the network, nothing that costs latency inside a
     * voice turn:
     *
     * - **Trailing conjunction or filler.** A transcript ending in "and",
     *   "um", "to" is grammatically unfinished; a person who stopped there is
     *   thinking, not done. Waits [TurnConfig.incompleteGraceMs] longer, up to
     *   [TurnConfig.maxIncompleteExtensions] times so a habitual trailer still
     *   gets an answer.
     * - **Very short transcripts.** Two words are far more likely to be the
     *   start of a sentence than a whole turn, so they wait longer.
     * - **Terminal punctuation.** A recogniser that emits "?" or "." has
     *   already judged the sentence complete; trust it and shorten the wait.
     *
     * A recogniser that emits no punctuation simply never hits the last case.
     * Nothing here fails closed.
     */
    private fun endOfTurnDelayMs(turn: ActiveTurn): Long {
        val text = turn.transcript
        val lower = text.lowercase()

        if (config.trustTerminalPunctuation && TERMINAL_PUNCTUATION.any { text.endsWith(it) }) {
            return config.completeUtteranceDelayMs
        }

        val lastWord = lower.split(WORD_SPLIT).lastOrNull { it.isNotBlank() }.orEmpty()
        if (lastWord in INCOMPLETE_TRAILING_WORDS && turn.extensions < config.maxIncompleteExtensions) {
            turn.extensions++
            return config.baseDelayMs + config.incompleteGraceMs
        }

        val wordCount = lower.split(WORD_SPLIT).count { it.isNotBlank() }
        if (wordCount <= config.shortUtteranceWords) return config.baseDelayMs + config.shortUtteranceGraceMs

        return config.baseDelayMs
    }

    private companion object {
        val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")

        val TERMINAL_PUNCTUATION = listOf('?', '.', '!')

        /**
         * Words that leave a sentence hanging. Conjunctions, prepositions,
         * articles and the common English hesitation fillers.
         *
         * A list, not a grammar model: it is inspectable, costs nothing, and
         * its failure mode is the mild one — a missed word means the turn
         * commits at the base delay, exactly as it would have without this
         * check at all.
         */
        val INCOMPLETE_TRAILING_WORDS = setOf(
            // Hesitation fillers.
            "um", "uh", "erm", "hmm", "er", "ah", "like",
            // Conjunctions.
            "and", "but", "or", "so", "because", "cause", "although", "while", "if", "unless", "than", "then",
            // Prepositions and particles that cannot end a clause.
            "to", "for", "with", "about", "from", "into", "onto", "at", "by", "of", "in", "on", "as",
            // Articles and determiners.
            "a", "an", "the", "my", "your", "our", "their", "this", "that", "these", "those",
            // Dangling interrogatives.
            "what", "which", "who", "whose", "where", "when", "how",
        )
    }
}
