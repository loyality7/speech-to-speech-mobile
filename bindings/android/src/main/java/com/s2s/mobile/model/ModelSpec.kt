package com.s2s.mobile.model

/**
 * Metadata specification for a downloadable speech-to-speech model bundle,
 * including optional stage-specific tuning parameters loaded dynamically from JSON.
 */
data class ModelSpec(
    val id: String = "",
    val category: String = "",
    val name: String,
    val url: String,
    /** Relative target path (file name or directory name when [archive] is true). */
    val targetPath: String,
    val archive: Boolean,
    val approxBytes: Long,
    val sha256: String? = null,
    val version: String = "1.0",
    val backend: String? = null,
    val numThreads: Int? = null,
    val firstChunkMinChars: Int? = null,
    val maxChunkChars: Int? = null,
    val minChunkChars: Int? = null,
    val speed: Float? = null,
    val decodingMethod: String? = null,
    val endpointTrailingSilence: Float? = null,
    /** LLM only — a bigger model needs a smaller prefill batch and reply cap to stay responsive. */
    val batchSize: Int? = null,
    val maxTokens: Int? = null,
)

/**
 * Progress status for a model download or extraction task.
 */
data class ModelProgress(
    val modelName: String,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Status,
) {
    enum class Status {
        PRECHECK,
        DOWNLOADING,
        EXTRACTING,
        VERIFYING,
        COMPLETED,
        FAILED
    }
}

/**
 * On-disk status and disk accounting info for a model specification.
 */
data class InstalledModelInfo(
    val spec: ModelSpec,
    val isInstalled: Boolean,
    val diskUsageBytes: Long,
    val targetFile: java.io.File,
)
