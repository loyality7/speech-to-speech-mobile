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
) {

    private val turns = ArrayDeque<ChatMessage>()
    private var summary: String? = null
    private var system: String = systemPrompt
    private val lock = Any()

    fun addUser(text: String) = append(ChatMessage("user", text))

    /**
     * Replaces the most recent user message.
     *
     * Used when the user pauses mid-thought and carries on: the turn is restarted
     * with the combined text, and the half-finished version must not be left
     * behind as a separate message the model would answer twice.
     */
    fun replaceLastUser(text: String) = synchronized(lock) {
        val index = turns.indexOfLast { it.role == "user" }
        if (index >= 0) turns[index] = ChatMessage("user", text) else turns.addLast(ChatMessage("user", text))
    }

    fun addAssistant(text: String) = append(ChatMessage("assistant", text))

    /**
     * Removes the last turn if it was an unanswered user message.
     * Called when a turn is interrupted before any assistant text was generated.
     */
    fun dropLastUserIfUnanswered() = synchronized(lock) {
        if (turns.lastOrNull()?.role == "user") {
            turns.removeLast()
        }
    }

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

            val condensed = foldToSummary(overflow).takeIf { it.isNotBlank() } ?: return
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

    /** Serializes state to JSON for process death persistence. */
    fun toJson(): String = synchronized(lock) {
        val root = org.json.JSONObject()
        root.put("system", system)
        summary?.let { root.put("summary", it) }
        val turnsArray = org.json.JSONArray()
        turns.forEach { msg ->
            val obj = org.json.JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            turnsArray.put(obj)
        }
        root.put("turns", turnsArray)
        root.toString()
    }

    /** Restores conversation state from serialized JSON. */
    fun fromJson(json: String) = synchronized(lock) {
        val root = org.json.JSONObject(json)
        if (root.has("system")) system = root.getString("system")
        summary = if (root.has("summary") && !root.isNull("summary")) root.getString("summary") else null
        turns.clear()
        if (root.has("turns")) {
            val array = root.getJSONArray("turns")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                turns.addLast(ChatMessage(obj.getString("role"), obj.getString("content")))
            }
        }
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
