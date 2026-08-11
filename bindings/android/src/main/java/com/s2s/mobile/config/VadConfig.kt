package com.s2s.mobile.config

/**
 * Voice activity detection.
 *
 * With a streaming recogniser these only govern barge-in, and turn ends come
 * from the recogniser's own endpointer. With an offline recogniser they also
 * decide where an utterance is cut, which makes [minSilenceSeconds] the single
 * most important setting for transcript quality.
 */
data class VadConfig(
    /** Speech probability above which a frame counts as voice. */
    val threshold: Float = 0.5f,

    /**
     * Trailing silence that closes a segment, in seconds.
     *
     * At 0.25 an ordinary mid-sentence pause ended the utterance, so a single
     * sentence arrived as three or four fragments — "Yes." / "Oh, there we" /
     * "Can you?" — each firing its own turn and then being barged into by the
     * rest of the sentence. Long enough to ride over natural pauses, short
     * enough that the end of a turn still feels immediate.
     */
    val minSilenceSeconds: Float = 0.7f,

    /** Utterances shorter than this are discarded as noise, in seconds. */
    val minSpeechSeconds: Float = 0.3f,
    val maxSpeechSeconds: Float = 20f,
    /**
     * Consecutive voiced frames required to accept a barge-in while the assistant
     * speaks. Echo cancellation always leaks a little, and a single leaked frame
     * firing a barge-in makes the assistant cut itself off and shred the next
     * transcript. 8 frames = 256 ms at 16 kHz: still well under what a user
     * notices, but far longer than a leak survives.
     */
    val bargeInFrames: Int = 8,

    /**
     * Time after playback starts during which barge-in is ignored, in ms.
     *
     * The echo canceller needs a moment to converge on a new output signal; until
     * it has, the assistant's first syllable reliably looks like user speech.
     */
    val bargeInGraceMs: Long = 400,
    /** Allow the user to interrupt the assistant mid-sentence. */
    val bargeInEnabled: Boolean = true,
    val numThreads: Int = 1,
)
