package com.s2s.mobile.tools

import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Proves the reason [ToolContext] exists: a stateful tool (open a page, click,
 * read) can key its session state on [ToolContext.sessionId] and never leak
 * state across two unrelated conversations, while still being able to tell
 * apart two calls within the same turn.
 */
class ToolContextTest {

    /** Stand-in for a webdroid-style tool: one open "page" per session. */
    private class FakeBrowserTool {
        private val openPages = mutableMapOf<String, String>()

        fun open(context: ToolContext, url: String): String {
            openPages[context.sessionId] = url
            return "opened $url"
        }

        fun read(context: ToolContext): String =
            openPages[context.sessionId] ?: "no page open for this session"
    }

    @Test
    fun `two sessions do not share tool state`() {
        val tool = FakeBrowserTool()
        val registry = ToolRegistry()
        registry.register(ToolDefinition("browse_open", "opens a page")) { ctx, args ->
            tool.open(ctx, args.getValue("url"))
        }
        registry.register(ToolDefinition("browse_read", "reads the open page")) { ctx, _ ->
            tool.read(ctx)
        }

        val sessionA = ToolContext(sessionId = "session-A", turnId = "1", callId = "1")
        val sessionB = ToolContext(sessionId = "session-B", turnId = "1", callId = "1")

        registry.execute(ToolCall("browse_open", mapOf("url" to "https://a.example")), sessionA)
        val resultB = registry.execute(ToolCall("browse_read", emptyMap()), sessionB)
        val resultA = registry.execute(ToolCall("browse_read", emptyMap()), sessionA)

        assertEquals("no page open for this session", resultB.output)
        assertEquals("https://a.example", resultA.output)
    }

    @Test
    fun `calls in the same turn share sessionId and turnId but not callId`() {
        val first = ToolContext(sessionId = "s", turnId = "3", callId = "1")
        val second = ToolContext(sessionId = "s", turnId = "3", callId = "2")

        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.turnId, second.turnId)
        assertNotEquals(first.callId, second.callId)
    }
}
