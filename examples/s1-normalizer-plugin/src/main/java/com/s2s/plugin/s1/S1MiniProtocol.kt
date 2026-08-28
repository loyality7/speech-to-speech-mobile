package com.s2s.plugin.s1

/**
 * The exact input format S1-mini was trained on, and validation of what it
 * returns.
 *
 * Every string here is transcribed from the model card, not paraphrased.
 * The card is explicit that the system prompt and the control line are
 * part of the trained input format — a "tidier" rewording is a different
 * input distribution than the one the model was fine-tuned on, and the
 * failure mode is quietly worse output rather than an error.
 *
 * Kept as pure functions with no Android or inference dependency so the
 * protocol can be tested exactly, without a model.
 */
internal object S1MiniProtocol {

    /**
     * The system prompt, verbatim from the model card. Do not reword.
     */
    const val SYSTEM_PROMPT: String =
        "You are a text normalizer for speech-to-text transcripts. The input begins with a " +
            "control line specifying the styling, structure, and context settings; clean the " +
            "transcript to match those settings and output only the cleaned text."

    /**
     * Builds the control line the transcript must be prefixed with:
     * `[Styling: <value>] [Structure: <value>] [Context: <value>]`
     *
     * Values are passed through lowercased and validated against the
     * documented sets — an unrecognised value falls back to the documented
     * default rather than being sent through, because an out-of-vocabulary
     * control value is exactly the kind of malformed input that produces
     * confidently wrong output.
     */
    fun controlLine(styling: String?, structure: String?, context: String?): String {
        val s = pick(styling, STYLING, DEFAULT_STYLING)
        val t = pick(structure, STRUCTURE, DEFAULT_STRUCTURE)
        val c = pick(context, CONTEXT, DEFAULT_CONTEXT)
        return "[Styling: $s] [Structure: $t] [Context: $c]"
    }

    /** The full user turn: control line, then the raw transcript on the next line. */
    fun userTurn(rawTranscript: String, styling: String?, structure: String?, context: String?): String =
        controlLine(styling, structure, context) + "\n" + rawTranscript.trim()

    private fun pick(value: String?, allowed: Set<String>, fallback: String): String {
        val normalized = value?.trim()?.lowercase()
        return if (normalized != null && normalized in allowed) normalized else fallback
    }

    /**
     * Whether the model's raw output is usable.
     *
     * Rejecting bad output matters more than it looks: this text goes on to
     * become the user's utterance for the whole turn, so a leaked control
     * line or chat marker would be treated as something the user said —
     * and could reach TTS. Rejection means the caller falls back to the raw
     * transcript, which is always a safe answer.
     *
     * Deliberately not aggressive about content: the model is *supposed* to
     * rewrite text, so anything that merely looks different from the input
     * is fine. Only structural contamination and implausible length are
     * rejected.
     */
    fun isValidOutput(output: String, rawTranscript: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false

        // Protocol contamination: the model echoed part of its own input format.
        if (text.contains("[Styling:", ignoreCase = true)) return false
        if (text.contains("[Structure:", ignoreCase = true)) return false
        if (text.contains("[Context:", ignoreCase = true)) return false
        if (text.contains("text normalizer for speech-to-text", ignoreCase = true)) return false

        // Chat-template markers. Qwen3's template is what this model ships
        // with, so a stray <|im_start|> or <think> is a real possibility.
        if (CHAT_MARKERS.any { text.contains(it, ignoreCase = true) }) return false

        // A tool-call-shaped reply is not normalized speech, and must never
        // be forwarded as if the user had said it.
        if (text.startsWith("{") && text.contains("\"tool\"")) return false

        // Length sanity. Normalization removes filler and tightens phrasing,
        // so shrinking is expected; ballooning is not, and a tiny output for
        // a long input means something was dropped.
        val rawLength = rawTranscript.trim().length
        if (rawLength > 0) {
            if (text.length > rawLength * MAX_GROWTH_FACTOR + MAX_GROWTH_SLACK) return false
            if (rawLength >= MIN_LENGTH_FOR_SHRINK_CHECK && text.length < rawLength * MIN_SHRINK_FACTOR) return false
        }
        return true
    }

    /**
     * Strips anything the model may have wrapped its answer in.
     *
     * Applied before validation: the model card notes the assistant turn
     * begins with an empty `<think>` block, and depending on how a runtime
     * applies the template that block can appear in the decoded text
     * rather than being consumed. Removing it is correct; leaving it would
     * fail validation and needlessly discard a good normalization.
     */
    fun cleanOutput(raw: String): String {
        var text = raw.trim()
        text = THINK_BLOCK.replace(text, "").trim()
        CHAT_MARKERS.forEach { marker -> text = text.replace(marker, "").trim() }
        // Some builds prefix a stray newline or quote around the answer.
        return text.trim().trim('"').trim()
    }

    val STYLING = setOf("casual", "semi-casual", "semi-formal", "formal")
    val STRUCTURE = setOf("prose", "lists")
    val CONTEXT = setOf("general", "email")

    /** Model-card defaults for anything the host did not specify. */
    const val DEFAULT_STYLING = "semi-casual"
    const val DEFAULT_STRUCTURE = "prose"
    const val DEFAULT_CONTEXT = "general"

    private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val CHAT_MARKERS = listOf("<|im_start|>", "<|im_end|>", "<think>", "</think>", "<|endoftext|>")

    private const val MAX_GROWTH_FACTOR = 2.0
    private const val MAX_GROWTH_SLACK = 40
    private const val MIN_SHRINK_FACTOR = 0.25
    private const val MIN_LENGTH_FOR_SHRINK_CHECK = 40
}
