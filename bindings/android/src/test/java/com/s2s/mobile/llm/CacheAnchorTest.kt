package com.s2s.mobile.llm

import com.s2s.mobile.pipeline.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Losing the cache anchor is silent and permanent — the conversation simply gets
 * slower, measured at 11.3 s to first token once it happened. These cover the
 * ways it was actually lost on a device.
 */
class CacheAnchorTest {

    private fun msg(role: String, content: String) = ChatMessage(role, content)

    @Test
    fun `finds the message the cache ends at`() {
        val messages = listOf(
            msg("system", "You are helpful."),
            msg("user", "Hello"),
            msg("assistant", "Hi there."),
        )
        assertEquals(2, CacheAnchor.indexIn(messages, "Hi there."))
    }

    @Test
    fun `survives a trailing newline from the model`() {
        // The regression: llama emits "Hi there.\n", the engine commits it through
        // trim() on the barge-in path, and an exact comparison never matches again.
        val messages = listOf(
            msg("user", "Hello"),
            msg("assistant", "Hi there."),
        )
        assertEquals(1, CacheAnchor.indexIn(messages, "Hi there.\n"))
    }

    @Test
    fun `survives leading and trailing whitespace on either side`() {
        val messages = listOf(msg("assistant", "  Hi there.  "))
        assertEquals(0, CacheAnchor.indexIn(messages, "\nHi there.\n"))
    }

    @Test
    fun `returns the last match when the assistant repeats itself`() {
        // "Sure." twice in a conversation is ordinary. The cache ends at the most
        // recent one; anchoring on the earlier would replay turns already cached.
        val messages = listOf(
            msg("assistant", "Sure."),
            msg("user", "and again?"),
            msg("assistant", "Sure."),
        )
        assertEquals(2, CacheAnchor.indexIn(messages, "Sure."))
    }

    @Test
    fun `reports no anchor when the message was trimmed out of history`() {
        val messages = listOf(msg("user", "Hello"), msg("assistant", "Hi."))
        assertEquals(-1, CacheAnchor.indexIn(messages, "something dropped long ago"))
    }

    @Test
    fun `reports no anchor on the first turn`() {
        assertEquals(-1, CacheAnchor.indexIn(listOf(msg("user", "Hello")), null))
    }

    @Test
    fun `treats a blank tail as no anchor`() {
        // A cancelled turn can leave an empty generation; matching that against a
        // blank message would reuse a cache that holds nothing.
        val messages = listOf(msg("assistant", "   "))
        assertEquals(-1, CacheAnchor.indexIn(messages, "   "))
    }
}
