package com.s2s.mobile.config

/**
 * Capture settings. Both Silero and the Zipformer encoder are trained at 16 kHz,
 * so [sampleRate] should not be changed without swapping models.
 */
data class AudioConfig(
    val sampleRate: Int = 16000,
    /** Samples per frame. Silero v5 expects 512 at 16 kHz. */
    val frameSize: Int = 512,
    /** Request the platform echo canceller. Required for barge-in over a speaker. */
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    /**
     * Playback rate. Null follows the TTS model's native rate, which avoids a
     * resampling pass on every chunk.
     */
    val playbackSampleRate: Int? = null,
)
