package com.s2s.plugin.s1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the exact input protocol and output validation, without a model.
 *
 * These are the parts that must be byte-correct: the model card states the
 * system prompt and control line are part of the trained input format, so a
 * silently reworded prompt is a silently degraded model. A wrong prompt does
 * not throw — it just produces worse text — which is exactly why it needs a
 * test rather than a code review.
 *
 * Model behaviour itself cannot be tested here (no model in a JVM unit
 * test); that is what the plugin's own "Try it" screen and the on-device
 * runs are for.
 */
class S1MiniProtocolTest {

    // ── Exact system prompt (model card, verbatim) ───────────────────────

    @Test
    fun `system prompt matches the model card exactly`() {
        assertEquals(
            "You are a text normalizer for speech-to-text transcripts. The input begins with a " +
                "control line specifying the styling, structure, and context settings; clean the " +
                "transcript to match those settings and output only the cleaned text.",
            S1MiniProtocol.SYSTEM_PROMPT,
        )
    }

    // ── Exact control line ──────────────────────────────────────────────

    @Test
    fun `control line uses the documented format`() {
        assertEquals(
            "[Styling: formal] [Structure: lists] [Context: email]",
            S1MiniProtocol.controlLine("formal", "lists", "email"),
        )
    }

    @Test
    fun `control line falls back to documented defaults for missing values`() {
        assertEquals(
            "[Styling: semi-casual] [Structure: prose] [Context: general]",
            S1MiniProtocol.controlLine(null, null, null),
        )
    }

    @Test
    fun `control line rejects values outside the documented vocabulary`() {
        // An out-of-vocabulary control value is the kind of malformed input
        // that yields confidently wrong output, so it must never reach the
        // model — the documented default is used instead.
        val line = S1MiniProtocol.controlLine("shouty", "haiku", "telepathy")
        assertEquals("[Styling: semi-casual] [Structure: prose] [Context: general]", line)
    }

    @Test
    fun `control line accepts every documented value`() {
        setOf("casual", "semi-casual", "semi-formal", "formal").forEach { styling ->
            assertTrue(S1MiniProtocol.controlLine(styling, null, null).contains("[Styling: $styling]"))
        }
        setOf("prose", "lists").forEach { structure ->
            assertTrue(S1MiniProtocol.controlLine(null, structure, null).contains("[Structure: $structure]"))
        }
        setOf("general", "email").forEach { context ->
            assertTrue(S1MiniProtocol.controlLine(null, null, context).contains("[Context: $context]"))
        }
    }

    @Test
    fun `control line is case insensitive on input but lowercase on output`() {
        assertEquals(
            "[Styling: formal] [Structure: prose] [Context: general]",
            S1MiniProtocol.controlLine("FORMAL", "Prose", " General "),
        )
    }

    @Test
    fun `user turn is the control line then the transcript on the next line`() {
        val turn = S1MiniProtocol.userTurn("hello there", "casual", "prose", "general")
        assertEquals("[Styling: casual] [Structure: prose] [Context: general]\nhello there", turn)
    }

    // ── Output cleaning ─────────────────────────────────────────────────

    @Test
    fun `empty think block is stripped`() {
        // The GGUF card notes the assistant turn begins with an empty think
        // block; depending on the runtime it can land in the decoded text.
        assertEquals(
            "I need to send the report by Thursday.",
            S1MiniProtocol.cleanOutput("<think>\n\n</think>\n\nI need to send the report by Thursday."),
        )
    }

    @Test
    fun `chat template markers are stripped`() {
        assertEquals(
            "Send it to support@superwhisper.com.",
            S1MiniProtocol.cleanOutput("<|im_start|>Send it to support@superwhisper.com.<|im_end|>"),
        )
    }

    @Test
    fun `surrounding quotes are stripped`() {
        assertEquals("The meeting is at three.", S1MiniProtocol.cleanOutput("\"The meeting is at three.\""))
    }

    // ── Output validation ───────────────────────────────────────────────

    @Test
    fun `plausible normalization is accepted`() {
        val raw = "so um i need to like send the the report by uh friday no wait make that thursday"
        assertTrue(S1MiniProtocol.isValidOutput("I need to send the report by Thursday.", raw))
    }

    @Test
    fun `documented model card examples pass validation`() {
        // The card's own examples must not be rejected by our own guard —
        // an over-strict validator would discard correct normalizations.
        val cases = listOf(
            "the invoice came to twenty three thousand four hundred and fifty dollars and it's due on march third twenty twenty six"
                to "The invoice came to \$23,450, and it's due on March 3, 2026.",
            "send it to support at superwhisper dot com" to "Send it to support@superwhisper.com.",
        )
        cases.forEach { (raw, output) ->
            assertTrue("should accept: $output", S1MiniProtocol.isValidOutput(output, raw))
        }
    }

    @Test
    fun `empty output is rejected`() {
        assertFalse(S1MiniProtocol.isValidOutput("", "some real transcript here"))
        assertFalse(S1MiniProtocol.isValidOutput("   ", "some real transcript here"))
    }

    @Test
    fun `leaked control line is rejected`() {
        assertFalse(
            S1MiniProtocol.isValidOutput(
                "[Styling: casual] [Structure: prose] [Context: general] hello there",
                "hello there",
            ),
        )
    }

    @Test
    fun `leaked system prompt is rejected`() {
        assertFalse(
            S1MiniProtocol.isValidOutput(
                "You are a text normalizer for speech-to-text transcripts.",
                "hello there friend how are you",
            ),
        )
    }

    @Test
    fun `leaked chat markers are rejected`() {
        assertFalse(S1MiniProtocol.isValidOutput("<|im_start|>hello", "hello there friend"))
        assertFalse(S1MiniProtocol.isValidOutput("<think>hmm</think>", "hello there friend"))
    }

    @Test
    fun `tool-call shaped output is rejected`() {
        // Must never be forwarded as if the user had said it.
        assertFalse(
            S1MiniProtocol.isValidOutput(
                """{"tool": "send_email", "arguments": {}}""",
                "send an email to bob about the thing",
            ),
        )
    }

    @Test
    fun `wildly expanded output is rejected`() {
        val raw = "call john"
        val rambling = "Certainly! I would be happy to help you place a telephone call to John. " +
            "Here are several considerations before we proceed with dialling."
        assertFalse(S1MiniProtocol.isValidOutput(rambling, raw))
    }

    @Test
    fun `output that lost almost all the content is rejected`() {
        val raw = "so i was thinking we should probably move the deployment to next tuesday afternoon instead"
        assertFalse("dropping the whole utterance is not normalization", S1MiniProtocol.isValidOutput("OK.", raw))
    }

    @Test
    fun `legitimate shortening is accepted`() {
        // Normalization removes filler, so getting shorter is normal and
        // must not be mistaken for content loss.
        val raw = "um so like i mean basically uh the the meeting is at three o'clock i think"
        assertTrue(S1MiniProtocol.isValidOutput("The meeting is at 3 o'clock.", raw))
    }
}
