package com.s2s.mobile.llm

import com.s2s.mobile.pipeline.ChatMessage

/**
 * Multi-turn conversation memory with rolling compaction. Port of the Python
 * `LLM/chat.py` plus the behaviour behind `LLM/compaction_prompt.py`.
 *
 * Without compaction a long session grows the prompt until the KV cache eats the
 * RAM budget and every turn gets slower. Turns that fall out of the verbatim
 * window are folded into a running summary rather than dropped, so the assistant
 * still remembers what was agreed earlier in the session.
 *
 * Thread-safe: the mic thread reads while the LLM thread appends.
 */
class ChatHistory(
    systemPrompt: String,
    private val keepTurns: Int = 6,
    private val compact: Boolean = true,
    /**
     * Condenses turns that fall out of the window. Defaults to a cheap textual
     * fold; supply an LLM-backed summariser for better recall. Only called when
     * the window overflows, never on the hot path.
     */
    private val summarizer: (List<ChatMessage>) -> String = ::foldToSummary,
) {

    private val turns = ArrayDeque<ChatMessage>()
    private var summary: String? = null
    private var system: String = systemPrompt
    private val lock = Any()

    fun addUser(text: String) = append(ChatMessage("user", text))

    fun addAssistant(text: String) = append(ChatMessage("assistant", text))

    /** Records a tool result so the model can speak about what it just did. */
    fun addToolResult(name: String, output: String) =
        append(ChatMessage("user", "[tool $name returned] $output"))

    private fun append(message: ChatMessage) {
        synchronized(lock) {
            turns.addLast(message)
            val max = keepTurns * 2
            if (turns.size <= max) return

            val overflow = mutableListOf<ChatMessage>()
            while (turns.size > max) overflow += turns.removeFirst()
            if (!compact) return

            val condensed = runCatching { summarizer(overflow) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return
            summary = summary?.let { "$it $condensed" } ?: condensed
        }
    }

    /** Full prompt: system message, rolling summary, then the verbatim turns. */
    fun messages(extraSystem: String? = null): List<ChatMessage> = synchronized(lock) {
        buildList {
            val head = listOfNotNull(system, extraSystem).joinToString("\n\n")
            add(ChatMessage("system", head))
            summary?.let { add(ChatMessage("system", "Earlier in this conversation: $it")) }
            addAll(turns)
        }
    }

    fun setSystemPrompt(prompt: String) = synchronized(lock) { system = prompt }

    fun clear() = synchronized(lock) {
        turns.clear()
        summary = null
    }

    companion object {
        /**
         * Free fallback summariser: keeps the first clause of each dropped turn.
         * Loses nuance, costs nothing, and never blocks a turn.
         */
        fun foldToSummary(dropped: List<ChatMessage>): String =
            dropped.joinToString(" ") { m ->
                val gist = m.content.trim().take(80).substringBefore('.')
                if (m.role == "user") "User asked about $gist." else "Assistant said $gist."
            }
    }
}
