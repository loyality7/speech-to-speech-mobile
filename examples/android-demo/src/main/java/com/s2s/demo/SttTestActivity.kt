package com.s2s.demo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.s2s.mobile.S2SStages
import com.s2s.mobile.audio.MicrophoneInput
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.model.ModelDownloads
import com.s2s.mobile.model.DownloadState
import com.s2s.mobile.model.ModelDownloader
import com.s2s.mobile.model.ModelProgress
import com.s2s.mobile.model.ModelRegistry
import com.s2s.mobile.model.S2SModels
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.Transcript
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
 * Dedicated Activity to test and preview STT speech-to-text models independently with direct download support.
 */
class SttTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var sttSpinner: Spinner
    private lateinit var recognizeBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var statusText: TextView
    private lateinit var consoleLog: TextView

    private val downloader by lazy { ModelDownloader(modelsDir()) }

    private val downloads by lazy { ModelDownloads(this) }
    private var isDownloading = false
    @Volatile private var isRecognizing = false
    private var activeMic: MicrophoneInput? = null
    private var activeRecognizer: SpeechRecognizer? = null

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
                        recognizeBtn.isEnabled = false
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
                        recognizeBtn.isEnabled = true
                        updateStatus()
                        appendLog("DOWNLOAD COMPLETED SUCCESSFULLY!")
                    }
                    is DownloadState.Error -> {
                        isDownloading = false
                        downloadBtn.text = "Download Model"
                        recognizeBtn.isEnabled = true
                        statusText.text = "Download failed: ${state.message}"
                        appendLog("DOWNLOAD ERROR: ${state.message}")
                    }
                    DownloadState.Idle -> {
                        isDownloading = false
                        downloadBtn.text = "Download Model"
                        recognizeBtn.isEnabled = true
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
            text = "🎙️ Standalone STT Model Tester"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(createLabel("Select STT Model to Test:"))
        sttSpinner = Spinner(this)
        root.addView(sttSpinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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
        recognizeBtn = Button(this).apply { text = "Start Voice Recognition" }

        val rowParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = 4
            marginEnd = 4
        }
        btnRow.addView(downloadBtn, rowParams)
        btnRow.addView(recognizeBtn, rowParams)
        root.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        consoleLog = TextView(this).apply {
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            text = "[RAW DEBUG LOG] STT Console Initialized.\n"
        }
        val scroller = ScrollView(this).apply { addView(consoleLog) }
        root.addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 16 })

        sttSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ModelRegistry.ALL_STT_OPTIONS.map { it.name },
        )
        sttSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        downloadBtn.setOnClickListener { onDownloadOrStop() }
        recognizeBtn.setOnClickListener { onRecognize() }

        updateStatus()
        return root
    }

    private fun updateStatus() {
        val spec = ModelRegistry.ALL_STT_OPTIONS[sttSpinner.selectedItemPosition]
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

        val spec = ModelRegistry.ALL_STT_OPTIONS[sttSpinner.selectedItemPosition]
        if (downloader.present(spec)) {
            statusText.text = "${spec.name} is already downloaded!"
            return
        }

        downloads.start(listOf(spec))
        appendLog("Triggered download for STT model: ${spec.name}")
    }

    private fun appendLog(msg: String) {
        Log.d("SttTestActivity", msg)
        scope.launch(Dispatchers.Main) {
            consoleLog.append("[$msg]\n")
        }
    }

    private fun onRecognize() {
        if (isRecognizing) {
            appendLog("User requested recognition stop.")
            isRecognizing = false
            recognizeBtn.text = "Start Voice Recognition"
            activeMic?.stop()
            activeMic = null
            activeRecognizer?.release()
            activeRecognizer = null
            return
        }

        val spec = ModelRegistry.ALL_STT_OPTIONS[sttSpinner.selectedItemPosition]
        if (!downloader.present(spec)) {
            statusText.text = "Cannot test: ${spec.name} is missing. Tap 'Download Model'."
            appendLog("ERROR: Model missing on disk: ${spec.targetPath}")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appendLog("Requesting RECORD_AUDIO permission...")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
            return
        }

        isRecognizing = true
        recognizeBtn.text = "Stop Recognition"
        appendLog("==========================================")
        appendLog("START STT RECOGNITION TASK")
        appendLog("Model: ${spec.name} (Backend: ${spec.backend}, Path: ${spec.targetPath})")

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    appendLog("Model dir path: ${File(modelsDir(), spec.targetPath).absolutePath}")

                    val audioConfig = AudioConfig()
                    // Same construction rules the engine uses — backend choice,
                    // model dir and VAD path all come from the SDK, so this screen
                    // cannot drift from what the real pipeline runs.
                    val recognizer = S2SStages.recognizer(
                        this@SttTestActivity,
                        spec,
                        audio = audioConfig,
                    )

                    val initRes = recognizer.initialize()

                    if (initRes.isSuccess) {
                        activeRecognizer = recognizer
                        appendLog("SUCCESS: STT Recognizer initialized!")

                        val mic = MicrophoneInput(audioConfig)
                        activeMic = mic
                        var frameCounter = 0
                        val started = mic.start { pcmFrame ->
                            if (!isRecognizing) return@start
                            frameCounter++
                            val res = recognizer.accept(pcmFrame)
                            when (res) {
                                is Transcript.Partial -> {
                                    appendLog("  [PARTIAL]: ${res.text}")
                                }
                                is Transcript.Final -> {
                                    appendLog(">>> [FINAL TRANSCRIPT]: ${res.text} <<<")
                                }
                                Transcript.Nothing -> {}
                            }
                        }

                        if (started) {
                            appendLog("Microphone recording ACTIVE! Speak into the mic...")
                        } else {
                            appendLog("ERROR: Failed to open microphone recording stream!")
                        }
                    } else {
                        val err = initRes.exceptionOrNull()
                        appendLog("ERROR: STT Recognizer initialize failed!")
                        err?.let { appendLog(getStackTraceString(it)) }
                    }
                } catch (e: Throwable) {
                    appendLog("FATAL EXCEPTION in STT coroutine:")
                    appendLog(getStackTraceString(e))
                }
            }
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
        isRecognizing = false
        activeMic?.stop()
        activeMic = null
        activeRecognizer?.release()
        activeRecognizer = null
        scope.cancel()
    }

    private companion object {
        const val REQ_PERMS = 101
    }
}
