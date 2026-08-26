package com.s2s.mobile.llm

/**
 * llama.cpp-backend-specific settings for [LlamaLanguageModel].
 *
 * Lives beside the backend it configures, not in `config/`, because core's
 * orchestration has no use for a temperature or a GPU layer count — only this
 * backend does. Slated to move into the `s2s-llm` plugin repo; kept here for
 * now so the split into stable interfaces + call-site wiring lands first.
 */
data class LlamaConfig(
    /** Sampling temperature. Lower is more deterministic/repetitive, higher is more varied/erratic. */
    val temperature: Float = 0.7f,
    /** Nucleus sampling cutoff — only tokens within this cumulative probability mass are considered. */
    val topP: Float = 0.95f,
    /** Only the top-K most likely tokens are considered before top-P is applied. */
    val topK: Int = 40,
    /** Multiplies down the probability of tokens already used recently. 1.0 disables it. */
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 256,
    /**
     * Strings that end generation early when produced, checked against the
     * running output. Empty disables the check. Useful for models that don't
     * reliably emit their own end-of-turn token.
     */
    val stopSequences: List<String> = emptyList(),
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
     * Keep llama.cpp's KV cache between turns so each turn prefills only the new
     * user text. Without it, prefill is re-run over the whole conversation every
     * turn and response time grows linearly with chat length.
     */
    val reuseKvCache: Boolean = true,
)
