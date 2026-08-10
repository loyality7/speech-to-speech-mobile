package com.s2s.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chunker decides where the assistant's voice pauses, so a wrong cut is
 * audible. These cover the cases that actually bite: abbreviations, decimals and
 * ellipses that look like sentence ends but are not.
 */
class SentenceChunkerTest {

    /** Feeds text one character at a time, the way tokens really arrive. */
    private fun chunk(text: String, minChars: Int = 1000): List<String> {
        val chunker = SentenceChunker(firstChunkMinChars = minChars)
        val out = text.map { chunker.accept(it.toString()) }.flatten().toMutableList()
        chunker.flush()?.let { out += it }
        return out
    }

    @Test
    fun `splits on terminal punctuation`() {
        assertEquals(
            listOf("Hello there.", "How are you?", "Great!"),
            chunk("Hello there. How are you? Great!"),
        )
    }

    @Test
    fun `does not split inside decimals`() {
        assertEquals(listOf("Pi is 3.14 exactly."), chunk("Pi is 3.14 exactly."))
    }

    @Test
    fun `does not split after abbreviations`() {
        assertEquals(
            listOf("Dr. Smith arrived.", "See e.g. the manual."),
            chunk("Dr. Smith arrived. See e.g. the manual."),
        )
    }

    @Test
    fun `does not split on initials`() {
        assertEquals(listOf("J. R. R. Tolkien wrote it."), chunk("J. R. R. Tolkien wrote it."))
    }

    @Test
    fun `treats an ellipsis as one break`() {
        assertEquals(listOf("Well...", "maybe."), chunk("Well... maybe."))
    }

    @Test
    fun `keeps closing punctuation with its sentence`() {
        assertEquals(listOf("He said \"stop.\"", "Then left."), chunk("He said \"stop.\" Then left."))
    }

    @Test
    fun `splits on newline`() {
        assertEquals(listOf("One", "Two"), chunk("One\nTwo"))
    }

    @Test
    fun `flushes an unterminated tail`() {
        assertEquals(listOf("Done.", "no period here"), chunk("Done. no period here"))
    }

    @Test
    fun `breaks the opening chunk early at a word boundary`() {
        // No terminal punctuation yet: the opening chunk is released at a word
        // boundary so synthesis can start instead of waiting for a full stop.
        val chunker = SentenceChunker(firstChunkMinChars = 10)
        val emitted = "Well now that is interesting and more".map { chunker.accept(it.toString()) }.flatten()
        assertEquals(listOf("Well now"), emitted)
    }

    @Test
    fun `prefers a clause boundary for the opening chunk`() {
        val chunker = SentenceChunker(firstChunkMinChars = 10)
        val emitted = "Well then, onwards we go".map { chunker.accept(it.toString()) }.flatten()
        assertEquals(listOf("Well then,"), emitted)
    }

    @Test
    fun `later chunks are capped so playback does not stall`() {
        // Past the opening chunk the cap still applies, otherwise the whole rest of
        // a sentence is synthesised in one call and arrives as a lump.
        val chunker = SentenceChunker(firstChunkMinChars = 10, maxChunkChars = 20)
        "First one. ".forEach { chunker.accept(it.toString()) }
        val after = "then a much longer clause that keeps going".map { chunker.accept(it.toString()) }.flatten()
        assertEquals(listOf("then a much longer", "clause that keeps"), after)
    }

    @Test
    fun `a short sentence is not split by the cap`() {
        val chunker = SentenceChunker(firstChunkMinChars = 100, maxChunkChars = 100)
        assertEquals(listOf("All good."), chunk("All good.", minChars = 100))
        assertEquals(emptyList<String>(), chunker.accept("short"))
    }

    @Test
    fun `flush returns null when empty`() {
        assertNull(SentenceChunker().flush())
    }

    @Test
    fun `waits for a growing ellipsis instead of cutting mid run`() {
        val chunker = SentenceChunker(firstChunkMinChars = 1000)
        assertEquals(emptyList<String>(), chunker.accept("Hmm.."))
        assertEquals(listOf("Hmm..."), chunker.accept(". "))
    }
}
