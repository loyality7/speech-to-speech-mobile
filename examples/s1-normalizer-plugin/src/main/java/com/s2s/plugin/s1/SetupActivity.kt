package com.s2s.plugin.s1

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The plugin's own setup screen: download the model, check status, try a
 * normalization, remove the model.
 *
 * The plugin owns its model, so it owns the UI for managing it. The host
 * shows the plugin in its Plugins list and can enable/select it, but it has
 * no idea this model exists or how large it is — asking the host to
 * download files on a plugin's behalf would mean the host knowing about
 * every plugin's assets.
 *
 * A test box is included because "is the model working?" is otherwise only
 * answerable by speaking to the assistant and guessing from the result.
 */
class SetupActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var status: TextView
    private lateinit var downloadBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var testInput: EditText
    private lateinit var testBtn: Button
    private lateinit var log: TextView

    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(
            TextView(this).apply {
                text = "\"S1-mini\" by \"Superwhisper\""
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "Speech-to-text transcript normalizer. Cleans raw dictation into written " +
                    "text: punctuation, numbers, filler words and self-corrections. English only."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 6, 0, 16)
            },
        )

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
        }
        root.addView(status)

        downloadBtn = Button(this).apply { text = "Download model (about 462 MB)" }
        downloadBtn.setOnClickListener { onDownloadOrCancel() }
        root.addView(downloadBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })

        deleteBtn = Button(this).apply { text = "Delete model" }
        deleteBtn.setOnClickListener { onDelete() }
        root.addView(deleteBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(
            TextView(this).apply {
                text = "Try it"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(0, 24, 0, 4)
            },
        )
        testInput = EditText(this).apply {
            setText("so um i need to like send the the report by uh friday no wait make that thursday")
            textSize = 14f
        }
        root.addView(testInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        testBtn = Button(this).apply { text = "Normalize" }
        testBtn.setOnClickListener { onTest() }
        root.addView(testBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        log = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setPadding(0, 16, 0, 0)
        }
        root.addView(log)

        root.addView(
            TextView(this).apply {
                text = "\n\nLicense: Apache 2.0, with the model's naming requirement — this model " +
                    "keeps the name \"S1-mini\" by \"Superwhisper\" wherever it is used.\n" +
                    "Model: superwhisper/s1-mini-GGUF (Q4_K_M), based on Qwen/Qwen3-0.6B."
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 24, 0, 0)
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshStatus() {
        val present = ModelDownload.isPresent(filesDir)
        val size = ModelDownload.target(filesDir).takeIf { it.isFile }?.length() ?: 0L
        status.text = if (present) {
            "Model installed (${size / 1_048_576} MB).\nThis plugin is ready — enable it in the assistant's Plugins screen."
        } else {
            "Model not installed. Download it, then enable this plugin in the assistant's Plugins screen."
        }
        downloadBtn.isEnabled = !present || downloading
        deleteBtn.isEnabled = present && !downloading
        testBtn.isEnabled = present && !downloading
    }

    private fun onDownloadOrCancel() {
        if (downloading) {
            downloading = false
            downloadBtn.text = "Download model (about 462 MB)"
            appendLog("Cancelled. Partial download kept — downloading again resumes.")
            return
        }

        downloading = true
        downloadBtn.text = "Cancel"
        deleteBtn.isEnabled = false
        appendLog("Downloading from Hugging Face…")

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelDownload.download(
                    filesDir = filesDir,
                    onProgress = { done, total ->
                        val percent = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt() }
                        scope.launch {
                            status.text = if (percent != null) {
                                "Downloading… $percent% (${done / 1_048_576} MB)"
                            } else {
                                "Downloading… ${done / 1_048_576} MB"
                            }
                        }
                    },
                    keepGoing = { downloading },
                )
            }
            downloading = false
            downloadBtn.text = "Download model (about 462 MB)"
            result
                .onSuccess { appendLog("Model ready.") }
                .onFailure { appendLog("Download failed: ${it.message}") }
            refreshStatus()
        }
    }

    private fun onDelete() {
        ModelDownload.delete(filesDir)
        appendLog("Model deleted.")
        refreshStatus()
    }

    /**
     * Runs the real model in THIS process, through the same protocol the
     * service uses — so a pass here means the actual integration works, not
     * just that a string was formatted.
     */
    private fun onTest() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) return
        testBtn.isEnabled = false
        appendLog("\nInput:  $text")

        scope.launch {
            val output = withContext(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val result = S1MiniLocalTest.normalize(this@SetupActivity, text)
                result to (System.currentTimeMillis() - started)
            }
            appendLog("Output: ${output.first}")
            appendLog("Took ${output.second} ms (includes model load if this was the first run)")
            testBtn.isEnabled = true
        }
    }

    private fun appendLog(line: String) {
        log.append("$line\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        downloading = false
        scope.cancel()
    }
}
