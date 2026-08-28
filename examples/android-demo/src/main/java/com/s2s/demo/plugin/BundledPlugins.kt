package com.s2s.demo.plugin

import android.content.Context
import com.s2s.context.local.SqliteContextEngine
import com.s2s.host.core.PluginAvailability
import com.s2s.host.core.PluginConfigField
import com.s2s.host.core.PluginDescriptor
import com.s2s.host.core.PluginManager
import com.s2s.host.core.PluginProvider
import com.s2s.host.core.PluginSource
import com.s2s.host.core.PluginType
import com.s2s.llm.local.LlamaConfig
import com.s2s.llm.local.LlamaLanguageModel
import com.s2s.llm.remote.RemoteLanguageModel
import com.s2s.llm.remote.RemoteLlmConfig
import com.s2s.mobile.pipeline.ContextEngine
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.Tools
import com.s2s.tools.core.CalculatorTool
import com.s2s.tools.core.ToolRegistry
import java.util.UUID

/**
 * The plugins compiled into this APK.
 *
 * This is the ONLY file in the app that names a concrete provider
 * (`LlamaLanguageModel`, `SqliteContextEngine`, `ToolRegistry`). That is
 * deliberate and allowed: these are [PluginSource.BUNDLED] plugins — they
 * ship inside Jarvis, so something has to construct them, and pretending
 * otherwise would be a fiction. What matters is that
 * [com.s2s.demo.JarvisRuntime], [com.s2s.host.core.HostComposer],
 * `AgentRuntime` and `S2SEngine` know none of these names.
 *
 * An externally-installed plugin never appears here — it arrives through
 * [AndroidPluginDiscovery] and is registered by [PluginManager] at runtime.
 *
 * Bootstrap policy: bundled plugins are enabled by default because they are
 * first-party code shipped in this APK and the app is useless without an
 * LLM and a context engine. That auto-enable is explicitly NOT extended to
 * discovered third-party plugins, which stay disabled until the user
 * installs and enables them.
 */
object BundledPlugins {
    const val LLAMA_CPP = "llama-cpp"
    const val REMOTE_LLM = "remote"
    const val SQLITE_CONTEXT = "sqlite-context"
    const val CORE_TOOLS = "core-tools"

    const val DEFAULT_SYSTEM_PROMPT =
        "You are Jarvis, a voice assistant. Keep answers short and " +
            "conversational — one or two sentences unless the user asks " +
            "for detail. When a registered tool can answer the request " +
            "(for example, a calculation), call it instead of solving it " +
            "yourself. Never explain your reasoning step by step unless " +
            "asked to."

    fun registerAll(manager: PluginManager, context: Context) {
        val app = context.applicationContext

        manager.registerBundled(
            PluginDescriptor(
                pluginId = LLAMA_CPP,
                type = PluginType.LANGUAGE_MODEL,
                displayName = "Llama.cpp (on-device)",
                version = "0.3.1",
                source = PluginSource.BUNDLED,
                availability = PluginAvailability.BUNDLED,
                description = "Runs a GGUF model locally on this device. No network, no data leaves the phone.",
                configSchema = listOf(
                    PluginConfigField("modelPath", "Model file", PluginConfigField.Type.FILE_PATH, help = "Path to a .gguf model on this device."),
                ),
            ),
            PluginProvider<LanguageModel> { config ->
                val modelPath = config["modelPath"] ?: error("llama-cpp plugin requires a 'modelPath' config value")
                LlamaLanguageModel(LlamaConfig(), modelPath)
            },
        )

        manager.registerBundled(
            PluginDescriptor(
                pluginId = REMOTE_LLM,
                type = PluginType.LANGUAGE_MODEL,
                displayName = "Remote (OpenAI-compatible)",
                version = "0.3.1",
                source = PluginSource.BUNDLED,
                availability = PluginAvailability.BUNDLED,
                description = "Sends prompts to an OpenAI-compatible HTTP endpoint you host or subscribe to.",
                configSchema = listOf(
                    PluginConfigField("baseUrl", "Server URL", help = "e.g. https://my-server/v1"),
                    PluginConfigField("apiKey", "API key", PluginConfigField.Type.SECRET, required = false),
                ),
            ),
            PluginProvider<LanguageModel> { config ->
                val baseUrl = config["baseUrl"] ?: error("remote plugin requires a 'baseUrl' config value")
                RemoteLanguageModel(RemoteLlmConfig(baseUrl = baseUrl, apiKey = config["apiKey"]))
            },
        )

        manager.registerBundled(
            PluginDescriptor(
                pluginId = SQLITE_CONTEXT,
                type = PluginType.CONTEXT_ENGINE,
                displayName = "SQLite Memory",
                version = "0.1.1",
                source = PluginSource.BUNDLED,
                availability = PluginAvailability.BUNDLED,
                description = "Stores the conversation transcript and long-term memory on-device in SQLite.",
            ),
            PluginProvider<ContextEngine> { config ->
                val sessionId = config["sessionId"] ?: UUID.randomUUID().toString()
                val systemPrompt = config["systemPrompt"] ?: DEFAULT_SYSTEM_PROMPT
                SqliteContextEngine(app, sessionId, systemPrompt)
            },
        )

        manager.registerBundled(
            PluginDescriptor(
                pluginId = CORE_TOOLS,
                type = PluginType.TOOLS,
                displayName = "Core Tools",
                version = "0.1.0",
                source = PluginSource.BUNDLED,
                availability = PluginAvailability.BUNDLED,
                description = "Built-in tools. Currently a calculator.",
            ),
            PluginProvider<Tools> { ToolRegistry().also { CalculatorTool.registerOn(it) } },
        )

        listOf(LLAMA_CPP, REMOTE_LLM, SQLITE_CONTEXT, CORE_TOOLS).forEach { manager.enable(it) }
    }
}
