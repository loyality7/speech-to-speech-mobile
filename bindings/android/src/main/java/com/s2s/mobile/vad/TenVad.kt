package com.s2s.mobile.vad

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.pipeline.VoiceActivityDetector
import java.io.File

/**
 * TEN VAD (~0.3 MB ultra-lightweight VAD model) running on ONNX Runtime via sherpa-onnx.
 *
 * Used for barge-in only — turn ends come from the recogniser's endpointer.
 */
class TenVad(
    private val vadConfig: VadConfig,
    private val audioConfig: AudioConfig,
    private val modelPath: String,
) : VoiceActivityDetector {

    private var vad: Vad? = null

    /** TEN VAD uses 256-sample windows at 16 kHz. */
    override val frameSize: Int get() = 256

    override fun initialize(): Result<Unit> = runCatching {
        val model = File(modelPath)
        require(model.isFile) { "TEN VAD model not found: ${model.absolutePath}" }

        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(),
                tenVadModelConfig = TenVadModelConfig(
                    model.absolutePath,
                    vadConfig.threshold,
                    vadConfig.minSilenceSeconds,
                    vadConfig.minSpeechSeconds,
                    frameSize,
                    vadConfig.maxSpeechSeconds,
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
        const val TAG = "S2S-TenVad"
    }
}
