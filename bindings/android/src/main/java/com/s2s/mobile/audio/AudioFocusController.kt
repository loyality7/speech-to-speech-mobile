package com.s2s.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Holds audio focus for the length of a conversation and reports when it is lost.
 *
 * Without this the engine talks over incoming calls, alarms and other media, and
 * never yields — which is the single most obvious way an assistant feels broken.
 *
 * Focus is held for the whole session rather than requested per turn. The
 * microphone is open continuously anyway, so per-turn churn would mean requesting
 * and abandoning focus several times a minute — audible as ducking flicker in
 * other apps, and racy against barge-in, which can cut a turn mid-word.
 */
class AudioFocusController(
    context: Context,
    private val onLoss: () -> Unit,
    private val onTransientLoss: () -> Unit,
    private val onRegained: () -> Unit,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    /** True while focus was lost transiently, so regaining it should resume. */
    private var pausedByFocusLoss = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent: another app took over for good. Stop, do not wait.
                Log.i(TAG, "focus lost permanently")
                pausedByFocusLoss = false
                onLoss()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // A call or alarm. Ducking is not an option for a speech assistant:
                // quieter speech still talks over the caller, and our own mic would
                // transcribe whatever is ducking us. Treat both as pause.
                Log.i(TAG, "focus lost transiently ($change)")
                pausedByFocusLoss = true
                onTransientLoss()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "focus regained")
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    onRegained()
                }
            }
        }
    }

    /** Returns false when the system refuses focus — the caller should not start. */
    fun request(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Without this the listener is never told about a transient loss, and
            // the engine would keep talking through a phone call.
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()

        request = req
        val result = runCatching { audioManager.requestAudioFocus(req) }
            .onFailure { Log.w(TAG, "focus request failed", it) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        return (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
            if (!it) Log.w(TAG, "audio focus denied ($result)")
        }
    }

    fun abandon() {
        pausedByFocusLoss = false
        request?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
                .onFailure { Log.w(TAG, "abandoning focus failed", it) }
        }
        request = null
    }

    private companion object {
        const val TAG = "S2S-Focus"
    }
}
