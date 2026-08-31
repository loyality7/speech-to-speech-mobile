package com.s2s.mobile.internal

import com.s2s.mobile.config.TurnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour these tests protect is the distinction between an acoustic
 * segment and a conversational turn. A fake clock is used throughout: real
 * sleeps would make the suite slow and flaky, and the logic under test is
 * entirely a function of elapsed time, which a fake clock represents exactly.
 */
class TurnAggregatorTest {

    private var clock = 0L
    private fun config(
        baseDelayMs: Long = 500,
        incompleteGraceMs: Long = 700,
        maxIncompleteExtensions: Int = 3,
        shortUtteranceGraceMs: Long = 400,
        shortUtteranceWords: Int = 3,
        completeUtteranceDelayMs: Long = 250,
        trustTerminalPunctuation: Boolean = true,
        maxTurnDurationMs: Long = 30_000,
    ) = TurnConfig(
        baseDelayMs = baseDelayMs,
        incompleteGraceMs = incompleteGraceMs,
        maxIncompleteExtensions = maxIncompleteExtensions,
        shortUtteranceGraceMs = shortUtteranceGraceMs,
        shortUtteranceWords = shortUtteranceWords,
        completeUtteranceDelayMs = completeUtteranceDelayMs,
        trustTerminalPunctuation = trustTerminalPunctuation,
        maxTurnDurationMs = maxTurnDurationMs,
    )

    private fun aggregator(config: TurnConfig = config()) = TurnAggregator(config) { clock }

    private fun advance(ms: Long) {
        clock += ms
    }

    // ── The core case: one thought, several pauses ──────────────────────

    @Test
    fun `one thought split across three segments commits once with the whole text`() {
        // "Set a reminder ... for tomorrow morning ... to call the dentist"
        // Two thinking pauses. Before the aggregator this produced three
        // permanent messages and three LLM requests.
        val a = aggregator()

        val first = a.offer("Set a reminder")
        assertEquals("Set a reminder", first?.transcript)
        assertFalse("a segment is never itself a finished turn", first!!.isFinal)

        advance(400)
        assertNull("still inside the endpoint window", a.commitIfDue())

        val second = a.offer("for tomorrow morning")
        assertEquals("Set a reminder for tomorrow morning", second?.transcript)
        assertFalse(second!!.isFinal)

        advance(400)
        assertNull("the new segment re-armed the window", a.commitIfDue())

        val third = a.offer("to call the dentist")
        assertEquals("Set a reminder for tomorrow morning to call the dentist", third?.transcript)

        // Now the user actually stops.
        advance(1_500)
        val committed = a.commitIfDue()
        assertNotNull("the turn must eventually commit", committed)
        assertTrue(committed!!.isFinal)
        assertEquals("Set a reminder for tomorrow morning to call the dentist", committed.transcript)
    }

    @Test
    fun `commit happens exactly once — a second call returns null`() {
        // Idempotence by construction: the engine dispatches its single
        // request on a non-null return, so a second non-null would be a
        // duplicate request.
        val a = aggregator()
        a.offer("what is the capital of France")
        advance(2_000)

        assertNotNull(a.commitIfDue())
        assertNull("a committed turn must never commit again", a.commitIfDue())
        assertNull(a.commitIfDue())
    }

    @Test
    fun `a new turn can start after the previous one committed`() {
        val a = aggregator()
        a.offer("first question")
        advance(2_000)
        assertEquals("first question", a.commitIfDue()?.transcript)

        val next = a.offer("second question")
        assertEquals("second question", next?.transcript)
        advance(2_000)
        assertEquals("second question", a.commitIfDue()?.transcript)
    }

    // ── Adaptive endpointing ────────────────────────────────────────────

    @Test
    fun `a transcript ending in a conjunction waits longer than the base delay`() {
        val a = aggregator(config(baseDelayMs = 500, incompleteGraceMs = 700))
        a.offer("I want to book a table and")

        advance(600)
        assertNull("500ms base would have committed; 'and' must extend it", a.commitIfDue())

        advance(700)
        assertNotNull("but it must still commit eventually", a.commitIfDue())
    }

