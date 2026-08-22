package com.s2s.mobile.config

import com.s2s.mobile.model.ModelSpec
import com.s2s.mobile.pipeline.TtsBackend
import java.io.File

/**
 * Turns registry [ModelSpec]s into stage configuration.
 *
 * Every default lives here and nowhere else. When these mappings were also
 * written out by hand at each call site they drifted — a TTS test screen built
 * its synthesiser with a chunk floor of 6 while the engine used 10, so a voice
 * could sound fine under test and stutter in the real pipeline.
 *
 * Contains no hardcoded model IDs: everything is read from the spec, and each
 * `?:` is the fallback for a spec that does not pin that field.
 */
object ModelConfigFactory {

    fun vad(spec: ModelSpec) = VadConfig(
        backend = when (spec.backend) {
            "TEN" -> VadBackend.TEN
            else -> VadBackend.SILERO
        },
        numThreads = spec.numThreads ?: 1,
    )

    fun stt(spec: ModelSpec) = SttConfig(
        backend = when (spec.backend) {
            "ZIPFORMER_TRANSDUCER" -> SttBackend.ZIPFORMER_TRANSDUCER
            "WHISPER" -> SttBackend.WHISPER
            "PARAKEET_TDT" -> SttBackend.PARAKEET_TDT
            "CANARY" -> SttBackend.CANARY
            else -> SttBackend.MOONSHINE
        },
        numThreads = spec.numThreads ?: 2,
        decodingMethod = spec.decodingMethod ?: "modified_beam_search",
        endpointTrailingSilence = spec.endpointTrailingSilence ?: 0.8f,
    )

    fun tts(spec: ModelSpec, speakerId: Int = 0) = TtsConfig(
        backend = when (spec.backend) {
            "KOKORO" -> TtsBackend.KOKORO
            "KITTEN" -> TtsBackend.KITTEN
            "MATCHA" -> TtsBackend.MATCHA
            "POCKET" -> TtsBackend.POCKET
            else -> TtsBackend.VITS
        },
        speakerId = speakerId,
        numThreads = spec.numThreads ?: 2,
        firstChunkMinChars = spec.firstChunkMinChars ?: 10,
        maxChunkChars = spec.maxChunkChars ?: 70,
        minChunkChars = spec.minChunkChars ?: 10,
        speed = spec.speed ?: 1.05f,
    )

    fun llm(spec: ModelSpec) = LlmConfig(
        numThreads = spec.numThreads ?: 4,
        batchSize = spec.batchSize ?: 512,
        maxTokens = spec.maxTokens ?: 256,
    )

    fun create(
        baseModelsDir: File,
        vadSpec: ModelSpec,
        sttSpec: ModelSpec,
        ttsSpec: ModelSpec,
        llmSpec: ModelSpec,
    ): S2SConfig {
        val vadConfig = vad(vadSpec)
        // Capture has to produce the window the detector expects, so the frame
        // size follows the VAD backend rather than being assumed. Silero wants
        // 512 samples, TEN wants 256.
        val audioConfig = AudioConfig(frameSize = vadConfig.backend.windowSize)
        return S2SConfig(
            models = ModelPaths(
                vadModel = File(baseModelsDir, vadSpec.targetPath).absolutePath,
                sttDir = File(baseModelsDir, sttSpec.targetPath).absolutePath,
                llmModel = File(baseModelsDir, llmSpec.targetPath).absolutePath,
                ttsDir = File(baseModelsDir, ttsSpec.targetPath).absolutePath,
            ),
            audio = audioConfig,
            vad = vadConfig,
            stt = stt(sttSpec),
            llm = llm(llmSpec),
            tts = tts(ttsSpec),
        )
    }
}
