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