    @Test
    fun `hesitation fillers extend the window`() {
        val a = aggregator(config(baseDelayMs = 500, incompleteGraceMs = 700))
        a.offer("the answer is probably um")

        advance(600)
        assertNull("'um' means the user is still thinking", a.commitIfDue())
    }

    @Test
    fun `extensions are capped so a habitual trailer still gets an answer`() {
        // Someone who ends every pause with "and" must not wait forever.
        val a = aggregator(config(baseDelayMs = 100, incompleteGraceMs = 500, maxIncompleteExtensions = 2))
        a.offer("this and")

        // Each commitIfDue() that sees an incomplete tail burns one extension.
        advance(200)
        assertNull(a.commitIfDue()) // extension 1
        advance(200)
        assertNull(a.commitIfDue()) // extension 2
        advance(200)
        // Cap reached: the incomplete rule no longer applies, so the base
        // delay (already elapsed) governs and the turn commits.
        assertNotNull("past maxIncompleteExtensions the turn must commit", a.commitIfDue())
    }

    @Test
    fun `a very short transcript waits longer than a long one`() {
        val short = aggregator(config(baseDelayMs = 500, shortUtteranceGraceMs = 400, shortUtteranceWords = 3))
        short.offer("set a")
        advance(600)
        assertNull("two words are more likely a sentence opening than a whole turn", short.commitIfDue())

        val long = aggregator(config(baseDelayMs = 500, shortUtteranceGraceMs = 400, shortUtteranceWords = 3))
        long.offer("please set a reminder for tomorrow morning")
        advance(600)
        assertNotNull("a long complete-looking sentence commits at the base delay", long.commitIfDue())
    }

    @Test
    fun `terminal punctuation commits faster than the base delay`() {
        val a = aggregator(config(baseDelayMs = 500, completeUtteranceDelayMs = 250))
        a.offer("What is the capital of France?")

        advance(300)
        assertNotNull("the recogniser already judged this sentence complete", a.commitIfDue())
    }

    @Test
    fun `terminal punctuation is ignored when the recogniser is not trusted for it`() {
        val a = aggregator(
            config(baseDelayMs = 500, completeUtteranceDelayMs = 250, trustTerminalPunctuation = false),
        )
        a.offer("What is the capital of France?")

        advance(300)
        assertNull("with trust disabled the base delay governs", a.commitIfDue())
        advance(300)
        assertNotNull(a.commitIfDue())
    }

    // ── Hard limits ─────────────────────────────────────────────────────

    @Test
    fun `maxTurnDuration commits a turn that never stops accumulating`() {
        // Guarantees the system always eventually answers. Without this, a
        // monologue is never replied to at all.
        val a = aggregator(config(baseDelayMs = 500, maxTurnDurationMs = 3_000))

        // Keep speaking: every 200ms a new segment re-arms the window, so the
        // endpoint rule alone would never fire.
        repeat(30) {
            a.offer("and still talking")
            advance(200)
            if (clock < 3_000) assertNull("before the ceiling the window keeps re-arming", a.commitIfDue())
        }

        assertNotNull("the hard ceiling must override every extension", a.commitIfDue())
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `blank and whitespace segments never open a turn`() {
        val a = aggregator()
        assertNull(a.offer(""))
        assertNull(a.offer("   "))
        assertFalse("an empty decode must not start a turn", a.hasActiveTurn)
        advance(5_000)
        assertNull(a.commitIfDue())
    }

    @Test
    fun `commitIfDue with no active turn is a no-op`() {
        val a = aggregator()
        advance(10_000)
        assertNull(a.commitIfDue())
    }

    @Test
    fun `abandon drops the turn and returns what had accumulated`() {
        val a = aggregator()
        a.offer("this was interrupted")

        assertEquals("this was interrupted", a.abandon())
        assertFalse(a.hasActiveTurn)
        advance(5_000)
        assertNull("an abandoned turn must never commit", a.commitIfDue())
    }

    @Test
    fun `abandon with no active turn returns null`() {
        assertNull(aggregator().abandon())
    }

    @Test
    fun `segments are joined with single spaces`() {
        val a = aggregator()
        a.offer("  leading and trailing  ")
        a.offer("  second  ")
        advance(2_000)
        assertEquals("leading and trailing second", a.commitIfDue()?.transcript)
    }
}
