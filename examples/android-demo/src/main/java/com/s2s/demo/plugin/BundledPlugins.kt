package com.s2s.demo.plugin

import android.content.Context
import android.util.Log
import com.s2s.context.local.SqliteContextEngine
import com.s2s.host.core.ContextAwareToolsProvider
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
import com.s2s.tools.core.IdentityTools
import com.s2s.tools.core.MemoryTools
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

    private const val TAG = "BundledPlugins"

    // Default remote endpoint — URL and model only, no key. The key is
    // never hardcoded: it goes in the settings screen's API key field
    // (PluginConfigField.Type.SECRET) same as any other secret this app
    // handles, and stays out of source and git history.
    const val OPENROUTER_DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    /**
     * A specific instruction-tuned model, NOT the `openrouter/free`
     * auto-router.
     *
     * The router chose `minimax/minimax-m3:free`, a reasoning model that
     * writes its thinking into the message `content` rather than the separate
     * `reasoning` field. On a real device that meant the assistant read its
     * whole deliberation aloud — including reciting this file's own system
     * prompt back ("Never explain reasoning step by step…", "Short,
     * conversational, 1-2 sentences max") — and took 9-22s to first audio,
     * because those tokens are generated before any answer.
     *
     * OpenRouter's `{"reasoning": {"exclude": true}}` (sent by
     * RemoteLlmConfig.excludeReasoning) does not help there: it governs the
     * structured reasoning field, not thinking inlined into content. And
     * filtering content by prose pattern is guesswork that breaks on the next
     * model. Choosing a non-reasoning model removes the problem at the root
     * instead — the tokens are never generated, so there is no latency to pay
     * and nothing to strip.
     *
     * Verified present in OpenRouter's live free-tier model list. Any model
     * the user sets in settings overrides this.
     */
    const val OPENROUTER_DEFAULT_MODEL = "google/gemma-4-31b-it:free"

    /**
     * Everything here has to survive being SPOKEN, which is why the formatting
     * rule is as explicit as the brevity one.
     *
     * A real device caught the gap: asked what it could do, the model answered
     * with a markdown bullet list, and TTS read the syntax out loud — six
     * "star star Providing information colon" lines, about 45 seconds of audio.
     * The old prompt asked for short answers but never said "no markdown", and
     * a remote model formats by default.
     */
    /**
     * No assistant NAME here, deliberately.
     *
     * This used to open "You are Jarvis". That hardcoded a persona into the
     * stable prompt prefix, where the user could not change it — and it
     * actively fought the identity layer: `AgentIdentity.displayName` is
     * prepended by `WorkingContextBuilder` as "You are <name>." whenever one
     * is stored, so a renamed assistant received both names in one prompt and
     * had to guess. The name belongs in the store the `set_identity` tool
     * writes, which is per-user and survives a restart.
     *
     * Everything that remains here is true of the assistant regardless of what
     * it is called: it speaks aloud, so it must not emit markup, and it should
     * be brief.
     */
    const val DEFAULT_SYSTEM_PROMPT =
        "You are a voice assistant. Everything you say is read aloud " +
            "by a speech synthesiser, so write plain spoken sentences only: no " +
            "markdown, no bullet points, no asterisks, no numbered lists, no " +
            "headings. If you need to give several items, say them in one " +
            "flowing sentence. Keep answers short and conversational — one or " +
            "two sentences unless the user asks for detail. When a registered " +
            "tool can answer the request (for example, a calculation), call it " +
            "instead of solving it yourself. Never explain your reasoning step " +
            "by step unless asked to."

    /**
     * [considerMemory]/[recallMemory] back the `remember`/`recall` tools
     * (see [com.s2s.tools.core.MemoryTools]) — supplied here rather than
     * closed over directly by [CalculatorTool]'s stateless pattern because
     * they need the live [SqliteContextEngine] the currently-running turn is
     * using, which does not exist yet at plugin-registration time (this
     * runs once at [com.s2s.demo.JarvisRuntime] construction; a fresh
     * `SqliteContextEngine` is built on every [com.s2s.demo.JarvisRuntime.start]).
     * They now take the engine as a PARAMETER rather than resolving it
     * themselves, because the composition-time
     * [com.s2s.host.core.ContextAwareToolsProvider] seam supplies the live
     * instance. The earlier version had them read `JarvisRuntime`'s own field,
     * which is assigned only after `HostComposer.resolve()` returns — later
     * than the tools are built — and is cleared on `stop()`. It happened to
     * work because a tool runs during a turn; it broke on a restart race. See
     * the provider registration below.
     */
    fun registerAll(
        manager: PluginManager,
        context: Context,
        considerMemory: (SqliteContextEngine, String) -> String,
        recallMemory: (SqliteContextEngine, String) -> String,
        updateIdentity: (SqliteContextEngine, String?, String?, String?) -> String,
        updateProfile: (SqliteContextEngine, String?, String?, String?) -> String,
    ) {
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
                    // Not required: OPENROUTER_DEFAULT_BASE_URL/MODEL fall in
                    // when left blank (see the PluginProvider below).
                    // baseUrl's field default is required=true, and
                    // JarvisRuntime.start()'s pre-composition check rejects a
                    // blank required field before the provider ever runs —
                    // so this must stay required=false or the default below
                    // is unreachable. apiKey has NO default: a real secret is
                    // never hardcoded, only typed into this field.
                    PluginConfigField("baseUrl", "Server URL", required = false, help = "e.g. https://my-server/v1 — leave blank to use OpenRouter."),
                    PluginConfigField("apiKey", "API key", PluginConfigField.Type.SECRET, required = false, help = "Required for OpenRouter — get one at openrouter.ai/keys."),
                    PluginConfigField(
                        "model",
                        "Model name",
                        required = false,
                        help = "As the server names it, e.g. gpt-4o-mini or qwen2.5-7b-instruct. Leave blank to use OpenRouter's free model.",
                    ),
                ),
            ),
            PluginProvider<LanguageModel> { config ->
                RemoteLanguageModel(
                    RemoteLlmConfig(
                        baseUrl = config["baseUrl"]?.takeIf { it.isNotBlank() } ?: OPENROUTER_DEFAULT_BASE_URL,
                        apiKey = config["apiKey"]?.takeIf { it.isNotBlank() },
                        // Was previously never passed, so the server always
                        // fell back to its own default model and the setting
                        // had no way to reach it.
                        remoteModelName = config["model"]?.takeIf { it.isNotBlank() } ?: OPENROUTER_DEFAULT_MODEL,
                    ),
                )
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
                version = "0.2.0",
                source = PluginSource.BUNDLED,
                availability = PluginAvailability.BUNDLED,
                description = "Built-in tools: a calculator, remember/recall for durable memory, " +
                    "and set_identity/set_user_profile so the assistant's persona and what it knows " +
                    "about the user survive a restart.",
            ),
            // ContextAwareToolsProvider, not a plain PluginProvider, and the
            // difference is correctness rather than style.
            //
            // The memory and identity tools need the ContextEngine that THIS
            // composition produced. The previous version closed over
            // JarvisRuntime's own `contextEngine` field, which HostComposer
            // assigns only after resolve() returns — so the tools were built
            // (line order: resolve, then AgentRuntime, then the field
            // assignment) before the field they read was set. It worked only
            // because a tool is invoked later, during a turn. On a
            // stop()/start() cycle the field is cleared first, so a tool call
            // racing a restart read null and reported "memory is not
            // available".
            //
            // This seam exists precisely to close that gap: HostComposer
            // resolves Tools after ContextEngine and hands the live instance
            // in, so the binding is by construction instead of by timing.
            ContextAwareToolsProvider { _, contextEngine ->
                ToolRegistry().also { registry ->
                    CalculatorTool.registerOn(registry)
                    // Only the SQLite engine carries memory/identity — the
                    // generic ContextEngine contract deliberately does not
                    // (see its own doc). A different provider means these
                    // tools are simply not registered, so the model is never
                    // shown a tool that cannot work.
                    val sqlite = contextEngine as? SqliteContextEngine
                    if (sqlite == null) {
                        Log.i(
                            TAG,
                            "context provider is not SQLite — memory and identity tools not registered",
                        )
                    } else {
                        MemoryTools(
                            considerMemory = { content -> considerMemory(sqlite, content) },
                            recallMemory = { query -> recallMemory(sqlite, query) },
                        ).registerOn(registry)
                        // The identity WRITE path. Without these, AgentIdentity
                        // and UserProfile were read on every turn and never
                        // written, so loadIdentity() always returned null and
                        // the persona fragment was filtered out of the prompt —
                        // every session started as a stranger.
                        IdentityTools(
                            updateIdentity = { name, instructions, language ->
                                updateIdentity(sqlite, name, instructions, language)
                            },
                            updateProfile = { name, style, language ->
                                updateProfile(sqlite, name, style, language)
                            },
                        ).registerOn(registry)
                    }
                }
            },
        )

        listOf(LLAMA_CPP, REMOTE_LLM, SQLITE_CONTEXT, CORE_TOOLS).forEach { manager.enable(it) }
    }
}
