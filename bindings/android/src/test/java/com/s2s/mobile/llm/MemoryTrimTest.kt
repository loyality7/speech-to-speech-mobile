package com.s2s.mobile.llm

import com.s2s.mobile.pipeline.LanguageModel
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimTest {

    @Test
    fun testLanguageModelInterfaceTrimMemory() {
        var trimmed = false
        val model = object : LanguageModel {
            override fun initialize() = Result.success(Unit)
            override fun generate(
                messages: List<com.s2s.mobile.pipeline.ChatMessage>,
                sink: com.s2s.mobile.pipeline.TokenSink,
                overrides: com.s2s.mobile.pipeline.GenerationOverrides?,
            ) {}
            override fun cancel() {}
            override fun resetContext() {}
            override fun trimMemory() {
                trimmed = true
            }
            override fun release() {}
        }

        model.trimMemory()
        assertTrue(trimmed)
    }
}
