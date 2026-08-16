package com.s2s.mobile.vad

import com.s2s.mobile.audio.MicrophoneInput
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.config.VadBackend
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.model.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The detector, the microphone and the recogniser must agree on the window size.
 *
 * They did not: TEN VAD hardcoded 256 while capture kept producing Silero's 512,
 * so TEN received the wrong shape and misbehaved instead of failing. These tests
 * assert the pipeline agrees, not that a constant equals itself — the previous
 * version passed precisely because it compared the hardcoded value to itself.
 */
class SherpaVadTest {

    private fun spec(id: String, backend: String) = ModelSpec(
        id = id,
        category = "VAD",
        name = id,
        url = "",
        targetPath = "$id.onnx",
        archive = false,
        approxBytes = 1_000_000,
        backend = backend,
    )

    @Test
    fun `capture frame size matches the selected detector window`() {
        for ((backend, expected) in listOf("SILERO" to 512, "TEN" to 256)) {
            val config = ModelConfigFactory.create(
                baseModelsDir = File("/tmp/models"),
                vadSpec = spec("vad", backend),
                sttSpec = spec("stt", "MOONSHINE"),
                ttsSpec = spec("tts", "VITS"),
                llmSpec = spec("llm", ""),
            )

            // What the microphone will actually produce.
            assertEquals(expected, MicrophoneInput(config.audio).frameSize)

            // What the detector will expect from it.
            val vad = if (config.vad.backend == VadBackend.TEN) {
                TenVad(config.vad, config.audio, "unused.onnx")
            } else {
                SileroVad(config.vad, config.audio, "unused.onnx")
            }
            assertEquals(expected, vad.frameSize)
            assertEquals(MicrophoneInput(config.audio).frameSize, vad.frameSize)
        }
    }

    @Test
    fun `backend is read from the registry spec`() {
        assertEquals(VadBackend.SILERO, ModelConfigFactory.vad(spec("silero_vad", "SILERO")).backend)
        assertEquals(VadBackend.TEN, ModelConfigFactory.vad(spec("ten_vad", "TEN")).backend)
    }

    @Test
    fun `an unknown backend falls back to Silero rather than failing`() {
        // A registry entry with no backend field predates the selector; it must
        // keep working rather than crash a shipped app on a config it has seen
        // a hundred times before.
        assertEquals(VadBackend.SILERO, ModelConfigFactory.vad(spec("old_entry", "")).backend)
    }

    @Test
    fun `window size is owned by the backend`() {
        assertEquals(512, VadBackend.SILERO.windowSize)
        assertEquals(256, VadBackend.TEN.windowSize)
        // VadConfig must not be able to disagree with its own backend.
        assertEquals(VadBackend.TEN.windowSize, VadConfig(backend = VadBackend.TEN).backend.windowSize)
    }
}
