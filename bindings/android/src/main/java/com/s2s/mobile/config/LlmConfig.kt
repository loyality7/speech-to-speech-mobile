package com.s2s.mobile.config

/**
 * Text generation, plus the rolling-memory policy that keeps a long
 * conversation from growing the KV cache without bound.
 */
data class LlmConfig(
    /**
     * Kept deliberately short. Prefill is re-run over the whole prompt every
     * turn, measured at ~5.8 ms per character on a mid-range device, so every
     * 100 characters here costs about 0.6 s before the assistant speaks.
     */
    val systemPrompt: String = "Talk Freely, but don't be rude. You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 256,
    val contextLength: Int = 2048,
    /**
     * Generation threads.
     *
     * Raised to 6 on the reasoning that the SoC has 8 cores, and reverted: TTS
     * synthesises concurrently on 4 more, and oversubscribing pushed mean TTS
     * real-time factor from 0.49 to 0.62 with long chunks starting to exceed
     * real time. The pipeline is only as fast as the stage that falls behind.
     */
    val numThreads: Int = 4,
    val batchSize: Int = 512,
    /** Layers offloaded to GPU. 0 keeps everything on CPU, which is safest. */
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val flashAttention: Boolean = false,
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
    /**
     * Keep llama.cpp's KV cache between turns so each turn prefills only the new
     * user text. Without it, prefill is re-run over the whole conversation every
     * turn and response time grows linearly with chat length.
     */
    val reuseKvCache: Boolean = true,
    /** Enable tool calling. Adds a tool description block to the system prompt. */
    val toolsEnabled: Boolean = false,
)
