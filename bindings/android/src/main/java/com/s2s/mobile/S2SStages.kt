package com.s2s.mobile

import android.content.Context
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.config.SttBackend
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.ModelSpec
import com.s2s.mobile.model.S2SModels
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.SpeechSynthesizer
import com.s2s.mobile.stt.OfflineVadRecognizer
import com.s2s.mobile.stt.SherpaStreamingRecognizer
import com.s2s.mobile.tts.SherpaSynthesizer
import java.io.File

/**
 * Builds a single pipeline stage from a downloaded [ModelSpec].
 *
 * Useful on its own — evaluating a voice, or running speech-to-text without
 * paying for an LLM — and it keeps three rules in one place that callers
 * otherwise had to know: which recogniser implementation a backend needs, where
 * a model unpacked to, and which file the VAD lives in.
 *
 * Stages returned here are NOT initialised; call `initialize()` off the main
 * thread, and `release()` when finished.
 */
object S2SStages {

    /**
     * Offline backends decode a whole utterance at once, so they need a VAD to
     * decide where an utterance ends. Streaming backends emit as they go and
     * bring their own endpointing.
     */
    fun recognizer(
        context: Context,
        spec: ModelSpec,
        audio: AudioConfig = AudioConfig(),
        vad: VadConfig = VadConfig(),
    ): SpeechRecognizer {
        val config = ModelConfigFactory.stt(spec)
        val modelDir = File(S2SModels.dir(context), spec.targetPath).absolutePath

        return when (config.backend) {
            SttBackend.MOONSHINE, SttBackend.WHISPER -> OfflineVadRecognizer(
                sttConfig = config,
                vadConfig = vad,
                audioConfig = audio,
                modelDir = modelDir,
                vadModelPath = vadModelPath(context),
            )
            else -> SherpaStreamingRecognizer(
                sttConfig = config,
                audioConfig = audio,
                modelDir = modelDir,
            )
        }
    }

    fun synthesizer(context: Context, spec: ModelSpec, speakerId: Int = 0): SpeechSynthesizer =
        SherpaSynthesizer(
            config = ModelConfigFactory.tts(spec, speakerId),
            modelDir = File(S2SModels.dir(context), spec.targetPath).absolutePath,
        )

    /** Taken from the registry rather than a literal filename, so it follows the default stack. */
    private fun vadModelPath(context: Context): String =
        File(S2SModels.dir(context), ModelRegistry.DEFAULT_VAD.targetPath).absolutePath
}
