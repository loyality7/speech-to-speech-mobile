package com.s2s.mobile.model

import org.json.JSONObject

/**
 * Where a [ModelSpec] came from. HUGGING_FACE specs are resolved dynamically at
 * runtime and may lack a pre-known [ModelSpec.sha256] — see [ModelDownloader] for
 * how integrity is verified in that case (Content-Length only, no silent downgrade
 * of a checksum that WAS known).
 */
enum class ModelSource {
    LOCAL,
    HUGGING_FACE,
}

/**
 * Metadata specification for a downloadable speech-to-speech model bundle,
 * including optional stage-specific tuning parameters loaded dynamically from JSON.
 */
data class ModelSpec(
    val id: String = "",
    val category: String = "",
    val name: String,
    val url: String,
    val source: ModelSource = ModelSource.LOCAL,
    /** Relative target path (file name or directory name when [archive] is true). */
    val targetPath: String,
    val archive: Boolean,
    /**
     * Non-empty only for a HUGGING_FACE spec assembled from several individually
     * fetched files (e.g. a TTS voice's .onnx plus its tokens.txt — sherpa-onnx
     * needs both in one directory and neither alone is enough to initialize).
     * Keys are the plain filename to write inside [targetPath] (which is treated
     * as a directory in this mode, same as [archive]); values are download URLs.
     * When non-empty this takes over from [archive]/[url] entirely — see
     * ModelDownloader.downloadSpec.
     */
    val multiFileUrls: Map<String, String> = emptyMap(),
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
) {
    /**
     * Serializes a dynamically-resolved (HUGGING_FACE) spec so a host app can persist
     * the user's pick across process death — see ModelRegistry for the equivalent
     * curated-JSON shape this deliberately mirrors field-for-field.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category)
        put("name", name)
        put("url", url)
        put("source", source.name)
        put("targetPath", targetPath)
        put("archive", archive)
        if (multiFileUrls.isNotEmpty()) {
            put("multiFileUrls", JSONObject().apply { multiFileUrls.forEach { (k, v) -> put(k, v) } })
        }
        put("approxBytes", approxBytes)
        sha256?.let { put("sha256", it) }
        put("version", version)
        backend?.let { put("backend", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ModelSpec {
            val multiFileUrls = o.optJSONObject("multiFileUrls")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap()
            return ModelSpec(
                id = o.getString("id"),
                category = o.getString("category"),
                name = o.getString("name"),
                url = o.getString("url"),
                source = ModelSource.valueOf(o.optString("source", "LOCAL")),
                targetPath = o.getString("targetPath"),
                archive = o.optBoolean("archive", false),
                multiFileUrls = multiFileUrls,
                approxBytes = o.optLong("approxBytes", 0L),
                sha256 = if (o.isNull("sha256")) null else o.optString("sha256").ifBlank { null },
                version = o.optString("version", "1.0"),
                backend = if (o.isNull("backend")) null else o.optString("backend").ifBlank { null },
            )
        }
    }
}

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
