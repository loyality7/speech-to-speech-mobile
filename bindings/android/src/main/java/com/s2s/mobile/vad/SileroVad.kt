package com.s2s.mobile.vad

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.pipeline.VoiceActivityDetector
import java.io.File

/**
 * Silero VAD v5 running on ONNX Runtime, via sherpa-onnx.
 *
 * Used for barge-in only — turn ends come from the recogniser's endpointer,
 * which is trained for the job and already has the decoded text in hand.
 *
 * This replaces the RMS-and-sigmoid stub the C++ engine shipped, which pinned at
 * "speech" whenever room noise crossed a fixed 0.008 threshold and so never let
 * an utterance end.
 */
class SileroVad(
    private val vadConfig: VadConfig,
    private val audioConfig: AudioConfig,
    private val modelPath: String,
) : VoiceActivityDetector {

    private var vad: Vad? = null

    /** Silero v5 is trained on 512-sample windows at 16 kHz. */
    override val frameSize: Int get() = audioConfig.frameSize

    override fun initialize(): Result<Unit> = runCatching {
        val model = File(modelPath)
        require(model.isFile) { "Silero VAD model not found: ${model.absolutePath}" }

        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = vadConfig.threshold,
                    minSilenceDuration = vadConfig.minSilenceSeconds,
                    minSpeechDuration = vadConfig.minSpeechSeconds,
                    windowSize = frameSize,
                    maxSpeechDuration = vadConfig.maxSpeechSeconds,
                ),
                sampleRate = audioConfig.sampleRate,
                numThreads = vadConfig.numThreads,
                provider = "cpu",
            ),
        )
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    override fun accept(frame: FloatArray): Boolean {
        val v = vad ?: return false
        v.acceptWaveform(frame)
        // Segments are not consumed on this path; drain them so the internal
        // queue cannot grow for the life of the session.
        while (!v.empty()) v.pop()
        return v.isSpeechDetected()
    }

    override fun reset() {
        vad?.reset()
    }

    override fun release() {
        vad?.release()
        vad = null
    }

    private companion object {
        const val TAG = "S2S-Vad"
    }
}
