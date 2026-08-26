package com.s2s.mobile.llm

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLanguageModelTest {

    private fun model(config: RemoteLlmConfig = RemoteLlmConfig(baseUrl = "http://localhost:8000/v1")) =
        RemoteLanguageModel(config)

    @Test
    fun `extractDelta reads streamed content`() {
        val payload = """{"choices":[{"delta":{"content":"hel"}}]}"""
        assertEquals("hel", model().extractDelta(payload))
    }

    @Test
    fun `extractDelta returns null when choices missing`() {
        assertNull(model().extractDelta("""{"id":"x"}"""))
    }

    @Test
    fun `extractDelta returns null on malformed json`() {
        try {
            model().extractDelta("not json")
            error("expected exception")
        } catch (e: org.json.JSONException) {
            // expected — caller catches this and skips the chunk
        }
    }

    @Test
    fun `buildRequestBody applies overrides over config defaults`() {
        val config = RemoteLlmConfig(
            baseUrl = "http://localhost:8000/v1",
            remoteModelName = "qwen2.5",
            temperature = 0.7f,
            maxTokens = 256,
        )
        val overrides = GenerationOverrides(temperature = 0.1f, maxTokens = 64)
        val body = model(config).buildRequestBody(listOf(ChatMessage("user", "hi")), overrides)

        assertEquals("qwen2.5", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals(0.1, body.getDouble("temperature"), 0.001)
        assertEquals(64, body.getInt("max_tokens"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test
    fun `buildRequestBody falls back to config when overrides absent`() {
        val config = RemoteLlmConfig(baseUrl = "http://localhost:8000/v1", maxTokens = 256)
        val body = model(config).buildRequestBody(listOf(ChatMessage("user", "hi")), null)
        assertEquals(256, body.getInt("max_tokens"))
    }
}
