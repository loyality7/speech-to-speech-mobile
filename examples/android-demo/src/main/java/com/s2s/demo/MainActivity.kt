package com.s2s.demo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.S2SEvent
import com.s2s.mobile.config.ModelPaths
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.config.SttBackend
import com.s2s.mobile.config.SttConfig
import com.s2s.mobile.config.TtsConfig
import com.s2s.mobile.pipeline.TtsBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal harness for the S2S SDK: one button, a status line, a transcript.
 *
 * Models are read from the app's external files directory so they can be pushed
 * with adb instead of downloaded:
 *
 * ```
 * adb push silero_vad.onnx  /sdcard/Android/data/com.s2s.demo/files/models/
 * adb push stt/             /sdcard/Android/data/com.s2s.demo/files/models/stt/
 * adb push tts/             /sdcard/Android/data/com.s2s.demo/files/models/tts/
 * adb push model.gguf       /sdcard/Android/data/com.s2s.demo/files/models/
 * ```
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var toggle: Button
    private lateinit var download: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    private var engine: S2SEngine? = null
    private var running = false
    private var partialShown = false
    private val downloader by lazy { ModelDownloader(modelsDir()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        toggle.setOnClickListener { onToggle() }
        download.setOnClickListener { onDownload() }
        status.text = describeModels()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        download = Button(this).apply { text = "Download models" }
        root.addView(
            download,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 },
        )

        toggle = Button(this).apply { text = "Start" }
        root.addView(
            toggle,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 },
        )

        transcript = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.BOTTOM
            movementMethod = ScrollingMovementMethod()
        }
        val scroller = ScrollView(this).apply { addView(transcript) }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 24 },
        )
        return root
    }

    private fun onDownload() {
        val pending = downloader.missing()
        if (pending.isEmpty()) {
            status.text = "Models ready. Tap Start."
            return
        }

        download.isEnabled = false
        toggle.isEnabled = false
        val totalMb = pending.sumOf { it.approxBytes } / 1_000_000

        scope.launch {
            val failure = runCatching {
                downloader.downloadAll { p ->
                    // Progress arrives on the IO thread; hop back for the UI.
                    runOnUiThread {
                        status.text = when {
                            p.extracting -> "Extracting ${p.label}…"
                            else -> "${p.label}  ${p.percent}%   (${totalMb}MB total)"
                        }
                    }
                }
            }.exceptionOrNull()

            download.isEnabled = true
            toggle.isEnabled = true
            status.text = failure?.let { "Download failed: ${it.message}" } ?: describeModels()
        }
    }

    private fun onToggle() {
        if (running) {
            engine?.stop()
            running = false
            toggle.text = "Start"
            status.text = "Stopped"
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }

        val missing = missingModels()
        if (missing.isNotEmpty()) {
            status.text = "Missing: ${missing.joinToString(", ")}\nin ${modelsDir().absolutePath}"
            return
        }

        toggle.isEnabled = false
        status.text = "Loading models…"

        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val e = engine ?: S2SEngine(
                    this@MainActivity,
                    S2SConfig(
                        models = ModelPaths(
                            vadModel = File(modelsDir(), VAD).absolutePath,
                            sttDir = File(modelsDir(), STT).absolutePath,
                            llmModel = File(modelsDir(), LLM).absolutePath,
                            ttsDir = File(modelsDir(), TTS).absolutePath,
                        ),
                        // Piper synthesises many times faster than Kokoro on a
                        // mid-range phone, which is what keeps speech continuous
                        // rather than arriving in chunks with gaps between them.
                        // Moonshine is far more accurate than the streaming
                        // recogniser on short conversational turns. It cannot
                        // stream, so its decode lands in the response path.
                        stt = SttConfig(backend = SttBackend.MOONSHINE),
                        tts = TtsConfig(backend = TtsBackend.VITS),
                    ),
                ).also { created ->
                    engine = created
                    collectEvents(created)
                }
                e.initialize().isSuccess && e.start()
            }

            toggle.isEnabled = true
            if (loaded) {
                running = true
                toggle.text = "Stop"
            } else {
                status.text = "Failed to start — check logcat (tag S2S*)"
            }
        }
    }

    private fun collectEvents(engine: S2SEngine) {
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is S2SEvent.UserTranscript ->
                        if (event.isFinal) {
                            replacePartial("You: ${event.text}\n")
                            partialShown = false
                        } else {
                            replacePartial("You: ${event.text}")
                            partialShown = true
                        }

                    is S2SEvent.AssistantDelta -> transcript.append(event.text)
                    is S2SEvent.AssistantDone -> transcript.append("\n\n")
                    is S2SEvent.StateChanged -> status.text = event.state.name
                    S2SEvent.BargeIn -> transcript.append("  [interrupted]\n")
                    is S2SEvent.Metrics ->
                        status.text = "first token ${event.metrics.timeToFirstTokenMs}ms · " +
                            "first audio ${event.metrics.timeToFirstAudioMs}ms"

                    is S2SEvent.ToolExecuted -> transcript.append("[tool ${event.name}] ${event.output}\n")
                    is S2SEvent.Error -> status.text = "Error: ${event.message}"
                }
            }
        }
    }

    /** Rewrites the trailing live-partial line instead of appending duplicates. */
    private fun replacePartial(text: String) {
        val current = transcript.text.toString()
        val base = if (partialShown) current.substringBeforeLast("You: ", "") else current
        transcript.text = base + text
    }

    private fun modelsDir() = File(getExternalFilesDir(null), "models")

    private fun missingModels(): List<String> = buildList {
        val dir = modelsDir()
        if (!File(dir, VAD).isFile) add(VAD)
        if (!File(dir, STT).isDirectory) add("$STT/")
        if (!File(dir, TTS).isDirectory) add("$TTS/")
        if (!File(dir, LLM).isFile) add(LLM)
    }

    private fun describeModels(): String {
        val missing = missingModels()
        return if (missing.isEmpty()) {
            "Models ready. Tap Start."
        } else {
            "Push models to:\n${modelsDir().absolutePath}\n\nMissing: ${missing.joinToString(", ")}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        engine?.release()
        engine = null
    }

    private companion object {
        const val REQ_MIC = 1
        const val VAD = "silero_vad.onnx"
        const val STT = "stt"
        const val TTS = "tts"
        const val LLM = "model.gguf"
    }
}
