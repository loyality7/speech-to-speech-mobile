package com.example.s2stools

import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the reason [ToolContext] exists: session A and session B never see
 * each other's open page, even though both call the same registered tools.
 */
class BrowserToolTest {

    private fun context(sessionId: String, callId: String = "1") =
        ToolContext(sessionId = sessionId, turnId = "1", callId = callId)

    @Test
    fun `sessions do not share an open page`() {
        val registry = ToolRegistry()
        BrowserTool.registerOn(registry)

        val sessionA = context("session-A")
        val sessionB = context("session-B")

        registry.execute(ToolCall("browse_open", mapOf("url" to "https://a.example")), sessionA)
        registry.execute(ToolCall("browse_open", mapOf("url" to "https://b.example")), sessionB)
        registry.execute(ToolCall("browse_click", mapOf("selector" to "#a-button")), sessionA)

        val readA = registry.execute(ToolCall("browse_read", emptyMap()), sessionA)
        val readB = registry.execute(ToolCall("browse_read", emptyMap()), sessionB)

        assertTrue(readA.output.contains("https://a.example"))
        assertTrue(readA.output.contains("#a-button"))
        assertTrue(readB.output.contains("https://b.example"))
        assertTrue(readB.output.contains("lastClicked=none")) // session B's click never happened
    }

    @Test
    fun `click before open fails cleanly instead of touching another session`() {
        val registry = ToolRegistry()
        BrowserTool.registerOn(registry)

        val result = registry.execute(
            ToolCall("browse_click", mapOf("selector" to "#x")),
            context("never-opened-session"),
        )

        assertEquals("No page open in this session — call browse_open first", result.output)
    }

    @Test
    fun `closing a session drops its state`() {
        val registry = ToolRegistry()
        BrowserTool.registerOn(registry)
        val ctx = context("throwaway-session")

        registry.execute(ToolCall("browse_open", mapOf("url" to "https://x.example")), ctx)
        BrowserTool.closeSession(ctx.sessionId)
        val result = registry.execute(ToolCall("browse_read", emptyMap()), ctx)

        assertEquals("No page open in this session", result.output)
    }
}
