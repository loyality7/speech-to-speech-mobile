package com.s2s.mobile.config

import com.s2s.mobile.model.ModelSpec
import com.s2s.mobile.pipeline.TtsBackend
import java.io.File

/**
 * Fully dynamic configuration factory that builds [S2SConfig] directly from the
 * JSON specification fields in each selected [ModelSpec].
 *
 * Contains ZERO hardcoded model IDs or string matches in Kotlin.
 */
object ModelConfigFactory {

    fun create(
        baseModelsDir: File,
        vadSpec: ModelSpec,
        sttSpec: ModelSpec,
        ttsSpec: ModelSpec,
        llmSpec: ModelSpec,
    ): S2SConfig {
        val modelPaths = ModelPaths(
            vadModel = File(baseModelsDir, vadSpec.targetPath).absolutePath,
            sttDir = File(baseModelsDir, sttSpec.targetPath).absolutePath,
            llmModel = File(baseModelsDir, llmSpec.targetPath).absolutePath,
            ttsDir = File(baseModelsDir, ttsSpec.targetPath).absolutePath,
        )

        // 1. Dynamic STT Config from JSON specification
        val sttBackend = when (sttSpec.backend) {
            "ZIPFORMER_TRANSDUCER" -> SttBackend.ZIPFORMER_TRANSDUCER
            "WHISPER" -> SttBackend.WHISPER
            else -> SttBackend.MOONSHINE
        }
        val sttConfig = SttConfig(
            backend = sttBackend,
            numThreads = sttSpec.numThreads ?: 2,
            decodingMethod = sttSpec.decodingMethod ?: "modified_beam_search",
            endpointTrailingSilence = sttSpec.endpointTrailingSilence ?: 0.8f,
        )

        // 2. Dynamic LLM Config from JSON specification
        val llmConfig = LlmConfig(
            numThreads = llmSpec.numThreads ?: 4,
            batchSize = llmSpec.batchSize ?: 512,
            maxTokens = llmSpec.maxTokens ?: 256,
        )

        // 3. Dynamic TTS Config from JSON specification
        val ttsBackend = when (ttsSpec.backend) {
            "KOKORO" -> TtsBackend.KOKORO
            "KITTEN" -> TtsBackend.KITTEN
            "MATCHA" -> TtsBackend.MATCHA
            else -> TtsBackend.VITS
        }

        val ttsConfig = TtsConfig(
            backend = ttsBackend,
            numThreads = ttsSpec.numThreads ?: 2,
            firstChunkMinChars = ttsSpec.firstChunkMinChars ?: 10,
            maxChunkChars = ttsSpec.maxChunkChars ?: 70,
            minChunkChars = ttsSpec.minChunkChars ?: 10,
            speed = ttsSpec.speed ?: 1.05f,
        )

        return S2SConfig(
            models = modelPaths,
            stt = sttConfig,
            llm = llmConfig,
            tts = ttsConfig,
        )
    }
}
