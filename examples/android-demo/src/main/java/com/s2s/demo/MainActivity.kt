package com.s2s.demo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.S2SEvent
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.model.ModelDownloads
import com.s2s.mobile.model.DownloadState
import com.s2s.mobile.model.ModelDownloader
import com.s2s.mobile.model.ModelProgress
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.S2SModels
import com.s2s.mobile.model.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Harness for the S2S SDK featuring background downloading via Foreground Service,
 * interactive model selection across all categories, and direct navigation to
 * dedicated standalone TTS & STT testing screens.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var vadSpinner: Spinner
    private lateinit var sttSpinner: Spinner
    private lateinit var ttsSpinner: Spinner
    private lateinit var llmSpinner: Spinner
    private lateinit var voiceSpinner: Spinner

    private lateinit var toggle: Button
    private lateinit var downloadBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var openTtsTestBtn: Button
    private lateinit var openSttTestBtn: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    private var selectedVad: ModelSpec = ModelRegistry.DEFAULT_VAD
    private var selectedStt: ModelSpec = ModelRegistry.DEFAULT_STT
    private var selectedTts: ModelSpec = ModelRegistry.DEFAULT_TTS
    private var selectedLlm: ModelSpec = ModelRegistry.DEFAULT_LLM

    private val downloads by lazy { ModelDownloads(this) }
    private var isDownloading = false

    private var engine: S2SEngine? = null
    private var running = false
    private var partialShown = false
    private val downloader by lazy { ModelDownloader(modelsDir()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        val reqs = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reqs.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (reqs.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(reqs.toTypedArray(), REQ_PERMS)
        }

        setupSpinners()
        toggle.setOnClickListener { onToggle() }
        downloadBtn.setOnClickListener { onDownloadOrStop() }
        clearBtn.setOnClickListener { onClearModels() }
        openTtsTestBtn.setOnClickListener {
            startActivity(Intent(this, TtsTestActivity::class.java))
        }
        openSttTestBtn.setOnClickListener {
            startActivity(Intent(this, SttTestActivity::class.java))
        }
        updateStatus()

        observeDownloadState()
    }

    private fun observeDownloadState() {
        scope.launch {
            downloads.state.collect { state ->
                when (state) {
                    is DownloadState.Progress -> {
                        isDownloading = true
                        downloadBtn.text = "Stop Download"
                        clearBtn.isEnabled = false
                        toggle.isEnabled = false
                        val p = state.progress
                        status.text = when (p.status) {
                            ModelProgress.Status.PRECHECK -> "Checking storage space…"
                            ModelProgress.Status.EXTRACTING -> "Extracting ${p.modelName}…"
                            ModelProgress.Status.VERIFYING -> "Verifying ${p.modelName} SHA256…"
                            else -> "${p.modelName} ${p.percent}%"
                        }
                    }
                    is DownloadState.Completed -> {
                        isDownloading = false
                        downloadBtn.text = "Download Selected"
                        clearBtn.isEnabled = true
                        toggle.isEnabled = true
                        updateStatus()
                    }
                    is DownloadState.Error -> {
                        isDownloading = false
                        downloadBtn.text = "Download Selected"
                        clearBtn.isEnabled = true
                        toggle.isEnabled = true
                        status.text = "Download failed: ${state.message}"
                    }
                    DownloadState.Idle -> {
                        isDownloading = false
                        downloadBtn.text = "Download Selected"
                        clearBtn.isEnabled = true
                        toggle.isEnabled = true
                        updateStatus()
                    }
                }
            }
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        val title = TextView(this).apply {
            text = "Speech-to-Speech Engine"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }

        openTtsTestBtn = Button(this).apply { text = "🔊 Test TTS Voice" }
        openSttTestBtn = Button(this).apply { text = "🎙️ Test STT Model" }

        val navParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = 2
            marginEnd = 2
        }
        navRow.addView(openTtsTestBtn, navParams)
        navRow.addView(openSttTestBtn, navParams)
        root.addView(navRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val selectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }

        vadSpinner = Spinner(this)
        sttSpinner = Spinner(this)
        ttsSpinner = Spinner(this)
        llmSpinner = Spinner(this)
        voiceSpinner = Spinner(this)

        selectionBox.addView(createLabel("VAD Model:"))
        selectionBox.addView(vadSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        selectionBox.addView(createLabel("STT Model:"))
        selectionBox.addView(sttSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        selectionBox.addView(createLabel("TTS Model:"))
        selectionBox.addView(ttsSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        selectionBox.addView(createLabel("LLM Model:"))
        selectionBox.addView(llmSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        selectionBox.addView(createLabel("TTS Voice / Speaker:"))
        selectionBox.addView(voiceSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(selectionBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        downloadBtn = Button(this).apply { text = "Download Selected" }
        clearBtn = Button(this).apply { text = "Clear Models" }

        val rowParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = 4
            marginEnd = 4
        }
        btnRow.addView(downloadBtn, rowParams)
        btnRow.addView(clearBtn, rowParams)
        root.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        toggle = Button(this).apply { text = "Start Engine" }
        root.addView(
            toggle,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 },
        )

        transcript = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.BOTTOM
            movementMethod = ScrollingMovementMethod()
        }
        val scroller = ScrollView(this).apply { addView(transcript) }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 12 },
        )
        return root
    }

    private fun createLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 4, 0, 2)
    }

    private fun setupSpinners() {
        vadSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_VAD_OPTIONS.map { it.name },
        )
        vadSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVad = ModelRegistry.ALL_VAD_OPTIONS[position]
                onModelSelectionChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sttSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_STT_OPTIONS.map { it.name },
        )
        sttSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedStt = ModelRegistry.ALL_STT_OPTIONS[position]
                onModelSelectionChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ttsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_TTS_OPTIONS.map { it.name },
        )
        ttsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTts = ModelRegistry.ALL_TTS_OPTIONS[position]
                onModelSelectionChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        llmSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_LLM_OPTIONS.map { it.name },
        )
        llmSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLlm = ModelRegistry.ALL_LLM_OPTIONS[position]
                onModelSelectionChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun onModelSelectionChanged() {
        updateStatus()
        if (running) {
            // Must release, not stop: onToggle builds a whole new engine, and the
            // old one holds the process-global llama.cpp runtime until released.
            releaseEngine()
            toggle.text = "Start Engine"
            onToggle()
        }
    }

    /** Frees the current engine's models. A stopped-but-unreleased engine still owns them. */
    private fun releaseEngine() {
        engine?.release()
        engine = null
        running = false
    }

    private fun getSelectedStack(): List<ModelSpec> = listOf(
        selectedVad,
        selectedStt,
        selectedTts,
        selectedLlm,
    )

    private fun onDownloadOrStop() {
        if (isDownloading) {
            downloads.stop()
            status.text = "Stopping download…"
            return
        }

        val stack = getSelectedStack()
        if (downloader.missing(stack).isEmpty()) {
            status.text = "Selected models already installed!"
            return
        }
        downloads.start(stack)
    }

    private fun onClearModels() {
        status.text = "Clearing models…"
        scope.launch {
            downloader.clearAll()
            status.text = "All models cleared from device."
            updateStatus()
        }
    }

    private fun updateStatus(): String {
        val stack = getSelectedStack()
        val missing = downloader.missing(stack)
        val msg = if (missing.isEmpty()) {
            "All selected models READY!"
        } else {
            "Missing ${missing.size} model(s): ${missing.joinToString(", ") { it.name }}"
        }
        status.text = msg
        return msg
    }

    private fun onToggle() {
        if (running) {
            releaseEngine()
            toggle.text = "Start Engine"
            status.text = "Engine stopped"
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
            return
        }

        val stack = getSelectedStack()
        val missing = downloader.missing(stack)
        if (missing.isNotEmpty()) {
            status.text = "Missing: ${missing.joinToString(", ") { it.name }}\nTap 'Download Selected' first."
            return
        }

        toggle.isEnabled = false
        status.text = "Initializing speech pipeline…"

        scope.launch {
            val config = ModelConfigFactory.create(
                modelsDir(),
                selectedVad,
                selectedStt,
                selectedTts,
                selectedLlm,
            )
            // No withContext here: initialize() suspends onto Dispatchers.IO itself.
            val loaded = try {
                val e = S2SEngine(this@MainActivity, config)
                if (e.initialize().isFailure) null else e.also { it.start() }
            } catch (ex: Throwable) {
                Log.e("MainActivity", "Engine init failed", ex)
                null
            }

            toggle.isEnabled = true
            if (loaded != null) {
                engine = loaded
                collectEvents(loaded)
                running = true
                toggle.text = "Stop Engine"
                updateVoicesList()
            } else {
                status.text = "Failed to initialize pipeline (check logcat S2S*)"
            }
        }
    }

    private fun updateVoicesList() {
        val e = engine ?: return
        val voiceList = e.voices
        if (voiceList.isNotEmpty()) {
            voiceSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                voiceList.map { it.name },
            )
            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    e.selectVoice(voiceList[position].id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
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
                        status.text = "TTFT: ${event.metrics.timeToFirstTokenMs}ms · " +
                            "TTFA: ${event.metrics.timeToFirstAudioMs}ms"

                    is S2SEvent.ToolExecuted -> transcript.append("[tool ${event.name}] ${event.output}\n")
                    is S2SEvent.Error -> status.text = "Error: ${event.message}"
                }
            }
        }
    }

    private fun replacePartial(text: String) {
        val current = transcript.text.toString()
        val base = if (partialShown) current.substringBeforeLast("You: ", "") else current
        transcript.text = base + text
    }

    private fun modelsDir() = S2SModels.dir(this)

    override fun onDestroy() {
        super.onDestroy()
        downloads.close()
        scope.cancel()
        releaseEngine()
    }

    private companion object {
        const val REQ_PERMS = 1
    }
}
