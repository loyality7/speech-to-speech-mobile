package com.s2s.mobile.pipeline

/**
 * Where conversation memory lives and how it is shaped into a prompt.
 *
 * [S2SEngine][com.s2s.mobile.S2SEngine] asks this for the messages a turn should
 * see rather than owning storage itself — core has no implementation of its own
 * (the constructor's `history` parameter is required, not defaulted). A plugin
 * (e.g. `s2s-context`'s SQLite-backed implementation) can be swapped for another
 * one (a vector store, a remote memory service) without the engine changing.
 *
 * Deliberately narrow: turn-taking (add/replace/drop) and prompt assembly
 * (messages), nothing about retrieval strategy, embeddings, or persistence
 * format — those are an implementation's own business. Thread-safe
 * implementations are required: the mic thread and the LLM thread both call in.
 */
interface ContextEngine {

    /** Appends a user turn. */
    fun addUser(text: String)

    /**
     * Replaces the most recent user message.
     *
     * Used when the user pauses mid-thought and carries on: the turn is restarted
     * with the combined text, and the half-finished version must not be left
     * behind as a separate message the model would answer twice.
     */
    fun replaceLastUser(text: String)

    /** Appends an assistant turn. */
    fun addAssistant(text: String)

    /**
     * Removes the last turn if it was an unanswered user message.
     * Called when a turn is interrupted before any assistant text was generated.
     */
    fun dropLastUserIfUnanswered()

    /** Records a tool result so the model can speak about what it just did. */
    fun addToolResult(name: String, output: String)

    /**
     * The messages a turn's [LanguageModel.generate] call should see —
     * system prompt, whatever this implementation surfaces as relevant memory,
     * then the turns it decides to include verbatim.
     *
     * [extraSystem] is appended to the system message for that call only (e.g.
     * a tool-calling instruction block) — not persisted as part of memory.
     *
     * Called on the engine's LLM worker thread and blocks until it returns —
     * same contract as [Tools.execute]. An implementation doing remote
     * retrieval (a vector store, a memory service) should keep this fast or
     * enforce its own timeout; there is no cancellation signal today.
     */
    fun messages(extraSystem: String? = null): List<ChatMessage>

    fun setSystemPrompt(prompt: String)

    /** Clears all memory. Does not affect the system prompt. */
    fun clear()

    /** Serializes state for process-death persistence. */
    fun toJson(): String

    /** Restores state serialized by [toJson]. */
    fun fromJson(json: String)

    /**
     * Releases any resource this implementation holds open (a database
     * connection, a file handle, a network client) — the counterpart to
     * whatever setup an implementation's constructor does. A stateless
     * implementation (in-memory only) needs no override; the default is a
     * no-op so existing implementations compile unchanged.
     *
     * Called by the host when the runtime using this instance stops (see
     * the Android demo's runtime teardown) — never by [com.s2s.agent.agent.AgentRuntime]
     * itself, which has no opinion on resource lifecycle beyond calling
     * whatever the host wires it to.
     */
    fun close() {}
}
