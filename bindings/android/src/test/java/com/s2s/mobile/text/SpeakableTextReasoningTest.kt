package com.s2s.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeakableText.stripReasoning] discards model output, so the bar for these
 * tests is not just "does it strip reasoning" but "can it ever eat a real
 * answer". The second question matters more: speaking some reasoning is
 * annoying, losing the answer entirely is a broken assistant.
 */
class SpeakableTextReasoningTest {

    // ── Tagged reasoning ────────────────────────────────────────────────

    @Test
    fun `removes a think block and keeps the answer`() {
        val raw = "<think>The user wants the capital. It is Paris.</think>The capital of France is Paris."
        assertEquals("The capital of France is Paris.", SpeakableText.stripReasoning(raw))
    }

    @Test
    fun `removes a multi-line think block`() {
        val raw = """
            <think>
            Step 1: parse the question.
            Step 2: recall the answer.
            </think>
            It is Paris.
        """.trimIndent()
        assertEquals("It is Paris.", SpeakableText.stripReasoning(raw))
    }

    @Test
    fun `handles every recognised tag name, case-insensitively`() {
        listOf("think", "thinking", "thought", "reasoning", "scratchpad").forEach { tag ->
            val raw = "<${tag.uppercase()}>deliberating</${tag.uppercase()}>The answer."
            assertEquals("tag $tag", "The answer.", SpeakableText.stripReasoning(raw))
        }
    }

    // ── Untagged reasoning ──────────────────────────────────────────────

    @Test
    fun `strips an untagged preamble when a blank line separates the answer`() {
        // The exact shape a real device produced.
        val raw = """
            Here's a thinking process:
            Analyze User Input: the user asked about cookies.
            Check Constraints & Tools: no tool needed.

            Sounds like you enjoyed a tasty treat! How were the cookies?
        """.trimIndent()

        val result = SpeakableText.stripReasoning(raw)
        assertEquals("Sounds like you enjoyed a tasty treat! How were the cookies?", result)
        assertFalse("the deliberation must not be spoken", result.contains("thinking process"))
        assertFalse(result.contains("Check Constraints"))
    }

    @Test
    fun `keeps the whole reply when an untagged preamble has no answer boundary`() {
        // No blank line means the end of the reasoning cannot be located.
        // Keeping everything speaks too much; guessing could speak nothing.
        // Too much is the recoverable failure, so that is the choice.
        val raw = "Let me think about this. It is probably Paris."
        assertEquals(raw, SpeakableText.stripReasoning(raw))
    }

    // ── Must never eat a real answer ────────────────────────────────────

    @Test
    fun `ordinary speech is returned completely unchanged`() {
        listOf(
            "The capital of France is Paris.",
            "I have set a reminder for tomorrow morning.",
            "Sure, what would you like me to remember?",
            "It is 14:30.",
            "",
            "   ",
        ).forEach { raw ->
            assertEquals("must not alter ordinary speech", raw, SpeakableText.stripReasoning(raw))
        }
    }

    @Test
    fun `a reasoning phrase appearing mid-answer is not treated as a preamble`() {
        // The preamble pattern is anchored to the start of the reply, so an
        // answer that merely mentions thinking survives intact.
        val raw = "You should take a moment to think about this before deciding.\n\nIt is worth it."
        assertEquals(raw, SpeakableText.stripReasoning(raw))
    }

    @Test
    fun `a reply that is ONLY a tagged reasoning block says something instead of nothing`() {
        // Stripping leaves nothing to say. Silence is indistinguishable from a
        // hang, and the reasoning itself must not be spoken, so a short
        // placeholder is used — it cannot be mistaken for an answer.
        val raw = "<think>I considered it at length and reached no conclusion.</think>"
        val result = SpeakableText.stripReasoning(raw)

        assertEquals(SpeakableText.REASONING_ONLY_PLACEHOLDER, result)
        assertFalse("the deliberation must not survive", result.contains("considered"))
    }

    @Test
    fun `an unclosed reasoning tag also yields the placeholder, never the deliberation`() {
        val raw = "<think>I am still working through this and never finished"
        val result = SpeakableText.stripReasoning(raw)

        assertEquals(SpeakableText.REASONING_ONLY_PLACEHOLDER, result)
        assertFalse(result.contains("working through"))
    }

    @Test
    fun `stripping composes with clean() for a tagged reply containing markdown`() {
        val raw = "<think>deliberating</think>Here are the **key** points: all good."
        val spoken = SpeakableText.clean(SpeakableText.stripReasoning(raw))
        assertFalse("reasoning gone", spoken.contains("deliberating"))
        assertFalse("markdown gone", spoken.contains("**"))
        assertTrue(spoken.contains("key"))
    }
}
