package com.example.s2splugin

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.ContextEngine

/** Plugin-owned configuration. Never touches `S2SConfig` — see the README. */
data class ExampleContextConfig(
    val systemPrompt: String = "You are a helpful assistant.",
    val maxTurns: Int = 20,
)

/**
 * Reference implementation: a [ContextEngine] plugin.
 *
 * Deliberately trivial — a fixed-size list, no compaction, no persistence
 * beyond [toJson]/[fromJson]. Copy this file's shape (config + capability
 * implementation + a factory/companion for construction), not its behavior,
 * when building a real memory backend (SQLite, a vector store, a remote
 * memory service).
 */
class ExampleContextPlugin(private val config: ExampleContextConfig) : ContextEngine, S2SPlugin {

    override val id: String = "example-context-plugin"
    override val version: String = "0.1.0"
    override val capabilities: List<String> = listOf("ContextEngine")

    private val turns = ArrayDeque<ChatMessage>()
    private var system = config.systemPrompt

    override fun addUser(text: String) = append(ChatMessage("user", text))

    override fun replaceLastUser(text: String) {
        val index = turns.indexOfLast { it.role == "user" }
        if (index >= 0) turns[index] = ChatMessage("user", text) else addUser(text)
    }

    override fun addAssistant(text: String) = append(ChatMessage("assistant", text))

    override fun dropLastUserIfUnanswered() {
        if (turns.lastOrNull()?.role == "user") turns.removeLast()
    }

    override fun addToolResult(name: String, output: String) =
        append(ChatMessage("user", "[tool $name returned] $output"))

    private fun append(message: ChatMessage) {
        turns.addLast(message)
        while (turns.size > config.maxTurns) turns.removeFirst()
    }

    override fun messages(extraSystem: String?): List<ChatMessage> = buildList {
        add(ChatMessage("system", listOfNotNull(system, extraSystem).joinToString("\n\n")))
        addAll(turns)
    }

    override fun setSystemPrompt(prompt: String) {
        system = prompt
    }

    override fun clear() = turns.clear()

    override fun toJson(): String =
        turns.joinToString("|") { "${it.role}:${it.content}" }

    override fun fromJson(json: String) {
        turns.clear()
        if (json.isBlank()) return
        json.split("|").forEach { segment ->
            val parts = segment.split(":", limit = 2)
            if (parts.size == 2) turns.addLast(ChatMessage(parts[0], parts[1]))
        }
    }
}
