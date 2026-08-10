package com.s2s.mobile.config

/**
 * Text generation, plus the rolling-memory policy that keeps a long
 * conversation from growing the KV cache without bound.
 */
data class LlmConfig(
    val systemPrompt: String =
        "You are a helpful voice assistant running locally on a phone. " +
            "Keep answers short, clear and conversational — 1 to 3 sentences.",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 256,
    val contextLength: Int = 2048,
    val numThreads: Int = 4,
    val batchSize: Int = 512,
    /** Layers offloaded to GPU. 0 keeps everything on CPU, which is safest. */
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val flashAttention: Boolean = false,
    /** Full turns kept verbatim before compaction kicks in. */
    val historyTurns: Int = 6,
    /**
     * Summarise turns that fall out of the window instead of dropping them, so
     * the assistant still remembers what was agreed earlier in a long session.
     */
    val compactHistory: Boolean = true,
    /** Enable tool calling. Adds a tool description block to the system prompt. */
    val toolsEnabled: Boolean = false,
)
