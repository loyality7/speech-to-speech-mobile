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
    /**
     * Pause playback when another app ducks audio focus, rather than lowering
     * volume and continuing. Pausing is the safer default for a voice
     * assistant — talking over a ducked notification sound is rarely wanted.
     */
    val pauseOnDuck: Boolean = true,
    /**
     * `MediaRecorder.AudioSource` constant name (e.g. "VOICE_COMMUNICATION",
     * "UNPROCESSED", "MIC"). VOICE_COMMUNICATION routes through the platform's
     * own AEC/NS pipeline; apps doing their own signal processing may want
     * "UNPROCESSED" or "MIC" instead.
     */
    val audioSource: String = "VOICE_COMMUNICATION",
    /** Foreground service notification channel id. Must be unique within the host app. */
    val notificationChannelId: String = "s2s_voice_session_channel",
    val notificationId: Int = 1002,
    /** Foreground service notification channel importance name (e.g. "LOW", "DEFAULT", "HIGH"). */
    val notificationImportance: String = "LOW",
    /** Drawable resource id for the notification's small icon. Defaults to a generic system icon. */
    val notificationSmallIconRes: Int = android.R.drawable.ic_btn_speak_now,
    /** Priority given to the audio capture and playback threads. See [Thread] priority constants. */
    val captureThreadPriority: Int = Thread.MAX_PRIORITY,
    val playbackThreadPriority: Int = Thread.MAX_PRIORITY,
    /** Capture buffer size as a multiple of one VAD frame, floored at the platform minimum. */
    val captureBufferFrameMultiplier: Int = 8,
    /** Playback buffer size as a multiple of `AudioTrack`'s minimum buffer size. */
    val playbackBufferMultiplier: Int = 2,
    /** Playback idle-poll interval while draining, in milliseconds. */
    val playbackPollIntervalMs: Long = 5,
    /** How long [release] waits for the audio worker threads to stop, in milliseconds. */
    val releaseJoinTimeoutMs: Long = 300,
)
