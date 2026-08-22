package com.s2s.mobile.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import java.io.File

/**
 * Runs synthesized TTS audio through sherpa-onnx's DPDFNet 48kHz speech
 * enhancement model as a post-process quality pass
 *
 * DPDFNet is built for cleaning noisy microphone input, not for turning small
 * TTS models into studio-quality voices; there is no dedicated neural
 * upsampler in sherpa-onnx. This uses it the way it is actually built:
 * running any input through it and taking whatever 48kHz output it commits
 * to. [restore]'s caller decides whether the result actually sounds better —
 * this class does not have an opinion.
 */
class AudioRestorer(private val modelPath: String) {

    private var denoiser: OfflineSpeechDenoiser? = null

    val sampleRate: Int get() = denoiser?.sampleRate ?: 0

    fun initialize(): Result<Unit> = runCatching {
        val model = File(modelPath)
        require(model.isFile) { "HD audio restorer model not found: ${model.absolutePath}" }

        denoiser = OfflineSpeechDenoiser(
            config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = model.absolutePath),
                ),
            ),
        )
        Log.i(TAG, "ready: output ${sampleRate}Hz")
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    /** Runs one chunk through the model. Returns the input unchanged on failure. */
    fun restore(samples: FloatArray, inputSampleRate: Int): FloatArray {
        val d = denoiser ?: return samples
        return try {
            d.run(samples, inputSampleRate).samples
        } catch (e: Throwable) {
            Log.e(TAG, "restore failed, passing audio through unmodified", e)
            samples
        }
    }

    fun release() {
        denoiser?.release()
        denoiser = null
    }

    private companion object {
        const val TAG = "S2S-AudioRestorer"
    }
}
