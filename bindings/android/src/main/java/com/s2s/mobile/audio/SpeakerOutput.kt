package com.s2s.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.s2s.mobile.pipeline.AudioOutput
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Gapless playback of synthesised audio through [AudioTrack].
 *
 * Runs at the TTS model's native rate so nothing is resampled on the way out.
 * [flush] drops queued *and* in-flight audio, which is what makes barge-in feel
 * immediate rather than "after the current sentence".
 */
class SpeakerOutput(
    private val context: Context,
    override val sampleRate: Int,
) : AudioOutput {

    private val queue = ConcurrentLinkedQueue<FloatArray>()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val framesWritten = AtomicLong(0)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL

    @Volatile private var running = false
    @Volatile private var active = false

    override var onDrained: (() -> Unit)? = null

    override fun start() {
        if (running) return
        routeToLoudspeaker()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION keeps playback on the same path the echo
                    // canceller references, so the mic can hear past our own voice.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        running = true
        track?.play()

        worker = thread(start = true, name = "S2S-Playback", priority = Thread.MAX_PRIORITY) {
            while (running) {
                val chunk = queue.poll()
                if (chunk != null) {
                    active = true
                    val written = track?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: 0
                    if (written > 0) framesWritten.addAndGet(written.toLong())
                } else if (active && playedOut()) {
                    active = false
                    onDrained?.invoke()
                } else {
                    Thread.sleep(5)
                }
            }
        }
    }

    override fun write(samples: FloatArray) {
        if (samples.isNotEmpty()) queue.offer(samples)
    }

    override fun hasPending(): Boolean = queue.isNotEmpty() || active

    override fun flush() {
        queue.clear()
        active = false
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            // playbackHeadPosition resets on flush, so the counter must follow it.
            framesWritten.set(0)
            t.play()
        }.onFailure { Log.e(TAG, "flush failed", it) }
    }

    override fun release() {
        running = false
        worker?.join(300)
        worker = null
        queue.clear()
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        restoreRouting()
    }

    /**
     * Forces output to the built-in loudspeaker.
     *
     * `USAGE_VOICE_COMMUNICATION` gives the platform echo canceller a reference to
     * subtract, which is what makes barge-in work over a speaker — but on its own
     * it routes to the earpiece, so the assistant comes out quiet and sounds like
     * it is on a phone call. Communication mode plus an explicit speaker device
     * keeps the echo cancellation and puts the audio back on the loudspeaker.
     */
    private fun routeToLoudspeaker() {
        runCatching {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        }.onFailure { Log.w(TAG, "loudspeaker routing failed", it) }
    }

    private fun restoreRouting() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousMode
        }.onFailure { Log.w(TAG, "restoring audio routing failed", it) }
    }

    /** True once the hardware head has caught up with everything written. */
    private fun playedOut(): Boolean {
        val t = track ?: return true
        // playbackHeadPosition is an unsigned count in a signed Int.
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return head >= framesWritten.get()
    }

    private companion object {
        const val TAG = "S2S-Playback"
    }
}
