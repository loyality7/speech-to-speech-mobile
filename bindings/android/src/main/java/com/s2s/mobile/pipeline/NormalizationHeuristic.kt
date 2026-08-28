package com.s2s.mobile.pipeline

/**
 * Decides whether a transcript is worth sending to a [TextNormalizer]
 * under [TextNormalizationPolicy.AUTO].
 *
 * Pure string inspection — no model, no allocation beyond a token split.
 * The whole point is that this costs microseconds so that transcripts
 * which would not benefit skip a model call costing hundreds of
 * milliseconds. "call John Smith" is already what the user meant; "um so
 * like send the the report friday no wait thursday" is not.
 *
 * Biased toward *skipping*. A false skip costs a slightly rough
 * transcript, which the LLM downstream usually handles fine. A false
 * normalize costs latency on every single turn, which is what makes a
 * voice assistant feel broken. When unsure, don't spend the time.
 */
object NormalizationHeuristic {

    /** True when [transcript] shows signs a normalizer could meaningfully improve. */
    fun benefitsFromNormalization(transcript: String): Boolean {
        val text = transcript.trim()
        if (text.length < MIN_LENGTH) return false

        val lower = text.lowercase()
        val words = lower.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return false

        // Any one of these is enough: each is something a normalizer
        // demonstrably fixes and an ASR transcript demonstrably contains.
        if (words.any { it in FILLERS }) return true
        if (hasRepeatedWord(words)) return true
        if (SELF_CORRECTION.containsMatchIn(lower)) return true
        if (words.count { it in SPELLED_NUMBERS } >= 2) return true
        if (SPOKEN_PUNCTUATION.containsMatchIn(lower)) return true

        // Long and completely unpunctuated: ASR gave us a wall of words,
        // which is exactly the case sentence-splitting helps.
        if (words.size >= LONG_UTTERANCE_WORDS && text.none { it in ".,?!;:" }) return true

        return false
    }

    /** "send the the report" — ASR duplication, distinct from legitimate repetition like "very very". */
    private fun hasRepeatedWord(words: List<String>): Boolean =
        words.zipWithNext().any { (a, b) -> a == b && a.length > 2 && a !in LEGITIMATE_REPEATS }

    private const val MIN_LENGTH = 12
    private const val MIN_WORDS = 4
    private const val LONG_UTTERANCE_WORDS = 14

    private val FILLERS = setOf("um", "uh", "erm", "uhh", "umm", "hmm", "er", "ah")

    /** "no wait", "actually", "i mean" — the speaker revised themselves mid-sentence. */
    private val SELF_CORRECTION = Regex(
        "\\b(no wait|no actually|wait no|i mean|scratch that|sorry i meant|make that|or rather)\\b",
    )

    /** Two or more of these suggests a spoken figure, date or amount worth rendering numerically. */
    private val SPELLED_NUMBERS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion",
    )

    /** Dictated punctuation/symbols the user spoke aloud rather than typed. */
    private val SPOKEN_PUNCTUATION = Regex(
        "\\b(full stop|new paragraph|new line|comma|question mark|exclamation mark|at sign|dot com)\\b",
    )

    /** Words where an immediate repeat is normal English, not an ASR artefact. */
    private val LEGITIMATE_REPEATS = setOf("very", "really", "no", "yes", "so")
}
