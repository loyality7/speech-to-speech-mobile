package com.s2s.demo

import android.content.Context
import com.s2s.agent.agent.AgentEvent
import com.s2s.agent.agent.AgentRuntime
import com.s2s.agent.task.InMemoryTaskStore
import com.s2s.demo.plugin.AndroidPluginDiscovery
import com.s2s.demo.plugin.BoundServiceTools
import com.s2s.demo.plugin.BundledPlugins
import com.s2s.demo.plugin.SharedPreferencesPluginInstallStore
import com.s2s.host.core.DiscoveredPlugin
import com.s2s.host.core.HostComposer
import com.s2s.host.core.PluginConfig
import com.s2s.host.core.PluginEntryPoint
import com.s2s.host.core.PluginManager
import com.s2s.host.core.PluginProvider
import com.s2s.host.core.PluginRegistry
import com.s2s.host.core.PluginType
import com.s2s.host.core.SharedPreferencesPluginConfigStore
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.pipeline.ContextEngine
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

    /**
     * The plugin lifecycle facade a UI uses to install/enable/configure/
     * select plugins. Assigned during [buildRegistry] — the registry and
     * the manager are built together because the manager is what populates
     * the registry.
     */
    lateinit var pluginManager: PluginManager
        private set

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

        // Configure whichever plugin is currently SELECTED for each type,
        // not a hardcoded plugin id. Switching the selected LLM in the
        // Plugins screen then has to keep working with no change here —
        // which was the point of removing the hardcoding.
        registry.getSelected(PluginType.LANGUAGE_MODEL)?.let { registry.setConfig(it, PluginConfig(llmConfig)) }
        registry.getSelected(PluginType.CONTEXT_ENGINE)?.let { registry.setConfig(it, PluginConfig(contextConfig)) }

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

    /**
     * Builds the registry from two sources, in order:
     *
     *  1. [BundledPlugins] — compiled into this APK. The only place concrete
     *     provider classes are named.
     *  2. Externally-installed plugin APKs the user has installed, found via
     *     [AndroidPluginDiscovery] and bound over IPC.
     *
     * Adding a new external plugin requires no change to this method, to
     * `HostComposer`, to `AgentRuntime`, or to `S2SEngine` — that is the
     * property the whole plugin platform exists to provide.
     */
    private fun buildRegistry(context: Context): PluginRegistry {
        val app = context.applicationContext
        val registry = PluginRegistry(SharedPreferencesPluginConfigStore(app))
        val manager = PluginManager(
            registry = registry,
            installStore = SharedPreferencesPluginInstallStore(app),
            discovery = AndroidPluginDiscovery(app),
        )
        pluginManager = manager

        BundledPlugins.registerAll(manager, app)

        // Re-register anything the user previously installed. Discovery
        // alone never activates a plugin — only a plugin with a stored
        // installation record (and a matching signing identity) comes back.
        manager.refreshDiscovered { found -> providerFor(app, found, registry.getConfig(found.descriptor.pluginId).values) }

        if (registry.getSelected(PluginType.LANGUAGE_MODEL) == null) manager.select(BundledPlugins.LLAMA_CPP, PluginType.LANGUAGE_MODEL)
        if (registry.getSelected(PluginType.CONTEXT_ENGINE) == null) manager.select(BundledPlugins.SQLITE_CONTEXT, PluginType.CONTEXT_ENGINE)
        if (registry.getSelected(PluginType.TOOLS) == null) manager.select(BundledPlugins.CORE_TOOLS, PluginType.TOOLS)

        return registry
    }

    companion object Providers {
        /**
         * Turns a discovered external plugin into the capability contract
         * the host composes.
         *
         * The host knows the plugin only by its declared [PluginType] and
         * its [PluginEntryPoint] address — never by class name. A
         * [PluginEntryPoint.Kind.BOUND_SERVICE] TOOLS plugin becomes a
         * [BoundServiceTools]; anything else is currently unsupported and
         * is rejected here rather than half-composed.
         */
        fun providerFor(context: Context, found: DiscoveredPlugin, config: Map<String, String>): PluginProvider<*> {
            val entry = found.descriptor.entryPoint
            require(entry.kind == PluginEntryPoint.Kind.BOUND_SERVICE) {
                "External plugin ${found.descriptor.pluginId} has unsupported entry point ${entry.kind}"
            }
            val (packageName, serviceClass) = entry.address.split('/', limit = 2)
                .also { require(it.size == 2) { "Malformed bound-service address: ${entry.address}" } }

            return when (found.descriptor.type) {
                PluginType.TOOLS -> PluginProvider { cfg ->
                    BoundServiceTools(context, packageName, serviceClass, cfg.values.ifEmpty { config })
                }
                // No IPC contract exists yet for a remote LLM/context/
                // normalizer — declaring one is a separate, deliberate
                // decision (each needs its own AIDL surface and streaming
                // story), not something to fake here.
                else -> throw IllegalArgumentException(
                    "External plugins of type ${found.descriptor.type} are not supported yet — only TOOLS have an IPC contract",
                )
            }
        }
    }
}
