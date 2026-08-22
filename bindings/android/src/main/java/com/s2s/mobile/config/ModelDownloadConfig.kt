package com.s2s.mobile.config

/**
 * Model download/registry behavior. All of it is overridable so a consuming
 * app can point at its own CDN, its own auth scheme, and its own timeouts
 * without forking the SDK.
 */
data class ModelDownloadConfig(
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 120_000,
    val maxRedirects: Int = 5,
    /** I/O buffer size for download/extraction, in bytes. */
    val bufferSizeBytes: Int = 1 shl 16,
    val userAgent: String = "S2S-Mobile-SDK/1.1",
    /**
     * Hosts (exact or `.`-suffix match) that receive the Hugging Face bearer
     * token. A redirect landing outside this list never sees the token —
     * widen it only to add your own mirror/proxy in front of Hugging Face.
     */
    val huggingFaceTokenHosts: List<String> = listOf("huggingface.co"),
    /** Directory name under the app's external files dir where models are stored. */
    val modelsDirName: String = "models",
    val notificationChannelId: String = "s2s_model_download_channel",
    val notificationChannelName: String = "Model Downloads",
    val notificationChannelDescription: String = "Shows progress while model files are downloading",
    val notificationId: Int = 1001,
    val notificationIconRes: Int = android.R.drawable.stat_sys_download,
)
