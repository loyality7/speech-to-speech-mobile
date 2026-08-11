package com.s2s.mobile.text

import com.s2s.mobile.pipeline.TextChunker

/**
 * Splits a token stream into speakable sentences. Port of the Python
 * `LLM/lm_output_processor.py`.
 *
 * The point is latency: sentence one is synthesised while the model is still
 * writing sentence two. Cutting in the wrong place is audible, so abbreviations,
 * decimals and ellipses do not end a sentence.
 *
 * Not thread-safe — drive it from one turn at a time.
 */
class SentenceChunker(
    private val firstChunkMinChars: Int = 24,
    private val maxChunkChars: Int = 120,
    private val minChunkChars: Int = 20,
) : TextChunker {

    private val buffer = StringBuilder()
    private var emittedAny = false

    override fun accept(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        buffer.append(token)

        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut(buffer) ?: break
            val sentence = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (sentence.isNotEmpty()) {
                out += sentence
                emittedAny = true
            }
        }
        return out
    }

    override fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        if (rest.isNotEmpty()) emittedAny = true
        return rest.ifEmpty { null }
    }

    override fun reset() {
        buffer.setLength(0)
        emittedAny = false
    }

    /** Index just past the end of the first complete sentence, or null. */
    private fun findCut(text: CharSequence): Int? {
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c != '.' && c != '!' && c != '?') continue

            // "..." — run to the end of the repeat, then treat it as one break.
            var end = i
            while (end + 1 < text.length && text[end + 1] == c) end++
            if (end + 1 >= text.length) return null // the run may still be growing

            if (c == '.' && !endsSentence(text, i)) continue

            // Swallow trailing quotes and brackets so they ride with the sentence.
            var cut = end + 1
            while (cut < text.length && text[cut] in CLOSERS) cut++

            // Synthesis costs a fixed 200-400 ms per call whatever the length, so
            // a fragment like the "2." of a numbered list is nearly all overhead —
            // measured at 631 ms of compute for 480 ms of audio, which is heard as
            // stutter. Keep scanning so it is spoken with the line that follows.
            if (text.subSequence(0, cut).trim().length < minChunkChars &&
                text.length < maxChunkChars
            ) {
                continue
            }
            return cut
        }

        // No terminal punctuation yet. Break early anyway, at a clause if there is
        // one, otherwise at a word boundary.
        //
        // The opening chunk goes short so the first word is spoken quickly. Every
        // later chunk is capped too: synthesis time scales with text length, and a
        // whole remaining sentence in one call means the listener hears a short
        // burst, a gap, then the rest arriving in a lump. Capped chunks keep audio
        // arriving steadily while the next one is already being synthesised.
        val threshold = if (emittedAny) maxChunkChars else firstChunkMinChars
        if (text.length >= threshold) {
            val clause = text.indexOfFirst { it == ',' || it == ';' || it == ':' }
            if (clause >= 0 && clause < threshold) return clause + 1
            val space = text.lastIndexOf(' ')
            if (space > 0) return space + 1
        }
        return null
    }

    /** A '.' at [i] ends a sentence only if it is not a decimal or abbreviation. */
    private fun endsSentence(text: CharSequence, i: Int): Boolean {
        // 3.14 — digits on both sides.
        if (i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()) return false

        // Single initial: "J. R. R." — one letter preceded by a boundary.
        if (i >= 1 && text[i - 1].isLetter() && (i == 1 || !text[i - 2].isLetterOrDigit())) return false

        var start = i
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        return text.substring(start, i).lowercase() !in ABBREVIATIONS
    }

    private companion object {
        val CLOSERS = charArrayOf('"', '\'', ')', ']', '}', '\u00BB', '\u201D', '\u2019')

        val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt",
            "e.g", "i.e", "etc", "vs", "approx", "dept", "est",
            "fig", "no", "vol", "inc", "ltd", "co", "corp",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
            "a.m", "p.m", "u.s", "u.k",
        )
    }
}
