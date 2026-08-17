package com.s2s.mobile.model

import org.json.JSONObject

/**
 * Registry of on-device model specifications loaded from `models_registry.json`.
 * No model URLs or metadata are hardcoded in Kotlin.
 */
object ModelRegistry {

    val ALL_MODELS: List<ModelSpec> get() = parsed.models
    val DEFAULT_STACK: List<ModelSpec> get() = parsed.defaultStack

    val ALL_VAD_OPTIONS: List<ModelSpec> get() = ALL_MODELS.filter { it.category == "VAD" }
    val ALL_STT_OPTIONS: List<ModelSpec> get() = ALL_MODELS.filter { it.category == "STT" }
    val ALL_TTS_OPTIONS: List<ModelSpec> get() = ALL_MODELS.filter { it.category == "TTS" }
    val ALL_LLM_OPTIONS: List<ModelSpec> get() = ALL_MODELS.filter { it.category == "LLM" }

    val DEFAULT_VAD: ModelSpec get() = defaultOf("VAD")
    val DEFAULT_STT: ModelSpec get() = defaultOf("STT")
    val DEFAULT_TTS: ModelSpec get() = defaultOf("TTS")
    val DEFAULT_LLM: ModelSpec get() = defaultOf("LLM")

    /**
     * Filters registered models by a search query string across name, id, category, or backend.
     */
    fun searchModels(query: String): List<ModelSpec> {
        if (query.isBlank()) return ALL_MODELS
        val q = query.trim().lowercase()
        return ALL_MODELS.filter {
            it.name.lowercase().contains(q) ||
                it.id.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (it.backend != null && it.backend.lowercase().contains(q))
        }
    }

    private fun defaultOf(category: String): ModelSpec =
        DEFAULT_STACK.firstOrNull { it.category == category }
            ?: error("models_registry.json default_stack names no $category model")

    private val parsed: Registry by lazy {
        val text = ModelRegistry::class.java.classLoader
            ?.getResourceAsStream("models_registry.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("models_registry.json missing from resources")
        load(text)
    }

    private class Registry(val models: List<ModelSpec>, val defaultStack: List<ModelSpec>)

    /** Visible for tests. */
    fun loadFromJson(jsonString: String): List<ModelSpec> = load(jsonString).models

    private fun load(jsonString: String): Registry {
        val root = JSONObject(jsonString)
        val array = root.getJSONArray("models")
        val models = (0 until array.length()).map { spec(array.getJSONObject(it)) }
        val byId = models.associateBy { it.id }

        // An id in default_stack that matches nothing is a typo in the registry, and
        // silently falling back to list order is exactly the bug this replaces.
        val ids = root.getJSONArray("default_stack")
        val stack = (0 until ids.length()).map { i ->
            val id = ids.getString(i)
            byId[id] ?: error("default_stack references unknown model id '$id'")
        }
        return Registry(models, stack)
    }

    private fun spec(o: JSONObject) = ModelSpec(
        id = o.getString("id"),
        category = o.getString("category"),
        name = o.optString("name", o.getString("id")),
        url = o.getString("url"),
        targetPath = o.getString("targetPath"),
        archive = o.optBoolean("archive", false),
        approxBytes = o.optLong("approxBytes", 0L),
        sha256 = o.optStringOrNull("sha256"),
        version = o.optString("version", "1.0"),
        backend = o.optStringOrNull("backend"),
        numThreads = o.optIntOrNull("numThreads"),
        firstChunkMinChars = o.optIntOrNull("firstChunkMinChars"),
        maxChunkChars = o.optIntOrNull("maxChunkChars"),
        minChunkChars = o.optIntOrNull("minChunkChars"),
        speed = o.optDoubleOrNull("speed")?.toFloat(),
        decodingMethod = o.optStringOrNull("decodingMethod"),
        endpointTrailingSilence = o.optDoubleOrNull("endpointTrailingSilence")?.toFloat(),
        batchSize = o.optIntOrNull("batchSize"),
        maxTokens = o.optIntOrNull("maxTokens"),
    )

    // org.json's opt* overloads return 0 / "" for an absent key, which is a real
    // value here — an absent numThreads must stay null so the config layer's own
    // default wins rather than being overridden with 0.
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null
}
