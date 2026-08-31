package com.s2s.mobile.text

/**
 * Strips markup that must never reach a speech synthesiser.
 *
 * A real device caught why this cannot live in the system prompt alone: asked
 * what it could do, the model replied with a markdown bullet list and TTS read
 * the syntax aloud — "star star Providing information colon" — about 45 seconds
 * of it. Telling the model "no markdown" helps, but every model leaks
 * formatting eventually, and the engine cannot choose which model a host
 * plugs in. So the guarantee is enforced here, on the one path all spoken text
 * routes through, instead of trusted to a prompt.
 *
 * Deliberately conservative: it removes markers, never words. Anything it does
 * not recognise is passed through unchanged, because dropping a word the user
 * needed to hear is worse than speaking a stray character.
 */
object SpeakableText {
    private val BULLET = Regex("""^\s{0,8}[*+\-•]\s+""", RegexOption.MULTILINE)
    private val NUMBERED = Regex("""^\s{0,8}\d{1,3}[.)]\s+""", RegexOption.MULTILINE)
    private val HEADING = Regex("""^\s{0,8}#{1,6}\s+""", RegexOption.MULTILINE)
    private val EMPHASIS = Regex("""(\*{1,3}|_{2,3})(?=\S)(.+?)(?<=\S)\1""", RegexOption.DOT_MATCHES_ALL)
    private val CODE_FENCE = Regex("""```[^\n]*\n?""")
    private val INLINE_CODE = Regex("""`([^`]+)`""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val LEFTOVER_MARKS = Regex("""[*_`#]""")
    private val BLANK_RUN = Regex("""\n{2,}""")
    private val SPACE_RUN = Regex("""[ \t]{2,}""")

    /**
     * Explicit reasoning blocks, as emitted by reasoning models. Matched
     * case-insensitively across lines; an unclosed opener runs to the end,
     * because a truncated reasoning block is still reasoning.
     */
    private val REASONING_BLOCK = Regex(
        """<(think|thinking|thought|reasoning|scratchpad)>.*?(</\1>|$)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * A reasoning preamble with no tags at all — a model that simply starts
     * narrating its deliberation in prose.
     *
     * Only ever drops text BEFORE a recognised answer boundary, and only when
     * the reply opens with one of these markers, so ordinary speech that
     * happens to contain the phrase later is untouched. Deliberately narrow:
     * silently discarding model output is dangerous, and the failure mode of
     * being too narrow (some reasoning is spoken) is far better than the
     * failure mode of being too broad (the actual answer is discarded).
     */
    /**
     * Spoken when a reply turns out to be nothing but reasoning. Short and
     * unmistakably not an answer — silence would read as a hang, and the
     * reasoning itself is what must not be spoken.
     */
    const val REASONING_ONLY_PLACEHOLDER = "Thinking."

    private val REASONING_PREAMBLE = Regex(
        """^\s*(here'?s (my |a )?(thinking|thought) process|let me think|thinking through|analy[sz]e user input|my (thinking|thought) process)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Strips model reasoning from a COMPLETE reply, before it is chunked into
     * sentences.
     *
     * Must run on the whole reply rather than in [clean]: a reasoning block
     * spans many sentences, and [clean] only ever sees one at a time — by then
     * the opening tag is in a different chunk from the closing one.
     *
     * A real device made this necessary. Pointed at a reasoning model
     * (`openrouter/free` routed to MiniMax M3), the assistant read its entire
     * chain of thought aloud — "Here's a thinking process:", "Analyze User
     * Input:", "Check Constraints & Tools:", and then it recited the system
     * prompt's own rules back, including "I should not use markdown" — roughly
     * 45 seconds of audio before reaching the answer, at 9.1s to first audio.
     *
     * Returns the input unchanged when nothing matches, so a model that emits
     * no reasoning pays only two failed regex matches.
     */
    fun stripReasoning(raw: String): String {
        if (raw.isBlank()) return raw

        val withoutBlocks = REASONING_BLOCK.replace(raw, "").trim()
        // A reply that was ONLY a reasoning block leaves nothing to say. Three
        // options, all bad in different ways: speak the reasoning (what the
        // bug was), speak nothing (indistinguishable from a hang — the user
        // waits, hears silence, and assumes it broke), or say something short
        // and honest. The third is the least bad: it tells the user the turn
        // was received and is being worked on, in one word, and cannot be
        // mistaken for an answer.
        if (withoutBlocks.isBlank()) return REASONING_ONLY_PLACEHOLDER
        val candidate = withoutBlocks

        val match = REASONING_PREAMBLE.find(candidate) ?: return candidate

        // An untagged preamble has no closing marker, so the end of the
        // reasoning has to be guessed. A blank line is the only boundary worth
        // trusting: reasoning models separate deliberation from the answer
        // that way. Without one, the whole reply is deliberation as far as we
        // can tell — and we keep it rather than emit silence, because a wrong
        // guess that speaks too much is recoverable and a wrong guess that
        // speaks nothing is not.
        val afterPreamble = candidate.indexOf("\n\n", startIndex = match.range.last)
        if (afterPreamble < 0) return candidate
        return candidate.substring(afterPreamble).trim().ifBlank { candidate }
    }

    fun clean(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw
        // Links and code first: their inner text is what should be spoken, and
        // later passes would otherwise strip the delimiters and leave the URL.
        s = LINK.replace(s) { it.groupValues[1] }
        s = CODE_FENCE.replace(s, "")
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = HEADING.replace(s, "")
        s = BULLET.replace(s, "")
        s = NUMBERED.replace(s, "")
        s = EMPHASIS.replace(s) { it.groupValues[2] }
        s = LEFTOVER_MARKS.replace(s, "")
        // A list read aloud should flow, so a line break between items becomes
        // a pause rather than a hard stop the chunker would treat as silence.
        s = BLANK_RUN.replace(s, ". ")
        s = s.replace('\n', ' ')
        s = SPACE_RUN.replace(s, " ")
        return s.trim()
    }
}
