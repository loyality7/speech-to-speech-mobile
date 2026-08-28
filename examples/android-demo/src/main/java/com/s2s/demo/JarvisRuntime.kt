package com.s2s.demo

import android.content.Context
import com.s2s.agent.agent.AgentEvent
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
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.pipeline.ContextEngine
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.Tools
import com.s2s.tools.core.CalculatorTool
import com.s2s.tools.core.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The Android runtime composition boundary: turns "which plugins are
 * enabled/selected" (owned by [PluginRegistry]) into one running
 * [S2SEngine] + [AgentRuntime] pair, and owns the lifecycle of that pair.
 *
 * One object, not two — a separate `JarvisHost` class was considered and
 * rejected: `PluginRegistry` already IS the host-level composition state,
 * and adding a second wrapper around it would only rename this class, not
 * clarify ownership. This class is instance-owned (constructed once per
 * [android.app.Activity]/application, not a static singleton) specifically
 * so [stop] can leave nothing behind for the next [start] to accidentally
 * observe — the previous `JarvisHost.registry` static `var` held a
 * [PluginRegistry] for the whole process lifetime, which is exactly the
 * "hidden static singleton" this class replaces.
 *
 * Turn dispatch: [S2SEngine]'s `externalTurnHandler` runs synchronously on
 * whatever thread is feeding it audio frames (the microphone/recognizer
 * path, not the UI thread) — see [S2SEngine.beginTurn]'s callers. Blocking
 * that path on [AgentRuntime.run] (which itself blocks on LLM inference)
 * would stall audio processing, so every utterance is dispatched onto
 * [agentDispatcher], a single dedicated thread scoped to this runtime's
 * lifetime and cancelled in [stop] — replacing the previous ad-hoc,
 * unscoped `Thread { runtime.run(text) }.start()` that had no owner and
 * nothing to cancel it on shutdown.
 */
