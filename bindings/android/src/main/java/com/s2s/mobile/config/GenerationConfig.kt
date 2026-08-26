package com.s2s.mobile.config

/**
 * Orchestration-level generation policy — the rolling-memory and tool-calling
 * behavior [S2SEngine] itself understands, independent of which [com.s2s.mobile.pipeline.LanguageModel]
 * the host has wired in.
 *
 * Backend-specific settings (sampling params, model path, remote endpoint, etc.)
 * live with the [com.s2s.mobile.pipeline.LanguageModel] implementation instead —
 * core has no business knowing what a temperature or a base URL means to a
 * backend it never constructs.
 */
data class GenerationConfig(
    /**
     * Kept deliberately short. Prefill is re-run over the whole prompt every
     * turn, measured at ~5.8 ms per character on a mid-range device, so every
     * 100 characters here costs about 0.6 s before the assistant speaks.
     */
    val systemPrompt: String = "Talk Freely, but don't be rude. You are a helpful assistant.",
    /**
     * Full turns kept verbatim before compaction kicks in.
     *
     * Every kept turn is re-prefilled on the next one, so this trades memory of
     * the conversation directly against time-to-first-token.
     */
    val historyTurns: Int = 3,
    /**
     * Summarise turns that fall out of the window instead of dropping them, so
     * the assistant still remembers what was agreed earlier in a long session.
     */
    val compactHistory: Boolean = true,
    /** Enable tool calling. Adds a tool description block to the system prompt. */
    val toolsEnabled: Boolean = false,
)
