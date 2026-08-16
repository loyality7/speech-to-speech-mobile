package com.s2s.mobile.vad

import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.config.VadBackend
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.model.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaVadTest {

    @Test
    fun testFrameSizeFollowsBackend() {
        val audioConfig = AudioConfig()

        val sileroVad = SileroVad(
            vadConfig = VadConfig(backend = VadBackend.SILERO),
            audioConfig = audioConfig,
            modelPath = "dummy/path/silero_vad.onnx",
        )
        assertEquals(audioConfig.frameSize, sileroVad.frameSize)

        val tenVad = TenVad(
            vadConfig = VadConfig(backend = VadBackend.TEN),
            audioConfig = audioConfig,
            modelPath = "dummy/path/ten_vad.onnx",
        )
        assertEquals(256, tenVad.frameSize)
    }

    @Test
    fun testModelConfigFactoryVadMapping() {
        val sileroSpec = ModelSpec(
            id = "silero_vad",
            category = "VAD",
            name = "Silero VAD",
            url = "",
            targetPath = "silero_vad.onnx",
            archive = false,
            approxBytes = 2000000,
            backend = "SILERO",
        )
        val sileroConfig = ModelConfigFactory.vad(sileroSpec)
        assertEquals(VadBackend.SILERO, sileroConfig.backend)

        val tenSpec = ModelSpec(
            id = "ten_vad",
            category = "VAD",
            name = "TEN VAD",
            url = "",
            targetPath = "ten_vad.onnx",
            archive = false,
            approxBytes = 300000,
            backend = "TEN",
        )
        val tenConfig = ModelConfigFactory.vad(tenSpec)
        assertEquals(VadBackend.TEN, tenConfig.backend)
    }
}