class JarvisRuntime(private val appContext: Context) {
    companion object {
        const val LLAMA_CPP = "llama-cpp"
        const val REMOTE_LLM = "remote"
        const val SQLITE_CONTEXT = "sqlite-context"
        const val CORE_TOOLS = "core-tools"

        /**
         * Real-device evidence: the prior default ("Talk freely, but don't be
         * rude. You are a helpful assistant.") gave a small local model
         * (Qwen2.5-0.5B) zero guidance on voice-appropriate brevity or tool
         * usage, and it responded to a simple subtraction request by
         * narrating a multi-step long-division-style explanation instead of
         * calling the calculator tool. This does not fix a weak model's
         * reasoning limits — a 0.5B model will still be a 0.5B model — but it
         * removes the part of the poor behavior that was actually the
         * prompt's fault: no instruction to be concise, and no instruction to
         * prefer a registered tool over mental math.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Jarvis, a voice assistant. Keep answers short and " +
                "conversational — one or two sentences unless the user asks " +
                "for detail. When a registered tool can answer the request " +
                "(for example, a calculation), call it instead of solving it " +
                "yourself. Never explain your reasoning step by step unless " +
                "asked to."
    }

    val registry: PluginRegistry = buildRegistry(appContext)

    var engine: S2SEngine? = null
        private set
    var agentRuntime: AgentRuntime? = null
        private set
    private var contextEngine: ContextEngine? = null

    /** Derived, not tracked separately — [engine] is non-null for exactly the lifetime a call to [start] has succeeded and [stop] hasn't yet cleared it. */
    val isRunning: Boolean get() = engine != null

    private val _agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

    /**
     * The one seam a host UI needs to show the assistant's final response —
     * `AgentRuntime.run()` drives generation through `S2SEngine.speakAssistantText()`
     * (audio only), so nothing before this existed to put that same text on
     * screen. The UI observes [AgentEvent.TaskCompleted]/[AgentEvent.TaskFailed]
     * here; it never touches [agentRuntime] or any tool/generation internals —
     * those stay inside [AgentEvent]'s existing "safe metadata only" contract.
     */
    val agentEvents: SharedFlow<AgentEvent> = _agentEvents.asSharedFlow()

    private val agentExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Jarvis-Agent") }
    private val agentDispatcher = agentExecutor.asCoroutineDispatcher()
    private var scope: CoroutineScope? = null

    /**
     * Resolves the currently selected providers via [HostComposer], builds a
     * fresh [S2SEngine] + [AgentRuntime] pair wired to each other, and calls
     * [S2SEngine.initialize] + [S2SEngine.start]. Safe to call again after
     * [stop] — each call resolves the registry's current state fresh, so a
     * plugin switch between calls is picked up automatically ([HostComposer]
     * holds no cached state of its own).
     */
    suspend fun start(config: S2SConfig, llmConfig: Map<String, String>, contextConfig: Map<String, String>): Result<Unit> {
        check(!isRunning) { "JarvisRuntime.start() called while already running — call stop() first" }

        registry.setConfig(LLAMA_CPP, PluginConfig(llmConfig))
        registry.setConfig(SQLITE_CONTEXT, PluginConfig(contextConfig))

        val composed = HostComposer(registry).resolve().getOrElse {
            return Result.failure(it)
        }

        val runtimeScope = CoroutineScope(Job() + agentDispatcher)
        scope = runtimeScope

        lateinit var runtime: AgentRuntime
        val sessionId = UUID.randomUUID().toString()
        val e = S2SEngine(
            appContext,
            config,
            languageModel = composed.languageModel,
            history = composed.contextEngine,
            sessionId = sessionId,
            externalTurnHandler = { text ->
                // Dispatched onto agentDispatcher, never run inline on the
                // audio/recognizer thread that invoked this callback (see
                // class doc) — and scoped to runtimeScope so stop()'s
                // scope.cancel() actually reaches an in-flight turn instead
                // of leaving an orphaned, uncancellable Thread running.
                runtimeScope.launch { runtime.run(text) }
            },
        )

        val initResult = e.initialize()
        if (initResult.isFailure) {
            runtimeScope.cancel()
            scope = null
            return Result.failure(initResult.exceptionOrNull() ?: IllegalStateException("S2SEngine.initialize() failed"))
        }

        runtime = AgentRuntime(e, composed.languageModel, composed.contextEngine, composed.tools, InMemoryTaskStore())
        runtime.addListener { event -> _agentEvents.tryEmit(event) }
        agentRuntime = runtime
        contextEngine = composed.contextEngine
        engine = e
        e.start()
        return Result.success(Unit)
    }

    /**
     * Cancels any in-flight agent turn, releases [S2SEngine] (frees mic/model
     * resources — matches [S2SEngine.release]'s own contract), closes
     * [contextEngine] (fixes the SQLiteConnectionPool leak previously
     * observed on a real device — [ContextEngine.close] didn't exist until
     * this fix, so nothing ever released [SqliteContextEngine]'s open
     * connection), and clears [engine]/[agentRuntime]/[contextEngine] so a
     * stale reference can't be observed after this returns. [registry]
     * itself is NOT cleared — plugin enable/selection/config is durable host
     * state (backed by [SharedPreferencesPluginConfigStore]), not runtime
     * state, and survives a stop/start cycle by design.
     */
    fun stop() {
        if (!isRunning) return

        scope?.cancel()
        scope = null

        engine?.release()
        engine = null
        agentRuntime = null
        contextEngine?.close()
        contextEngine = null
    }

    /** Fully shuts down this runtime instance, including the dedicated agent thread — call once, when the owning component is destroyed for good (e.g. `onDestroy`), never before a plain restart (use [stop] + [start] for that). */
    fun shutdown() {
        stop()
        agentExecutor.shutdown()
    }

    private fun buildRegistry(context: Context): PluginRegistry {
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
                val sessionId = config["sessionId"] ?: UUID.randomUUID().toString()
                val systemPrompt = config["systemPrompt"] ?: DEFAULT_SYSTEM_PROMPT
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
