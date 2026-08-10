package com.s2s.mobile.config

/**
 * Voice activity detection, used for barge-in.
 *
 * Turn ends are decided by the recogniser's endpointer, not by these thresholds
 * — see [SttConfig].
 */
data class VadConfig(
    /** Speech probability above which a frame counts as voice. */
    val threshold: Float = 0.5f,
    val minSilenceSeconds: Float = 0.25f,
    val minSpeechSeconds: Float = 0.25f,
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
