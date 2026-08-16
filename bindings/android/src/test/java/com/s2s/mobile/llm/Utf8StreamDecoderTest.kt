package com.s2s.mobile.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class Utf8StreamDecoderTest {

    @Test
    fun testSingleByteAsciiPassingThrough() {
        val decoder = Utf8StreamDecoder()
        val chunk1 = decoder.decodeChunk("Hello ")
        val chunk2 = decoder.decodeChunk("world!")
        val flushed = decoder.flush()

        assertEquals("Hello ", chunk1)
        assertEquals("world!", chunk2)
        assertEquals("", flushed)
    }

    @Test
    fun testMultiByteEmojiSplitAcrossChunks() {
        val decoder = Utf8StreamDecoder()
        // Emoji 😊 is 4 bytes: 0xF0 0x9F 0x98 0x8A
        val fullEmojiBytes = "😊".toByteArray(StandardCharsets.UTF_8)
        assertEquals(4, fullEmojiBytes.size)

        val firstHalf = fullEmojiBytes.copyOfRange(0, 2)
        val secondHalf = fullEmojiBytes.copyOfRange(2, 4)

        // First chunk contains partial 2 bytes -> decoder holds them
        val out1 = decoder.decodeChunk(firstHalf)
        assertEquals("", out1)

        // Second chunk delivers remaining 2 bytes -> decoder outputs full emoji 😊
        val out2 = decoder.decodeChunk(secondHalf)
        assertEquals("😊", out2)

        assertEquals("", decoder.flush())
    }

    @Test
    fun testCurlyQuoteSplitAcrossChunks() {
        val decoder = Utf8StreamDecoder()
        // Curly quote ’ is 3 bytes: 0xE2 0x80 0x99
        val quoteBytes = "’".toByteArray(StandardCharsets.UTF_8)
        assertEquals(3, quoteBytes.size)

        val out1 = decoder.decodeChunk(quoteBytes.copyOfRange(0, 1))
        assertEquals("", out1)

        val out2 = decoder.decodeChunk(quoteBytes.copyOfRange(1, 3))
        assertEquals("’", out2)
    }
}
