package com.s2s.mobile.llm

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Stream decoder that safely handles multi-byte UTF-8 characters and surrogate pairs
 * split across streaming token boundaries (e.g. 4-byte emojis like 😊 or smart quotes).
 */
class Utf8StreamDecoder {

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var pendingBytes = ByteArray(0)

    @Synchronized
    fun decodeChunk(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val combined = pendingBytes + bytes
        val bb = ByteBuffer.wrap(combined)
        val cb = CharBuffer.allocate(combined.size * 2)

        decoder.decode(bb, cb, false)

        val remaining = bb.remaining()
        if (remaining > 0) {
            pendingBytes = ByteArray(remaining)
            bb.get(pendingBytes)
        } else {
            pendingBytes = ByteArray(0)
        }

        cb.flip()
        return cb.toString()
    }

    @Synchronized
    fun decodeChunk(text: String): String {
        return decodeChunk(text.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun flush(): String {
        if (pendingBytes.isEmpty()) return ""
        val bb = ByteBuffer.wrap(pendingBytes)
        val cb = CharBuffer.allocate(pendingBytes.size * 2)
        pendingBytes = ByteArray(0)

        decoder.decode(bb, cb, true)
        decoder.flush(cb)
        cb.flip()
        return cb.toString()
    }

    @Synchronized
    fun reset() {
        pendingBytes = ByteArray(0)
        decoder.reset()
    }
}
