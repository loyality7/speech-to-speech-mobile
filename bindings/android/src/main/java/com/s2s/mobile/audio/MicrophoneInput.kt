package com.s2s.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.pipeline.AudioInput
import kotlin.concurrent.thread

/**
 * Microphone capture via [AudioRecord].
 *
 * Delivers fixed-size frames: a short read is buffered rather than forwarded,
 * because a partially-filled buffer would feed the VAD window and the encoder
 * stale tail samples. One buffer is reused for the life of the stream, so the
 * capture path allocates nothing per frame.
 *
 * Requires the `RECORD_AUDIO` permission.
 */
class MicrophoneInput(private val config: AudioConfig) : AudioInput {

    override val sampleRate: Int get() = config.sampleRate
    override val frameSize: Int get() = config.frameSize

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    override fun start(onFrame: (FloatArray) -> Unit): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuffer")
            return false
        }

        val rec = try {
            AudioRecord(
                // VOICE_COMMUNICATION routes through the platform echo canceller on
                // most devices, which is what makes barge-in over a speaker viable.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, frameSize * 8),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord uninitialised — is RECORD_AUDIO granted?")
            rec.release()
            return false
        }

        if (config.echoCancellation && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }
        if (config.noiseSuppression && NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }

        record = rec
        running = true
        rec.startRecording()

        worker = thread(start = true, name = "S2S-Mic", priority = Thread.MAX_PRIORITY) {
            val shorts = ShortArray(frameSize)
            val floats = FloatArray(frameSize)
            var filled = 0

            while (running) {
                val read = rec.read(shorts, filled, frameSize - filled)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "read failed: $read")
                        break
                    }
                    continue
                }
                filled += read
                if (filled < frameSize) continue

                for (i in 0 until frameSize) floats[i] = shorts[i] / 32768.0f
                filled = 0
                try {
                    onFrame(floats)
                } catch (e: Throwable) {
                    // A throw here would kill capture for the whole session.
                    Log.e(TAG, "frame handler threw", e)
                }
            }
        }
        return true
    }

    override fun stop() {
        running = false
        // Unbounded on purpose: releasing AudioRecord while the capture thread is
        // still inside read() is a use-after-free. The loop checks `running` every
        // frame and read() returns within one frame period, so this is bounded in
        // practice by the frame size — provided no frame handler blocks, which is
        // why decoding must not happen on this thread.
        worker?.join()
        worker = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            it.release()
        }
        record = null
    }

    private companion object {
        const val TAG = "S2S-Mic"
    }
}
