package com.s2s.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import com.s2s.mobile.model.HuggingFaceDownloader.HuggingFaceFile
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import com.s2s.mobile.model.HuggingFaceDownloader
import com.s2s.mobile.model.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dedicated Hugging Face model search screen. Search is the primary flow — no raw
 * repo URL entry. Search hub repos, drill into one, pick a file matching the
 * requested category, and hand repo+filename back to the caller via activity result.
 * The caller (MainActivity) resolves the actual ModelSpec — including sha256 lookup
 * — through HuggingFaceDownloader, same as any curated model.
 */
class HuggingFaceBrowserActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var category: String
    private lateinit var queryInput: EditText
    private lateinit var searchBtn: Button
    private lateinit var quantSpinner: Spinner
    private lateinit var resultsList: ListView
    private lateinit var progress: ProgressBar
    private lateinit var emptyLabel: TextView

    private var results: List<HuggingFaceDownloader.HuggingFaceRepoInfo> = emptyList()
    private var selectedQuant: String = QUANT_ANY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = intent.getStringExtra(EXTRA_CATEGORY) ?: "LLM"
        title = "Hugging Face — $category models"
        setContentView(buildUi())

        searchBtn.setOnClickListener { runSearch() }
        queryInput.setText(defaultQueryFor(category))
        runSearch()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queryInput = EditText(this).apply { hint = "Search Hugging Face models…" }
        searchBtn = Button(this).apply { text = "Search" }
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        searchRow.addView(searchBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(searchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val localModels = ModelRegistry.ALL_MODELS.filter { it.category == category }
        val localBtn = Button(this).apply { text = "📦 Already have locally (${localModels.size})" }
        localBtn.setOnClickListener { showLocalModels(localModels) }
        root.addView(
            localBtn,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 },
        )

        if (category == "LLM") {
            val filterRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }
            filterRow.addView(
                TextView(this).apply {
                    text = "Quantization:"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
            quantSpinner = Spinner(this)
            quantSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                QUANT_OPTIONS,
            )
            quantSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedQuant = QUANT_OPTIONS[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            filterRow.addView(quantSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            root.addView(filterRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        progress = ProgressBar(this).apply { visibility = android.view.View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 16
        })

        emptyLabel = TextView(this).apply {
            text = "No results yet."
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 16)
        }
        root.addView(emptyLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        resultsList = ListView(this)
        resultsList.setOnItemClickListener { _, _, position, _ -> onRepoSelected(results[position]) }
        root.addView(resultsList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    private fun defaultQueryFor(category: String): String = when (category) {
        "LLM" -> "GGUF"
        "STT" -> "sherpa-onnx STT"
        "TTS" -> "sherpa-onnx TTS"
        "VAD" -> "VAD onnx"
        else -> category
    }

    private fun extensionsFor(category: String): List<String> = when (category) {
        "LLM" -> listOf(".gguf")
        else -> listOf(".onnx")
    }

    /** Hugging Face hub "library" tag used to bias search toward repos that actually
     * carry this format, instead of any repo whose text happens to match the query. */
    private fun libraryFilterFor(category: String): String? = when (category) {
        "LLM" -> "gguf"
        else -> null // no equivalent ONNX-speech library tag on the hub
    }

    private fun formatLabelFor(path: String): String = when {
        path.endsWith(".gguf", ignoreCase = true) -> "GGUF"
        path.endsWith(".onnx", ignoreCase = true) -> "ONNX"
        else -> path.substringAfterLast('.', "").uppercase()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    private fun runSearch() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) return

        progress.visibility = android.view.View.VISIBLE
        emptyLabel.visibility = android.view.View.GONE
        resultsList.adapter = null

        scope.launch {
            val repos = try {
                HuggingFaceDownloader.searchRepositories(query, libraryFilter = libraryFilterFor(category))
            } catch (ex: Throwable) {
                Log.e(TAG, "Hugging Face search failed for '$query'", ex)
                emptyList()
            }

            progress.visibility = android.view.View.GONE
            results = repos
            if (repos.isEmpty()) {
                emptyLabel.text = "No repositories found for \"$query\"."
                emptyLabel.visibility = android.view.View.VISIBLE
                return@launch
            }

            resultsList.adapter = ArrayAdapter(
                this@HuggingFaceBrowserActivity,
                android.R.layout.simple_list_item_1,
                repos.map { "${it.id}\n${it.downloads} downloads · ${it.likes} likes" },
            )
        }
    }

    private fun onRepoSelected(repo: HuggingFaceDownloader.HuggingFaceRepoInfo) {
        progress.visibility = android.view.View.VISIBLE
        scope.launch {
            val files = try {
                HuggingFaceDownloader.fetchRepositoryFiles(repo.id)
            } catch (ex: Throwable) {
                Log.e(TAG, "Failed to list files for ${repo.id}", ex)
                emptyList()
            }
            progress.visibility = android.view.View.GONE

            when (category) {
                "TTS" -> handleTtsRepo(repo, files)
                "STT" -> handleSttRepo(repo, files)
                else -> handleSingleFileRepo(repo, files)
            }
        }
    }

    /** VAD (single .onnx) and LLM (single .gguf) — both load from one file, no
     * assembly needed. */
    private fun handleSingleFileRepo(repo: HuggingFaceDownloader.HuggingFaceRepoInfo, files: List<HuggingFaceFile>) {
        val wanted = extensionsFor(category)
        var matches = files.filter { f -> wanted.any { f.path.endsWith(it, ignoreCase = true) } }

        if (category == "LLM" && selectedQuant != QUANT_ANY) {
            val filtered = matches.filter { it.path.contains(selectedQuant, ignoreCase = true) }
            if (filtered.isEmpty()) {
                showInfo(repo.id, "No $selectedQuant GGUF file in this repository.")
                return
            }
            matches = filtered
        }

        if (matches.isEmpty()) {
            showInfo(repo.id, "No ${wanted.joinToString(" / ")} file found in this repository.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(repo.id)
            .setItems(
                matches.map { "${it.path}\n${formatLabelFor(it.path)} · ${formatSize(it.sizeBytes)}" }.toTypedArray(),
            ) { _, which -> onFileSelected(repo.id, matches[which].path) }
            .show()
    }

    /**
     * Only VITS-shape TTS repos are safely auto-fetchable: one model .onnx plus
     * tokens.txt (espeak-ng-data is optional in that backend, so its absence is
     * fine). Kokoro/Kitten additionally need voices.bin plus a whole espeak-ng-data
     * directory of files, and Pocket needs vocab.json/token_scores.json instead of
     * tokens.txt entirely — guessing at those would produce a download that looks
     * complete and then fails to initialize, exactly like the plain single-file
     * picker did. Refuse those clearly instead.
     */
    private fun handleTtsRepo(repo: HuggingFaceDownloader.HuggingFaceRepoInfo, files: List<HuggingFaceFile>) {
        val tokens = files.firstOrNull { File(it.path).name == "tokens.txt" }
        val onnxFiles = files.filter {
            it.path.endsWith(".onnx", true) &&
                !it.path.contains("vocos", true) && !it.path.contains("hifigan", true)
        }
        val hasVoicesBin = files.any { File(it.path).name == "voices.bin" }
        val hasPocketFiles = files.any { File(it.path).name == "vocab.json" }

        val refusal = when {
            hasPocketFiles -> "This is a Pocket-style TTS repo (vocab.json/token_scores.json) — not supported yet."
            hasVoicesBin -> "This is a Kokoro/Kitten-style TTS repo — needs voices.bin plus a full espeak-ng-data " +
                "folder, not supported yet. Pick a VITS/Piper-style voice instead."
            tokens == null -> "No tokens.txt found in this repository."
            onnxFiles.isEmpty() -> "No usable model .onnx found in this repository."
            onnxFiles.size > 1 -> "Found ${onnxFiles.size} model .onnx files — likely a Matcha-style two-stage " +
                "voice, not supported yet."
            else -> null
        }
        if (refusal != null) {
            showInfo(repo.id, refusal)
            return
        }

        val model = onnxFiles.single()
        confirmAndSelectMultiFile(
            repo = repo.id,
            targetDirName = "hf_tts_${repo.id.hashCode()}",
            files = mapOf(
                File(model.path).name to HuggingFaceDownloader.buildUrl(repo.id, model.path),
                "tokens.txt" to HuggingFaceDownloader.buildUrl(repo.id, tokens!!.path),
            ),
            approxBytes = model.sizeBytes + tokens.sizeBytes,
            backend = "VITS",
            displayName = "${repo.id} (VITS)",
        )
    }

    /**
     * Only three streaming STT shapes are unambiguous enough to auto-detect from
     * filenames alone: transducer (encoder+decoder+joiner), paraformer
     * (encoder+decoder), and zipformer2-ctc (a single *ctc* model) — "joiner" and
     * "ctc" are distinctive substrings that do not show up by accident. Offline
     * families (Whisper/Moonshine/Parakeet) use enough different naming schemes
     * that guessing would misclassify rather than fail cleanly — refused instead,
     * same reasoning as the TTS Kokoro/Pocket refusal.
     */
    private fun handleSttRepo(repo: HuggingFaceDownloader.HuggingFaceRepoInfo, files: List<HuggingFaceFile>) {
        val tokens = files.firstOrNull { File(it.path).name == "tokens.txt" }
        val onnxFiles = files.filter { it.path.endsWith(".onnx", true) }
        val encoder = onnxFiles.firstOrNull { it.path.contains("encoder", true) }
        val decoder = onnxFiles.firstOrNull { it.path.contains("decoder", true) }
        val joiner = onnxFiles.firstOrNull { it.path.contains("joiner", true) }
        val ctc = onnxFiles.firstOrNull { it.path.contains("ctc", true) }

        if (tokens == null) {
            showInfo(repo.id, "No tokens.txt found in this repository.")
            return
        }

        val (backend, parts) = when {
            encoder != null && decoder != null && joiner != null ->
                "ZIPFORMER_TRANSDUCER" to listOf(encoder, decoder, joiner)
            encoder != null && decoder != null -> "PARAFORMER" to listOf(encoder, decoder)
            ctc != null -> "ZIPFORMER2_CTC" to listOf(ctc)
            else -> null to emptyList()
        }
        if (backend == null) {
            showInfo(
                repo.id,
                "Could not identify a streaming STT model shape (need encoder+decoder+joiner, " +
                    "encoder+decoder, or a ctc model, plus tokens.txt). Offline models " +
                    "(Whisper/Moonshine/Parakeet) aren't supported here yet — use the curated registry.",
            )
            return
        }

        val fileMap = mutableMapOf<String, String>()
        for (part in parts) fileMap[File(part.path).name] = HuggingFaceDownloader.buildUrl(repo.id, part.path)
        fileMap["tokens.txt"] = HuggingFaceDownloader.buildUrl(repo.id, tokens.path)

        confirmAndSelectMultiFile(
            repo = repo.id,
            targetDirName = "hf_stt_${repo.id.hashCode()}",
            files = fileMap,
            approxBytes = parts.sumOf { it.sizeBytes } + tokens.sizeBytes,
            backend = backend,
            displayName = "${repo.id} ($backend)",
        )
    }

    private fun showInfo(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmAndSelectMultiFile(
        repo: String,
        targetDirName: String,
        files: Map<String, String>,
        approxBytes: Long,
        backend: String,
        displayName: String,
    ) {
        AlertDialog.Builder(this)
            .setTitle(displayName)
            .setMessage(
                "Will download ${files.size} files (${formatSize(approxBytes)}):\n" +
                    files.keys.joinToString("\n") { "• $it" },
            )
            .setPositiveButton("Use this model") { _, _ ->
                onMultiFileSelected(repo, targetDirName, files, approxBytes, backend, displayName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shows the curated registry entries already bundled for this category, with
     * their direct URLs, so the user can see what's already available before
     * searching Hugging Face for something else. Informational only — these are
     * already selectable via the spinner on the main screen. */
    private fun showLocalModels(models: List<com.s2s.mobile.model.ModelSpec>) {
        if (models.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Already have locally")
                .setMessage("No curated $category models in the registry yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Already have locally — $category")
            .setItems(
                models.map { "${it.name}\n${formatSize(it.approxBytes)} · ${it.url}" }.toTypedArray(),
                null,
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun onFileSelected(repo: String, filename: String) {
        val result = Intent().apply {
            putExtra(EXTRA_REPO, repo)
            putExtra(EXTRA_FILENAME, filename)
            putExtra(EXTRA_CATEGORY, category)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    /** Files are already resolved to direct URLs here, so the result carries them
     * straight through instead of making MainActivity re-fetch the repo listing. */
    private fun onMultiFileSelected(
        repo: String,
        targetDirName: String,
        files: Map<String, String>,
        approxBytes: Long,
        backend: String,
        displayName: String,
    ) {
        val filesBundle = Bundle()
        for ((filename, url) in files) filesBundle.putString(filename, url)
        val result = Intent().apply {
            putExtra(EXTRA_REPO, repo)
            putExtra(EXTRA_CATEGORY, category)
            putExtra(EXTRA_MULTI_FILES, filesBundle)
            putExtra(EXTRA_TARGET_DIR_NAME, targetDirName)
            putExtra(EXTRA_APPROX_BYTES, approxBytes)
            putExtra(EXTRA_BACKEND, backend)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "S2S-HFBrowser"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_REPO = "repo"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MULTI_FILES = "multiFiles"
        const val EXTRA_TARGET_DIR_NAME = "targetDirName"
        const val EXTRA_APPROX_BYTES = "approxBytes"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_DISPLAY_NAME = "displayName"

        private const val QUANT_ANY = "Any"
        private val QUANT_OPTIONS = listOf(QUANT_ANY, "Q2_K", "Q3_K", "Q4_K_M", "Q4_0", "Q5_K_M", "Q6_K", "Q8_0", "F16")
    }
}
