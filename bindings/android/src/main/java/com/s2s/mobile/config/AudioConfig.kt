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
    /**
     * Run a microphone-typed foreground service while the engine is listening.
     *
     * Android stops delivering audio to a backgrounded process, so without one the
     * assistant goes deaf when the user switches apps. Set false only if the host
     * app already runs its own microphone foreground service.
     */
    val manageForegroundService: Boolean = true,
    /** Notification title while listening. Shown by the foreground service. */
    val serviceNotificationTitle: String = "Listening",
    val serviceNotificationText: String = "Voice assistant is active",
    /** Notification title while paused for audio focus loss. */
    val serviceNotificationPausedTitle: String = "Paused",
    val serviceNotificationPausedText: String = "Audio focus taken by another app",
    /**
     * Hold audio focus while listening, and yield to calls and alarms.
     *
     * Disabling it means the assistant speaks over an incoming call, which is
     * almost never what anyone wants — the switch exists for hosts that manage
     * focus themselves at a higher level.
     */
    val manageAudioFocus: Boolean = true,
)
