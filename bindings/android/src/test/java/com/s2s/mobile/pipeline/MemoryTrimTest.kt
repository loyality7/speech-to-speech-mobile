package com.s2s.mobile.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimTest {

    @Test
    fun testLanguageModelInterfaceTrimMemory() {
        var trimmed = false
        val model = object : LanguageModel {
            override fun initialize() = Result.success(Unit)
            override fun generate(
                messages: List<ChatMessage>,
                sink: TokenSink,
                overrides: GenerationOverrides?,
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
