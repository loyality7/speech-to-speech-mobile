package com.s2s.mobile.llm

/**
 * Self-hosted OpenAI-compat-server settings for [RemoteLanguageModel].
 *
 * Lives beside the backend it configures, not in `config/` — see [LlamaConfig]
 * for why. Slated to move into the `s2s-llm` plugin repo.
 */
data class RemoteLlmConfig(
    /** Self-hosted server's chat-completions base URL, e.g. "http://192.168.1.10:8000/v1". No default — must point at the app owner's own infra. */
    val baseUrl: String,
    /** Optional bearer token. A self-hosted server may not require auth. */
    val apiKey: String? = null,
    /** Model name as the server's `/v1/models` (or its own config) knows it. */
    val remoteModelName: String? = null,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 256,
    val stopSequences: List<String> = emptyList(),
)
