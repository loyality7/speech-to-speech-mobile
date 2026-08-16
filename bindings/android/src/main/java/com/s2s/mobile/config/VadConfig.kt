package com.s2s.mobile.config

/**
 * [windowSize] is the frame length the detector is trained on, in samples at
 * 16 kHz. It lives on the backend because the microphone, the VAD and the
 * recogniser all have to agree on it — when they did not, TEN VAD silently
 * received 512-sample frames and misbehaved rather than failing.
 */
enum class VadBackend(val windowSize: Int) {
    SILERO(512),
    TEN(256),
}

/**
 * Voice activity detection.
 *
 * With a streaming recogniser these only govern barge-in, and turn ends come
 * from the recogniser's own endpointer. With an offline recogniser they also
 * decide where an utterance is cut, which makes [minSilenceSeconds] the single
 * most important setting for transcript quality.
 */
data class VadConfig(
    val backend: VadBackend = VadBackend.SILERO,
    /** Speech probability above which a frame counts as voice. */
    val threshold: Float = 0.5f,

    /**
     * Trailing silence that closes a segment, in seconds.
     *
     * This is dead time on every single turn: the user has stopped, and the
     * engine is still waiting to be sure. It was raised to 0.7 to stop a pause
     * mid-sentence from splitting an utterance into fragments, but continuing to
     * speak now merges into the turn already in flight, so the split is no longer
     * destructive and the wait can come back down.
     */
    val minSilenceSeconds: Float = 0.35f,

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
