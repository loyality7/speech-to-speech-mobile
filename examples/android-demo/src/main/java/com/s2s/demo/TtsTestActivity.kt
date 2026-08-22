package com.s2s.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
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
import com.s2s.mobile.S2SStages
import com.s2s.mobile.audio.SpeakerOutput
import com.s2s.mobile.model.ModelDownloads
import com.s2s.mobile.model.DownloadState
import com.s2s.mobile.model.ModelProgress
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.S2SModels
import com.s2s.mobile.tts.SherpaSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Dedicated Activity to test and preview TTS voice models independently, including Kokoro multi-speaker voice selection.
 */
class TtsTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ttsSpinner: Spinner
    private lateinit var speakerSpinner: Spinner
    private lateinit var speakerLabel: TextView
    private lateinit var textInput: EditText
    private lateinit var synthesizeBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var statusText: TextView
    private lateinit var consoleLog: TextView

    private val downloader by lazy { S2SModels.downloader(this) }

    private val downloads by lazy { ModelDownloads(this) }
    private var isDownloading = false
    @Volatile private var isSynthesizing = false
    private var activeSpeaker: SpeakerOutput? = null
    private var selectedSpeakerId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        observeDownloadState()
    }

    private fun observeDownloadState() {
        scope.launch {
            downloads.state.collect { state ->
                when (state) {
                    is DownloadState.Progress -> {
                        isDownloading = true
                        downloadBtn.text = "Stop Download"
                        synthesizeBtn.isEnabled = false
                        val p = state.progress
                        val msg = when (p.status) {
                            ModelProgress.Status.PRECHECK -> "Checking storage space…"
                            ModelProgress.Status.EXTRACTING -> "Extracting ${p.modelName}…"
                            ModelProgress.Status.VERIFYING -> "Verifying ${p.modelName} SHA256…"
                            else -> "Downloading ${p.modelName} ${p.percent}%"
                        }
                        statusText.text = msg
                        appendLog("DOWNLOAD PROGRESS: $msg")
                    }
                    is DownloadState.Completed -> {
                        isDownloading = false
                        downloadBtn.text = "Download Model"
                        synthesizeBtn.isEnabled = true
                        updateStatus()
                        appendLog("DOWNLOAD COMPLETED SUCCESSFULLY!")
                    }
                    is DownloadState.Error -> {
                        isDownloading = false
                        downloadBtn.text = "Download Model"
                        synthesizeBtn.isEnabled = true
                        statusText.text = "Download failed: ${state.message}"
                        appendLog("DOWNLOAD ERROR: ${state.message}")
                    }
                    DownloadState.Idle -> {
                        isDownloading = false
                        downloadBtn.text = "Download Model"
                        synthesizeBtn.isEnabled = true
                        updateStatus()
                    }
                }
            }
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "🔊 Standalone TTS Voice Tester"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(createLabel("Select TTS Model:"))
        ttsSpinner = Spinner(this)
        root.addView(ttsSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        speakerLabel = createLabel("Select Voice / Speaker Persona (Multi-Voice Models):")
        speakerSpinner = Spinner(this)
        root.addView(speakerLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(speakerSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 8)
        }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        downloadBtn = Button(this).apply { text = "Download Model" }
        synthesizeBtn = Button(this).apply { text = "Synthesize & Play Voice" }

        val rowParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = 4
            marginEnd = 4
        }
        btnRow.addView(downloadBtn, rowParams)
        btnRow.addView(synthesizeBtn, rowParams)
        root.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(createLabel("Test Speech Input:"))
        textInput = EditText(this).apply {
            setText("Hello! This is a multi-voice synthesis test with Kokoro and Piper.")
            textSize = 14f
        }
        root.addView(textInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        consoleLog = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            text = "[RAW DEBUG LOG] TTS Console Initialized.\n"
        }
        val scroller = ScrollView(this).apply { addView(consoleLog) }
        root.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 16 })

        ttsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_TTS_OPTIONS.map { it.name },
        )
        ttsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateStatus()
                setupSpeakerSpinnerForSpec(ModelRegistry.ALL_TTS_OPTIONS[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        speakerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSpeakerId = position
                appendLog("Selected Speaker ID: $selectedSpeakerId")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        downloadBtn.setOnClickListener { onDownloadOrStop() }
        synthesizeBtn.setOnClickListener { onSynthesize() }

        updateStatus()
        setupSpeakerSpinnerForSpec(ModelRegistry.ALL_TTS_OPTIONS[0])
        return root
    }

    private fun setupSpeakerSpinnerForSpec(spec: com.s2s.mobile.model.ModelSpec) {
        if (spec.backend == "KOKORO") {
            val kokoroVoices = (0..50).map { id ->
                val label = when (id) {
                    0 -> "Speaker 0 (af_sarah - American Female)"
                    1 -> "Speaker 1 (af_bella - American Female)"
                    2 -> "Speaker 2 (af_nicole - American Female)"
                    3 -> "Speaker 3 (af_sky - American Female)"
                    4 -> "Speaker 4 (am_adam - American Male)"
                    5 -> "Speaker 5 (am_michael - American Male)"
                    6 -> "Speaker 6 (bf_emma - British Female)"
                    7 -> "Speaker 7 (bm_george - British Male)"
                    else -> "Speaker $id (Persona #$id)"
                }
                label
            }
            speakerSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                kokoroVoices,
            )
            speakerLabel.visibility = View.VISIBLE
            speakerSpinner.visibility = View.VISIBLE
        } else {
            speakerSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Default Single Voice (Speaker 0)"),
            )
            speakerLabel.visibility = View.GONE
            speakerSpinner.visibility = View.GONE
        }
        selectedSpeakerId = 0
    }

    private fun updateStatus() {
        val spec = ModelRegistry.ALL_TTS_OPTIONS[ttsSpinner.selectedItemPosition]
        val isPresent = downloader.present(spec)
        val dir = File(modelsDir(), spec.targetPath)

        statusText.text = if (isPresent) {
            "Status: READY | ${spec.name}\nPath: ${dir.absolutePath}"
        } else {
            "Status: MISSING | Tap 'Download Model' to download ${spec.name}"
        }
    }

    private fun onDownloadOrStop() {
        if (isDownloading) {
            downloads.stop()
            statusText.text = "Stopping download…"
            return
        }

        val spec = ModelRegistry.ALL_TTS_OPTIONS[ttsSpinner.selectedItemPosition]
        if (downloader.present(spec)) {
            statusText.text = "${spec.name} is already downloaded!"
            return
        }

        downloads.start(listOf(spec))
        appendLog("Triggered download for model: ${spec.name}")
    }

    private fun appendLog(msg: String) {
        Log.d("TtsTestActivity", msg)
        scope.launch(Dispatchers.Main) {
            consoleLog.append("[$msg]\n")
        }
    }

    private fun onSynthesize() {
        if (isSynthesizing) {
            appendLog("User requested synthesis stop.")
            isSynthesizing = false
            synthesizeBtn.text = "Synthesize & Play Voice"
            activeSpeaker?.flush()
            activeSpeaker?.release()
            activeSpeaker = null
            return
        }

        val spec = ModelRegistry.ALL_TTS_OPTIONS[ttsSpinner.selectedItemPosition]
        if (!downloader.present(spec)) {
            statusText.text = "Cannot test: ${spec.name} is missing. Tap 'Download Model'."
            appendLog("ERROR: Model missing on disk: ${spec.targetPath}")
            return
        }

        val input = textInput.text.toString().trim()
        if (input.isEmpty()) {
            appendLog("WARN: Text input is empty.")
            return
        }

        isSynthesizing = true
        synthesizeBtn.text = "Stop Synthesis"
        appendLog("==========================================")
        appendLog("START SYNTHESIS TASK")
        appendLog("Model: ${spec.name} (Backend: ${spec.backend}, Speaker ID: $selectedSpeakerId)")
        appendLog("Input text: \"$input\"")

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    appendLog("Model dir path: ${File(modelsDir(), spec.targetPath).absolutePath}")

                    // Built exactly the way the engine builds it. Previously this
                    // screen used its own chunk floors (8/6 against the engine's
                    // 10/10), so a voice could pass here and stutter in a real turn.
                    val synth = S2SStages.synthesizer(this@TtsTestActivity, spec, selectedSpeakerId)
                    val initRes = synth.initialize()

                    if (initRes.isSuccess) {
                        synth.selectVoice(selectedSpeakerId)
                        appendLog("SUCCESS: SherpaSynthesizer initialized for ${spec.backend}!")
                        appendLog("Selected Voice Speaker ID: $selectedSpeakerId | Sample Rate: ${synth.sampleRate} Hz | Total Voices: ${synth.voices.size}")

                        val speaker = SpeakerOutput(this@TtsTestActivity, synth.sampleRate)
                        activeSpeaker = speaker
                        speaker.start()

                        val startTime = System.currentTimeMillis()
                        var totalSamples = 0L
                        var chunkCount = 0

                        synth.synthesize(input, keepGoing = { isSynthesizing }) { pcm ->
                            chunkCount++
                            totalSamples += pcm.size
                            speaker.write(pcm)
                        }

                        val durationMs = System.currentTimeMillis() - startTime
                        val audioMs = totalSamples * 1000L / maxOf(1, synth.sampleRate)
                        val rtf = durationMs / maxOf(1.0, audioMs.toDouble())

                        appendLog("SYNTHESIS COMPLETE: $chunkCount chunks, $totalSamples samples (${audioMs}ms audio) in ${durationMs}ms (RTF ${"%.3f".format(rtf)})")

                        while (speaker.hasPending() && isSynthesizing) {
                            Thread.sleep(50)
                        }

                        speaker.release()
                        activeSpeaker = null
                        synth.release()
                        appendLog("Playback completed!")
                    } else {
                        val err = initRes.exceptionOrNull()
                        appendLog("ERROR: SherpaSynthesizer initialize failed!")
                        err?.let { appendLog(getStackTraceString(it)) }
                    }
                } catch (e: Throwable) {
                    appendLog("FATAL EXCEPTION in synthesis coroutine:")
                    appendLog(getStackTraceString(e))
                }
            }

            isSynthesizing = false
            synthesizeBtn.text = "Synthesize & Play Voice"
        }
    }

    private fun getStackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun createLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 8, 0, 2)
    }

    private fun modelsDir() = S2SModels.dir(this)

    override fun onDestroy() {
        super.onDestroy()
        downloads.close()
        isSynthesizing = false
        activeSpeaker?.flush()
        activeSpeaker?.release()
        activeSpeaker = null
        scope.cancel()
    }
}
