package com.s2s.demo

import android.content.Context
import com.s2s.agent.agent.AgentRuntime
import com.s2s.agent.task.InMemoryTaskStore
import com.s2s.context.local.SqliteContextEngine
import com.s2s.host.core.HostComposer
import com.s2s.host.core.PluginConfig
import com.s2s.host.core.PluginDescriptor
import com.s2s.host.core.PluginProvider
import com.s2s.host.core.PluginRegistry
import com.s2s.host.core.PluginType
import com.s2s.host.core.SharedPreferencesPluginConfigStore
import com.s2s.llm.local.LlamaConfig
import com.s2s.llm.local.LlamaLanguageModel
import com.s2s.llm.remote.RemoteLanguageModel
import com.s2s.llm.remote.RemoteLlmConfig
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.pipeline.ContextEngine
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.Tools
import com.s2s.tools.core.CalculatorTool
import com.s2s.tools.core.ToolRegistry

/**
 * The demo app's composition root: registers this app's bundled plugins once
 * and exposes a [PluginRegistry] for [MainActivity] to enable/select/compose
 * from. This is what [PluginProvider]/[PluginRegistry]/[com.s2s.host.core.HostComposer]
 * exist to replace — the direct `LlamaLanguageModel(...)`/
 * `SqliteContextEngine(...)` construction that used to live inline in
 * `MainActivity.onToggle()`.
 *
 * Deliberately app-level, not part of `s2s-host` itself: which concrete
 * plugins exist (llama-cpp, remote, sqlite-context, core-tools, ...) is this
 * specific app's choice, not something a generic host library should
 * hardcode.
 */
object JarvisHost {
    const val LLAMA_CPP = "llama-cpp"
    const val REMOTE_LLM = "remote"
    const val SQLITE_CONTEXT = "sqlite-context"
    const val CORE_TOOLS = "core-tools"

    private var registry: PluginRegistry? = null

    fun registry(context: Context): PluginRegistry = registry ?: build(context).also { registry = it }

    /**
     * Builds an [AgentRuntime] wired to [engine] and the already-[HostComposer]-resolved
     * [languageModel]/[contextEngine]/[tools] — the actual model→parse→tool→
     * model→final-response loop that [S2SEngine] itself deliberately does
     * not own.
     */
    fun agentRuntime(engine: S2SEngine, languageModel: LanguageModel, contextEngine: ContextEngine, tools: Tools): AgentRuntime =
        AgentRuntime(engine, languageModel, contextEngine, tools, InMemoryTaskStore())

    private fun build(context: Context): PluginRegistry {
        val configStore = SharedPreferencesPluginConfigStore(context.applicationContext)
        val registry = PluginRegistry(configStore)

        registry.register(
            PluginDescriptor(LLAMA_CPP, PluginType.LANGUAGE_MODEL, "Llama.cpp", version = "0.2.0"),
            PluginProvider<LanguageModel> { config ->
                val modelPath = config["modelPath"] ?: error("llama-cpp plugin requires a 'modelPath' config value")
                LlamaLanguageModel(LlamaConfig(), modelPath)
            },
        )

        registry.register(
            PluginDescriptor(REMOTE_LLM, PluginType.LANGUAGE_MODEL, "Remote (OpenAI-compatible)", version = "0.2.0"),
            PluginProvider<LanguageModel> { config ->
                val baseUrl = config["baseUrl"] ?: error("remote plugin requires a 'baseUrl' config value")
                RemoteLanguageModel(RemoteLlmConfig(baseUrl = baseUrl, apiKey = config["apiKey"]))
            },
        )

        registry.register(
            PluginDescriptor(SQLITE_CONTEXT, PluginType.CONTEXT_ENGINE, "SQLite Context", version = "0.1.0"),
            PluginProvider<ContextEngine> { config ->
                val sessionId = config["sessionId"] ?: java.util.UUID.randomUUID().toString()
                val systemPrompt = config["systemPrompt"] ?: "Talk freely, but don't be rude. You are a helpful assistant."
                SqliteContextEngine(context.applicationContext, sessionId, systemPrompt)
            },
        )

        // Real ToolRegistry + CalculatorTool, not NoopTools — proves the
        // TOOLS slot actually reaches a working dispatcher through the
        // plugin architecture rather than resolving to an inert stub.
        registry.register(
            PluginDescriptor(CORE_TOOLS, PluginType.TOOLS, "Core Tools (Calculator)", version = "0.1.0"),
            PluginProvider<Tools> {
                ToolRegistry().also { CalculatorTool.registerOn(it) }
            },
        )

        registry.setEnabled(LLAMA_CPP, true)
        registry.setEnabled(REMOTE_LLM, true)
        registry.setEnabled(SQLITE_CONTEXT, true)
        registry.setEnabled(CORE_TOOLS, true)
        if (registry.getSelected(PluginType.LANGUAGE_MODEL) == null) registry.select(LLAMA_CPP, PluginType.LANGUAGE_MODEL)
        if (registry.getSelected(PluginType.CONTEXT_ENGINE) == null) registry.select(SQLITE_CONTEXT, PluginType.CONTEXT_ENGINE)
        if (registry.getSelected(PluginType.TOOLS) == null) registry.select(CORE_TOOLS, PluginType.TOOLS)

        return registry
    }
}
