package com.s2s.demo

import android.content.Context
import android.util.Log
import com.s2s.agent.agent.AgentEvent
import com.s2s.agent.agent.AgentRuntime
import com.s2s.agent.skill.SkillRegistry
import com.s2s.agent.skill.registerToolSkills
import com.s2s.agent.task.InMemoryTaskStore
import com.s2s.context.local.MemoryCandidate
import com.s2s.context.local.MemoryDecision
import com.s2s.context.local.MemoryProvenance
import com.s2s.context.local.MemoryScope
import com.s2s.context.local.SqliteContextEngine
import com.s2s.demo.plugin.AndroidPluginDiscovery
import com.s2s.demo.plugin.BoundServiceTools
import com.s2s.demo.plugin.BoundServiceTextNormalizer
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
import com.s2s.mobile.pipeline.NormalizationHeuristic
import com.s2s.mobile.pipeline.TextNormalizationPolicy
import com.s2s.mobile.pipeline.TextNormalizer
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
     * Cleans a raw transcript before the agent sees it, if a normalizer is
     * selected and the policy says this utterance is worth it.
     *
     * Runs on the agent dispatcher, between STT and `AgentRuntime.run()` —
     * the correct boundary. `S2SEngine` knows nothing about normalization
     * and `AgentRuntime` receives only finished text, so neither had to
     * change to support this.
     *
     * Always returns usable text. Every failure inside the normalizer
     * already falls back to the raw transcript; this adds the outer
     * guarantee that a thrown exception cannot lose the turn either.
     */
    private fun normalizeTranscript(raw: String): String {
        val normalizer = textNormalizer ?: return raw
        if (normalizationPolicy == TextNormalizationPolicy.DISABLED) return raw
        if (normalizationPolicy == TextNormalizationPolicy.AUTO &&
            !NormalizationHeuristic.benefitsFromNormalization(raw)
        ) {
            Log.i(TAG, "normalization skipped (AUTO: transcript looks clean)")
            return raw
        }

        val started = System.currentTimeMillis()
        val result = runCatching { normalizer.normalize(raw) }.getOrElse {
            Log.w(TAG, "normalizer threw — using raw transcript", it)
            raw
        }
        val elapsed = System.currentTimeMillis() - started
        // Timing only, never the transcript: this is the number that decides
        // whether normalization is worth its place on the voice path.
        Log.i(TAG, "normalization took ${elapsed}ms (changed=${result != raw})")
        return result
    }

    /**
     * The `remember` tool's backing closure — passed to [com.s2s.tools.core.MemoryTools]
     * when composing the TOOLS capability (see [buildRegistry]/[BundledPlugins]).
     *
     * Called only when the model itself decides to invoke `remember`, which
     * IS the judgment call [com.s2s.context.local.MemoryWriter] used to guess
     * at from a phrase list — a real device caught that guess storing
     * "Hello cookies, remember this" verbatim because the substring
     * "remember this" appeared anywhere in the sentence. The model's own
     * generation already reasoning about the conversation replaces that
     * guess for free, with no second LLM call.
     *
     * Still goes through the real gate unchanged: [explicit] only marks that
     * a caller judged this worth keeping, [MemoryWriter.consider] still
     * enforces provenance/scope/dedup regardless of who's asking.
     */
    private fun considerForMemory(content: String): String {
        val store = sqliteMemoryStore ?: return "Memory is not available with the current context provider."
        return runCatching {
            val decision = store.memoryWriter.consider(
                MemoryCandidate(
                    content = content,
                    scope = MemoryScope.User,
                    provenance = MemoryProvenance.USER,
                    explicit = true,
                ),
            )
            when (decision) {
                is MemoryDecision.Stored -> "Stored: ${decision.memory.content}".also { Log.i(TAG, "memory stored: \"${decision.memory.content}\"") }
                is MemoryDecision.Updated -> "Updated: ${decision.memory.content}".also { Log.i(TAG, "memory updated: \"${decision.memory.content}\"") }
                is MemoryDecision.Duplicate -> "Already known: ${decision.existing.content}".also { Log.i(TAG, "memory already known: \"${decision.existing.content}\"") }
                is MemoryDecision.Ignored -> "Not stored: ${decision.reason}".also { Log.i(TAG, "memory not stored: ${decision.reason}") }
            }
        }.getOrElse {
            Log.w(TAG, "memory write failed", it)
            "Could not store that right now."
        }
    }

    /** The `recall` tool's backing closure — searches memory for [query]. See [considerForMemory]'s doc for why this is a tool call, not automatic. */
    private fun recallFromMemory(query: String): String {
        val store = sqliteMemoryStore ?: return "Memory is not available with the current context provider."
        return runCatching {
            val found = store.memories.relevant(sessionId = currentSessionId.orEmpty(), query = query, limit = 5)
            if (found.isEmpty()) "No relevant memory found for: $query" else found.joinToString("; ") { it.content }
        }.getOrElse {
            Log.w(TAG, "memory recall failed", it)
            "Could not search memory right now."
        }
    }

    /** The selected normalizer plugin, or null if none is installed/enabled/selected. Never throws — normalization is optional. */
    private fun resolveNormalizer(): TextNormalizer? {
        val selected = registry.getSelected(PluginType.SPEECH_TEXT_NORMALIZER) ?: return null
        if (!registry.canCompose(selected)) {
            Log.i(TAG, "normalizer plugin $selected is selected but not composable — skipping")
            return null
        }
        val descriptor = registry.find(selected) ?: return null
        return runCatching {
            val (packageName, serviceClass) = descriptor.entryPoint.address.split('/', limit = 2)
            BoundServiceTextNormalizer(appContext, packageName, serviceClass)
        }.onFailure { Log.w(TAG, "could not build normalizer for $selected", it) }.getOrNull()
    }

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

    /** Set at the start of [start], read by [recallFromMemory] — the session whose SESSION-scoped memories should be visible to this runtime's own recall calls. */
    private var currentSessionId: String? = null

    /**
     * [contextEngine] narrowed to its concrete type, when the SQLite-backed
     * one is selected — so a settings UI can reach [SqliteContextEngine.identities]
     * / `.memories` / `.memoryWriter`, which [ContextEngine] deliberately
     * does not expose (see that interface's own doc: core has no opinion on
     * memory management, only on what a turn's prompt looks like).
     *
     * Naming the concrete type here is the same exception [BundledPlugins]
     * documents for itself and this class already takes for
     * [BoundServiceTools]/[BoundServiceTextNormalizer]: something has to
     * reach a capability the generic contract doesn't carry, and pretending
     * otherwise would be a fiction. Null whenever a different
     * [com.s2s.host.core.PluginType.CONTEXT_ENGINE] provider is selected —
     * callers must not assume SQLite.
     */
    val sqliteMemoryStore: SqliteContextEngine? get() = contextEngine as? SqliteContextEngine

    /**
     * The selected transcript normalizer, if one is installed, enabled and
     * selected. Null is the normal case — normalization is optional, and
     * everything works without it.
     */
    private var textNormalizer: TextNormalizer? = null

    /**
     * When normalization runs. AUTO by default: on a phone, paying a model
     * call for "what time is it" is worse than leaving a rough transcript
     * alone, so only utterances that actually look like they need cleaning
     * get one. See [NormalizationHeuristic].
     */
    var normalizationPolicy: TextNormalizationPolicy = TextNormalizationPolicy.AUTO

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
        // not a hardcoded plugin id. Switching the selected LLM then keeps
        // working with no change here — the point of removing the
        // hardcoding.
        //
        // MERGED, not replaced: llmConfig carries the on-device model path
        // this host derived from the model spinner, but a remote provider is
        // configured with a URL and key the user typed into a settings form.
        // Overwriting would wipe those the moment the engine started, so
        // stored values win and the host only fills in what is missing.
        registry.getSelected(PluginType.LANGUAGE_MODEL)?.let { pluginId ->
            val stored = registry.getConfig(pluginId).values
            registry.setConfig(pluginId, PluginConfig(llmConfig + stored))
        }
        registry.getSelected(PluginType.CONTEXT_ENGINE)?.let { pluginId ->
            val stored = registry.getConfig(pluginId).values
            registry.setConfig(pluginId, PluginConfig(contextConfig + stored))
        }

        // Check required config BEFORE composing, so a half-configured
        // provider produces a sentence the user can act on rather than a
        // CompositionException naming an internal failure type. The remote
        // LLM selected with no server URL is the realistic case.
        registry.getSelected(PluginType.LANGUAGE_MODEL)?.let { pluginId ->
            val descriptor = registry.find(pluginId)
            val stored = registry.getConfig(pluginId).values
            val missing = descriptor?.configSchema
                ?.filter { it.required && stored[it.key].isNullOrBlank() }
                ?.map { it.label }
                .orEmpty()
            if (missing.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "${descriptor?.displayName ?: pluginId} needs ${missing.joinToString(", ")} — " +
                            "set it under \"Answering with\", or switch back to the on-device model.",
                    ),
                )
            }
        }

        val composed = HostComposer(registry).resolve().getOrElse {
            return Result.failure(it)
        }

        val runtimeScope = CoroutineScope(Job() + agentDispatcher)
        scope = runtimeScope

        lateinit var runtime: AgentRuntime
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
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
                runtimeScope.launch {
                    // Barge-in: stop the turn already running before starting
                    // this one. AgentRuntime enforces WIP=1 per session, so
                    // without this run() throws and — inside a bare launch —
                    // the coroutine died silently. On a real device that meant
                    // the user spoke, nothing answered, and nothing was logged.
                    if (runtime.cancelSession(sessionId)) {
                        Log.i(TAG, "barge-in: cancelled the in-flight turn for this utterance")
                    }
                    val normalized = normalizeTranscript(text)
                    // No automatic memory consideration here anymore — the
                    // model decides by calling the `remember` tool (see
                    // considerForMemory's doc for why: a phrase-list guess
                    // stored "Hello cookies, remember this" verbatim on a
                    // real device, because it merely contained the substring
                    // "remember this"). AgentRuntime.run() already routes a
                    // remember/recall tool call through ToolCoordinator like
                    // any other tool, so nothing extra is needed here.
                    runCatching { runtime.run(normalized) }
                        .onFailure { Log.w(TAG, "turn failed: ${it.message}", it) }
                }
            },
        )

        val initResult = e.initialize()
        if (initResult.isFailure) {
            runtimeScope.cancel()
            scope = null
            return Result.failure(initResult.exceptionOrNull() ?: IllegalStateException("S2SEngine.initialize() failed"))
        }

        // One skill per installed tool, derived from the tools themselves —
        // so a turn's prompt lists only the tools whose own description
        // matches what the user said, instead of the whole catalogue. Built
        // here, after composition, because the catalogue is whatever the
        // enabled plugins contribute and is not known before that.
        val skills = SkillRegistry().apply { registerToolSkills(composed.tools.definitions) }
        runtime = AgentRuntime(
            e,
            composed.languageModel,
            composed.contextEngine,
            composed.tools,
            InMemoryTaskStore(),
            skills = skills,
        )
        runtime.addListener { event -> _agentEvents.tryEmit(event) }
        agentRuntime = runtime
        contextEngine = composed.contextEngine

        // Resolve the normalizer separately from HostComposer's three core
        // capabilities: it is genuinely optional, and a missing or broken
        // normalizer must never stop the assistant from starting. Warmed up
        // here, off the voice path, so the first utterance doesn't pay the
        // model's cold start.
        textNormalizer = resolveNormalizer()
        (textNormalizer as? BoundServiceTextNormalizer)?.let { normalizer ->
            runtimeScope.launch {
                val warmed = normalizer.warmUp()
                Log.i(TAG, "text normalizer warm-up ${if (warmed) "succeeded" else "failed (raw transcripts will be used)"}")
            }
        }
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
        currentSessionId = null
        // Unbinds and tells the plugin to free its model — otherwise a
        // 462 MiB normalizer stays resident in another process after this
        // runtime has stopped needing it.
        textNormalizer?.release()
        textNormalizer = null
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

        BundledPlugins.registerAll(manager, app, ::considerForMemory, ::recallFromMemory)

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
        private const val TAG = "JarvisRuntime"

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
                PluginType.SPEECH_TEXT_NORMALIZER -> PluginProvider {
                    BoundServiceTextNormalizer(context, packageName, serviceClass)
                }
                // LANGUAGE_MODEL and CONTEXT_ENGINE have no IPC contract
                // yet: both need streaming surfaces (token-by-token
                // generation, cancellation) that a request/response AIDL
                // interface does not express well. Declaring one is a
                // deliberate design step, not something to fake here.
                else -> throw IllegalArgumentException(
                    "External plugins of type ${found.descriptor.type} have no IPC contract yet",
                )
            }
        }
    }
}
