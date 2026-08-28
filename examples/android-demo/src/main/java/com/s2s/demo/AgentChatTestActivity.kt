package com.s2s.demo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s2s.agent.agent.AgentEvent
import com.s2s.agent.agent.AgentRuntime
import com.s2s.agent.task.InMemoryTaskStore
import com.s2s.host.core.HostComposer
import com.s2s.demo.plugin.BundledPlugins
import com.s2s.host.core.PluginConfig
import com.s2s.host.core.PluginType
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.S2SModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Text-only agent test harness: type a prompt, see the full
 * classify -> tool-call -> tool-result -> final-answer trace, with no
 * microphone/VAD/STT/TTS involved. Exists specifically to answer "if I give
 * it a good prompt, does it actually use a tool?" without needing to speak
 * to the device and guess from what came out of the speaker.
 *
 * Reuses the exact same [HostComposer]/[AgentRuntime] construction path as
 * [MainActivity]/[JarvisRuntime] — the only difference is [S2SEngine] is
 * constructed once purely so [AgentRuntime] has something to call
 * `speakAssistantText()` on, and TTS is never actually started (no
 * `S2SEngine.start()`, no microphone permission needed). This is a test
 * tool, not a second composition root — it doesn't reimplement anything
 * [JarvisRuntime] already does.
 */
class AgentChatTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var promptInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var statusText: TextView
    private lateinit var traceLog: TextView

    private var engine: S2SEngine? = null
    private var runtime: AgentRuntime? = null

    /** Guards against a second initializeAgent() call racing the first — llama.cpp is process-global (one LlamaLanguageModel per process), so a genuine double-init crashes the second attempt rather than queuing behind the first. */
    @Volatile private var initializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        initializeAgent()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(
            TextView(this).apply {
                text = "🧪 Agent Tool-Calling Tester"
                textSize = 20f
                setTextColor(Color.BLACK)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        root.addView(
            TextView(this).apply {
                text = "Type a request and watch the exact classify -> tool -> result -> answer trace below. No microphone needed."
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 4, 0, 12)
            },
        )

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            text = "Initializing agent…"
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        promptInput = EditText(this).apply {
            hint = "e.g. \"What is 25 times 18?\""
            setText("What is 25 times 18?")
        }
        root.addView(promptInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        sendBtn = Button(this).apply {
            text = "Send"
            isEnabled = false
        }
        sendBtn.setOnClickListener { onSend() }
        root.addView(sendBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })

        traceLog = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroller = ScrollView(this).apply { addView(traceLog) }
        root.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 16 })

        return root
    }

    private fun appendTrace(line: String) {
        Log.d("AgentChatTest", line)
        traceLog.append("$line\n")
    }

    private fun initializeAgent() {
        if (initializing) return
        initializing = true
        scope.launch {
            try {
                val config = ModelConfigFactory.create(
                    S2SModels.dir(this@AgentChatTestActivity),
                    ModelRegistry.DEFAULT_VAD,
                    ModelRegistry.DEFAULT_STT,
                    ModelRegistry.DEFAULT_TTS,
                    ModelRegistry.DEFAULT_LLM,
                )
                val registry = com.s2s.demo.plugin.JarvisRuntimeHolder.get(applicationContext).registry
                registry.setConfig(BundledPlugins.LLAMA_CPP, PluginConfig(mapOf("modelPath" to config.models.llmModel)))
                val sessionId = UUID.randomUUID().toString()
                registry.setConfig(
                    BundledPlugins.SQLITE_CONTEXT,
                    PluginConfig(mapOf("sessionId" to sessionId, "systemPrompt" to BundledPlugins.DEFAULT_SYSTEM_PROMPT)),
                )
                registry.select(BundledPlugins.CORE_TOOLS, PluginType.TOOLS)

                val composed = HostComposer(registry).resolve().getOrElse {
                    appendTrace("COMPOSITION FAILED: $it")
                    statusText.text = "Failed to compose plugins — see log above"
                    return@launch
                }

                val e = S2SEngine(
                    applicationContext,
                    config,
                    languageModel = composed.languageModel,
                    history = composed.contextEngine,
                    sessionId = sessionId,
                )
                // Assigned before initialize() completes, not after — if the
                // Activity is destroyed mid-init (e.g. rotation, or the user
                // backing out before load finishes), onDestroy()'s
                // engine?.release() must still be able to free llama.cpp's
                // process-global claim. A blocking native init call keeps
                // running even after scope.cancel(), so onDestroy() needs a
                // real reference to release regardless of where init was
                // interrupted — otherwise a second launch of this screen hits
                // "Another LlamaLanguageModel is already initialised."
                engine = e
                if (e.initialize().isFailure) {
                    appendTrace("S2SEngine.initialize() FAILED")
                    statusText.text = "Model failed to load — check models are downloaded"
                    return@launch
                }

                val rt = AgentRuntime(e, composed.languageModel, composed.contextEngine, composed.tools, InMemoryTaskStore())
                rt.addListener { event -> scope.launch { renderEvent(event) } }
                runtime = rt

                statusText.text = "Ready — model loaded, ${composed.tools.definitions.size} tool(s) registered: " +
                    composed.tools.definitions.joinToString(", ") { it.name }
                sendBtn.isEnabled = true
            } catch (ex: Throwable) {
                appendTrace("INIT EXCEPTION: ${Log.getStackTraceString(ex)}")
                statusText.text = "Init failed — see log"
            }
        }
    }

    private fun renderEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TaskStarted -> appendTrace("[task started]")
            is AgentEvent.GenerationStarted -> appendTrace("  step ${event.step}: generation started")
            is AgentEvent.GenerationCompleted -> appendTrace("  step ${event.step}: generation completed")
            is AgentEvent.ToolCallStarted -> appendTrace("  -> TOOL CALL: ${event.toolName} (id=${event.callId})")
            is AgentEvent.ToolCallCompleted -> appendTrace("  <- tool result (${if (event.isError) "ERROR" else "ok"}): ${event.toolName}")
            is AgentEvent.ConfirmationRequired -> appendTrace("  [confirmation required for ${event.toolName}]")
            is AgentEvent.TaskCompleted -> {
                appendTrace("FINAL ANSWER: ${event.response}")
                appendTrace("==========================================")
                sendBtn.isEnabled = true
            }
            is AgentEvent.TaskFailed -> {
                appendTrace("TASK FAILED: ${event.message}")
                appendTrace("==========================================")
                sendBtn.isEnabled = true
            }
            is AgentEvent.TaskCancelled -> {
                appendTrace("TASK CANCELLED")
                sendBtn.isEnabled = true
            }
        }
    }

    private fun onSend() {
        val text = promptInput.text.toString().trim()
        if (text.isEmpty()) return
        val rt = runtime ?: return

        sendBtn.isEnabled = false
        appendTrace("==========================================")
        appendTrace("USER: $text")

        scope.launch {
            withContext(Dispatchers.Default) {
                try {
                    rt.run(text)
                } catch (ex: Throwable) {
                    withContext(Dispatchers.Main) {
                        appendTrace("RUN EXCEPTION: ${Log.getStackTraceString(ex)}")
                        sendBtn.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        engine?.release()
    }
}
