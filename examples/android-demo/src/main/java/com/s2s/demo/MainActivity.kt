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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.S2SEvent
import com.s2s.mobile.config.ModelConfigFactory
import com.s2s.mobile.llm.LlamaConfig
import com.s2s.mobile.llm.LlamaLanguageModel
import com.s2s.mobile.model.ModelDownloads
import com.s2s.mobile.model.DownloadState
import com.s2s.mobile.model.HuggingFaceDownloader
import com.s2s.mobile.model.ModelProgress
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.S2SModels
import com.s2s.mobile.model.ModelSource
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

    private var hfBrowseButtons: List<Button> = emptyList()

    private var selectedVad: ModelSpec = ModelRegistry.DEFAULT_VAD
    private var selectedStt: ModelSpec = ModelRegistry.DEFAULT_STT
    private var selectedTts: ModelSpec = ModelRegistry.DEFAULT_TTS
    private var selectedLlm: ModelSpec = ModelRegistry.DEFAULT_LLM

    private val downloads by lazy { ModelDownloads(this) }
    private var isDownloading = false

    private var engine: S2SEngine? = null
    private var running = false
    private var partialShown = false
    private val downloader by lazy { S2SModels.downloader(this) }

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
        restorePersistedCustomModels()
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

        root.addView(label("Hugging Face Token (for gated repos, e.g. Gemma):", padTop = 4))
        val tokenRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tokenInput = EditText(this).apply {
            hint = "hf_..."
            setText(S2SModels.huggingFaceToken(this@MainActivity).orEmpty())
        }
        val saveTokenBtn = Button(this).apply { text = "Save" }
        saveTokenBtn.setOnClickListener {
            S2SModels.setHuggingFaceToken(this, tokenInput.text?.toString())
            status.text = "Hugging Face token saved."
        }
        tokenRow.addView(tokenInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tokenRow.addView(saveTokenBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(tokenRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val selectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }

        vadSpinner = Spinner(this)
        sttSpinner = Spinner(this)
        ttsSpinner = Spinner(this)
        llmSpinner = Spinner(this)
        voiceSpinner = Spinner(this)

        val browseButtons = mutableListOf<Button>()
        fun addModelRow(label: String, spinner: Spinner, category: String) {
            selectionBox.addView(label(label, padTop = 4))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(spinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val browseBtn = Button(this).apply { text = "🔍 HF" }
            browseBtn.setOnClickListener {
                val intent = Intent(this, HuggingFaceBrowserActivity::class.java)
                    .putExtra(HuggingFaceBrowserActivity.EXTRA_CATEGORY, category)
                startActivityForResult(intent, REQ_HF_BROWSE)
            }
            browseButtons.add(browseBtn)
            row.addView(browseBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            selectionBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        addModelRow("VAD Model:", vadSpinner, "VAD")
        addModelRow("STT Model:", sttSpinner, "STT")
        addModelRow("TTS Model:", ttsSpinner, "TTS")
        addModelRow("LLM Model:", llmSpinner, "LLM")
        selectionBox.addView(label("TTS Voice / Speaker:", padTop = 4))
        selectionBox.addView(voiceSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        hfBrowseButtons = browseButtons

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

    // Mutable per-category option lists. Start as the curated registry; a Hugging
    // Face pick is appended here (see addAndSelect) so it actually shows up in the
    // spinner instead of only living in selectedXxx — otherwise it's invisible in
    // the dropdown, and touching the spinner again would silently discard it.
    private val vadOptions = ModelRegistry.ALL_VAD_OPTIONS.toMutableList()
    private val sttOptions = ModelRegistry.ALL_STT_OPTIONS.toMutableList()
    private val ttsOptions = ModelRegistry.ALL_TTS_OPTIONS.toMutableList()
    private val llmOptions = ModelRegistry.ALL_LLM_OPTIONS.toMutableList()

    private fun setupSpinners() {
        bindSpinner(vadSpinner, vadOptions) { selectedVad = it }
        bindSpinner(sttSpinner, sttOptions) { selectedStt = it }
        bindSpinner(ttsSpinner, ttsOptions) { selectedTts = it }
        bindSpinner(llmSpinner, llmOptions) { selectedLlm = it }
    }

    private fun bindSpinner(spinner: Spinner, options: MutableList<ModelSpec>, onSelected: (ModelSpec) -> Unit) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.name },
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(options[position])
                onModelSelectionChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Appends a dynamically-resolved spec (Hugging Face pick) to a category's
     * option list, refreshes that spinner, and selects the new entry. Persisted to
     * SharedPreferences so the pick survives process death — otherwise a Hugging
     * Face model you already downloaded vanishes from the dropdown on next launch
     * and you'd have to re-search for it (the files themselves stay on disk either
     * way; this is only about the SDK remembering which one you picked). */
    private fun addAndSelect(spinner: Spinner, options: MutableList<ModelSpec>, spec: ModelSpec) {
        options.removeAll { it.id == spec.id }
        options.add(spec)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.name },
        )
        spinner.setSelection(options.size - 1)
        if (spec.source == ModelSource.HUGGING_FACE) persistCustomModel(spec)
    }

    private fun customModelPrefs() = getSharedPreferences("hf_custom_models", MODE_PRIVATE)

    private fun persistCustomModel(spec: ModelSpec) {
        customModelPrefs().edit().putString(spec.category, spec.toJson().toString()).apply()
    }

    private fun loadPersistedCustomModel(category: String): ModelSpec? {
        val json = customModelPrefs().getString(category, null) ?: return null
        return try {
            ModelSpec.fromJson(org.json.JSONObject(json))
        } catch (ex: Exception) {
            Log.e("MainActivity", "Failed to restore persisted $category model", ex)
            null
        }
    }

    /** Re-applies whatever custom model was picked last session, per category. Must
     * run after setupSpinners() — addAndSelect needs the listener already bound so
     * selecting the restored entry actually updates selectedXxx. */
    private fun restorePersistedCustomModels() {
        loadPersistedCustomModel("VAD")?.let { addAndSelect(vadSpinner, vadOptions, it) }
        loadPersistedCustomModel("STT")?.let { addAndSelect(sttSpinner, sttOptions, it) }
        loadPersistedCustomModel("TTS")?.let { addAndSelect(ttsSpinner, ttsOptions, it) }
        loadPersistedCustomModel("LLM")?.let { addAndSelect(llmSpinner, llmOptions, it) }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_HF_BROWSE || resultCode != RESULT_OK || data == null) return

        val category = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_CATEGORY) ?: "LLM"
        val multiFiles = data.getBundleExtra(HuggingFaceBrowserActivity.EXTRA_MULTI_FILES)
        if (multiFiles != null) {
            val repo = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_REPO) ?: return
            val targetDirName = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_TARGET_DIR_NAME) ?: return
            val backend = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_BACKEND)
            val displayName = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_DISPLAY_NAME) ?: repo
            val approxBytes = data.getLongExtra(HuggingFaceBrowserActivity.EXTRA_APPROX_BYTES, 0L)
            val files = multiFiles.keySet().associateWith { multiFiles.getString(it)!! }
            selectMultiFileHuggingFaceModel(repo, targetDirName, files, approxBytes, backend, displayName, category)
            return
        }

        val repo = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_REPO) ?: return
        val filename = data.getStringExtra(HuggingFaceBrowserActivity.EXTRA_FILENAME) ?: return
        resolveHuggingFaceSelection(repo, filename, category)
    }

    private fun selectMultiFileHuggingFaceModel(
        repo: String,
        targetDirName: String,
        files: Map<String, String>,
        approxBytes: Long,
        backend: String?,
        displayName: String,
        category: String,
    ) {
        val spec = HuggingFaceDownloader.createMultiFileModelSpec(
            id = "hf_multi_${targetDirName.hashCode()}",
            name = displayName,
            category = category,
            repo = repo,
            targetDirName = targetDirName,
            files = files,
            approxBytes = approxBytes,
            backend = backend,
        )
        when (category) {
            "VAD" -> addAndSelect(vadSpinner, vadOptions, spec)
            "STT" -> addAndSelect(sttSpinner, sttOptions, spec)
            "TTS" -> addAndSelect(ttsSpinner, ttsOptions, spec)
            else -> addAndSelect(llmSpinner, llmOptions, spec)
        }
        status.text = "Selected $category: ${spec.name} (${files.size} files, no sha256 — byte-count check only)"
    }

    /**
     * Resolves a repo+filename picked in [HuggingFaceBrowserActivity] into a
     * [ModelSpec] and selects it for the matching stage. Pulls the file's size and
     * (when the file is an LFS object) sha256 from the repo listing so the resulting
     * spec gets the same hard-fail integrity check as a curated registry entry.
     */
    private fun resolveHuggingFaceSelection(repo: String, filename: String, category: String) {
        hfBrowseButtons.forEach { it.isEnabled = false }
        status.text = "Resolving $filename from $repo…"
        scope.launch {
            val spec = try {
                val files = HuggingFaceDownloader.fetchRepositoryFiles(repo)
                val match = files.firstOrNull { it.path == filename }
                if (match != null) {
                    HuggingFaceDownloader.createModelSpec(
                        id = "hf_custom_${filename.hashCode()}",
                        name = "$repo / $filename",
                        category = category,
                        repo = repo,
                        file = match,
                    )
                } else {
                    HuggingFaceDownloader.createModelSpec(
                        id = "hf_custom_${filename.hashCode()}",
                        name = "$repo / $filename",
                        category = category,
                        repo = repo,
                        filename = filename,
                        approxBytes = 0L,
                    )
                }
            } catch (ex: Throwable) {
                Log.e("MainActivity", "Failed to resolve Hugging Face model", ex)
                null
            }

            hfBrowseButtons.forEach { it.isEnabled = true }
            if (spec == null) {
                status.text = "Failed to resolve $filename from $repo (check logcat)."
                return@launch
            }

            when (category) {
                "VAD" -> addAndSelect(vadSpinner, vadOptions, spec)
                "STT" -> addAndSelect(sttSpinner, sttOptions, spec)
                "TTS" -> addAndSelect(ttsSpinner, ttsOptions, spec)
                else -> addAndSelect(llmSpinner, llmOptions, spec)
            }
            val integrityNote = if (spec.sha256 != null) "sha256 verified" else "no sha256 — Content-Length only"
            status.text = "Selected $category: ${spec.name} ($integrityNote)"
        }
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
                val languageModel = LlamaLanguageModel(LlamaConfig(), config.models.llmModel)
                val e = S2SEngine(this@MainActivity, config, languageModel = languageModel)
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
                    S2SEvent.SpeechStarted -> Unit
                    S2SEvent.SpeechEnded -> Unit
                    is S2SEvent.Metrics ->
                        status.text = "TTFT: ${event.metrics.timeToFirstTokenMs}ms · " +
                            "TTFA: ${event.metrics.timeToFirstAudioMs}ms"

                    is S2SEvent.ToolExecuted -> transcript.append("[tool ${event.name}] ${event.output}\n")

                    is S2SEvent.AudioFocusLost -> if (event.willResume) {
                        status.text = "Paused — something else is using the audio"
                    } else {
                        // The engine has already stopped itself, so the button has
                        // to follow or it lies about the state.
                        status.text = "Stopped — audio focus lost"
                        running = false
                        toggle.text = "Start Engine"
                    }

                    S2SEvent.AudioFocusRegained -> status.text = "Listening again"

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w("MainActivity", "onTrimMemory level=$level received")
        engine?.onTrimMemory(level)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloads.close()
        scope.cancel()
        releaseEngine()
    }

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_HF_BROWSE = 2
    }
}
