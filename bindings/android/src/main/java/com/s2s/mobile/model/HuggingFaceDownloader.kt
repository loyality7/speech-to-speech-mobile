package com.s2s.mobile.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dynamic Hugging Face Model Downloader & GGUF Quantization Resolver.
 *
 * Supports:
 * - Direct resolution of Hugging Face repository files (`https://huggingface.co/{repo}/resolve/{revision}/{path}`)
 * - Parsing `hf://{org}/{repo}@{revision}/{filename}` shorthands
 * - Automatic GGUF model path selection based on available device RAM & target memory budgets
 */
object HuggingFaceDownloader {

    private const val TAG = "S2S-HuggingFace"
    private const val HF_API_BASE = "https://huggingface.co/api/models"

    data class HuggingFaceFile(
        val path: String,
        val sizeBytes: Long,
        val type: String, // "file" or "directory"
        val lfsOid: String? = null,
    )

    /**
     * Resolves a Hugging Face shorthand or HTTPS URL to a direct downloadable URL.
     * Example input: "hf://Qwen/Qwen2.5-0.5B-Instruct-GGUF@main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
     * Example output: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
     */
    fun resolveUrl(urlOrShorthand: String): String {
        if (!urlOrShorthand.startsWith("hf://")) return urlOrShorthand
        val cleaned = urlOrShorthand.removePrefix("hf://")
        val revisionSplit = cleaned.split("@", limit = 2)
        val repo = revisionSplit[0]
        val rest = revisionSplit.getOrNull(1) ?: "main/"
        val revPath = if (rest.contains("/")) rest else "$rest/"
        val revision = revPath.substringBefore("/")
        val path = revPath.substringAfter("/")
        return "https://huggingface.co/$repo/resolve/$revision/$path"
    }

    /**
     * Builds a standard Hugging Face direct download URL given repo details.
     */
    fun buildUrl(repo: String, filename: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/$filename"

    /**
     * Queries the Hugging Face REST API to fetch all files in a repository.
     */
    suspend fun fetchRepositoryFiles(
        repo: String,
        revision: String = "main",
    ): List<HuggingFaceFile> = withContext(Dispatchers.IO) {
        val apiUrl = "$HF_API_BASE/$repo/tree/$revision"
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "S2S-Mobile-SDK/1.1")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Failed to query Hugging Face API for $repo: HTTP ${connection.responseCode}")
                return@withContext emptyList()
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(jsonText)
            val files = mutableListOf<HuggingFaceFile>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.optString("type", "file")
                val path = obj.getString("path")
                val lfs = obj.optJSONObject("lfs")
                // For an LFS-tracked file (every GGUF/large ONNX bundle), the top-level
                // "size" is the tiny pointer file's size, not the real content — the
                // real byte count lives in lfs.size. Non-LFS files have no "lfs" object
                // and their top-level "size" is already correct.
                val size = lfs?.optLong("size", 0L)?.takeIf { it > 0L } ?: obj.optLong("size", 0L)
                val lfsOid = lfs?.optString("oid")
                files.add(HuggingFaceFile(path = path, sizeBytes = size, type = type, lfsOid = lfsOid))
            }
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching repo files for $repo", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Automatically discovers available `.gguf` quantizations in a Hugging Face repository
     * and selects the optimal model path based on the target RAM budget (e.g. 900 MB).
     *
     * Preference hierarchy for mobile:
     * 1. Q4_K_M or Q4_0 (Best balance of size ~400-500MB, speed, and accuracy)
     * 2. Q3_K_M / Q4_K_S (If RAM budget < 450 MB)
     * 3. Q5_K_M / Q8_0 (If RAM budget > 1200 MB)
     */
    suspend fun selectOptimalGguf(
        repo: String,
        targetRamBudgetMb: Long = 900L,
        revision: String = "main",
    ): HuggingFaceFile? = withContext(Dispatchers.IO) {
        val files = fetchRepositoryFiles(repo, revision)
        val ggufFiles = files.filter { it.path.endsWith(".gguf", ignoreCase = true) }

        if (ggufFiles.isEmpty()) {
            Log.w(TAG, "No .gguf files found in Hugging Face repository $repo")
            return@withContext null
        }

        Log.i(TAG, "Found ${ggufFiles.size} GGUF variants in $repo. Selecting optimal file for ${targetRamBudgetMb}MB RAM budget...")

        // Sort priority: find exact match for Q4_K_M, fallback to Q4_0, Q3, etc.
        val optimal = when {
            targetRamBudgetMb <= 600 -> {
                ggufFiles.firstOrNull { it.path.contains("q2_k", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q3_k", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q4_k_m", ignoreCase = true) }
            }
            targetRamBudgetMb <= 1000 -> {
                ggufFiles.firstOrNull { it.path.contains("q4_k_m", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q4_0", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q4_k_s", ignoreCase = true) }
            }
            else -> {
                ggufFiles.firstOrNull { it.path.contains("q5_k_m", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q8_0", ignoreCase = true) }
                    ?: ggufFiles.firstOrNull { it.path.contains("q4_k_m", ignoreCase = true) }
            }
        } ?: ggufFiles.minByOrNull { it.sizeBytes }

        if (optimal != null) {
            Log.i(TAG, "Selected GGUF model path: ${optimal.path} (${optimal.sizeBytes / (1024 * 1024)} MB)")
        }
        optimal
    }

    data class HuggingFaceRepoInfo(
        val id: String,
        val downloads: Int,
        val likes: Int,
    )

    /**
     * Searches Hugging Face model hub for public model repositories matching a query
     * string, sorted by download count (most-used first) so a search screen shows
     * trustworthy results before obscure ones.
     */
    suspend fun searchRepositories(
        query: String,
        limit: Int = 20,
        libraryFilter: String? = null,
    ): List<HuggingFaceRepoInfo> = withContext(Dispatchers.IO) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val filterParam = if (libraryFilter != null) "&filter=$libraryFilter" else ""
        val apiUrl = "$HF_API_BASE?search=$encodedQuery&sort=downloads&direction=-1&limit=$limit$filterParam"
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "S2S-Mobile-SDK/1.1")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Failed to search Hugging Face for '$query': HTTP ${connection.responseCode}")
                return@withContext emptyList()
            }
            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(jsonText)
            val repos = mutableListOf<HuggingFaceRepoInfo>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                repos.add(
                    HuggingFaceRepoInfo(
                        id = obj.getString("id"),
                        downloads = obj.optInt("downloads", 0),
                        likes = obj.optInt("likes", 0),
                    ),
                )
            }
            repos
        } catch (e: Exception) {
            Log.e(TAG, "Error searching Hugging Face repositories for '$query'", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extracts a hex SHA256 digest from a Hugging Face LFS object id, if present.
     * Git LFS oids are formatted "sha256:<64 hex chars>"; anything else (or a
     * missing oid, which is normal for small non-LFS files) yields null.
     */
    fun HuggingFaceFile.sha256OrNull(): String? =
        lfsOid?.removePrefix("sha256:")?.takeIf { it.length == 64 }

    /**
     * Constructs a dynamic [ModelSpec] straight from a resolved [HuggingFaceFile] —
     * e.g. the result of [selectOptimalGguf]. Pulls the checksum from the file's LFS
     * oid automatically when available; see [createModelSpec] for what happens when
     * it is not.
     */
    fun createModelSpec(
        id: String,
        name: String,
        category: String,
        repo: String,
        file: HuggingFaceFile,
        revision: String = "main",
        backend: String? = null,
    ): ModelSpec = createModelSpec(
        id = id,
        name = name,
        category = category,
        repo = repo,
        filename = file.path,
        approxBytes = file.sizeBytes,
        revision = revision,
        backend = backend,
        sha256 = file.sha256OrNull(),
    )

    /**
     * Constructs a dynamic [ModelSpec] from a Hugging Face model repository and filename.
     *
     * [sha256] should come from the matching [HuggingFaceFile.sha256OrNull] when the
     * repo lists the file via LFS — that gives the same hard-fail integrity guarantee
     * as a curated registry entry. When unavailable, leave it null: [ModelDownloader]
     * still enforces the downloaded byte count against the server's Content-Length,
     * it just cannot cryptographically verify the content.
     */
    fun createModelSpec(
        id: String,
        name: String,
        category: String,
        repo: String,
        filename: String,
        approxBytes: Long,
        revision: String = "main",
        backend: String? = null,
        sha256: String? = null,
    ): ModelSpec {
        val url = buildUrl(repo, filename, revision)
        val targetPath = File(filename).name
        return ModelSpec(
            id = id,
            category = category,
            name = name,
            url = url,
            source = ModelSource.HUGGING_FACE,
            targetPath = targetPath,
            archive = false,
            approxBytes = approxBytes,
            sha256 = sha256,
            version = revision,
            backend = backend,
        )
    }

    /**
     * Constructs a dynamic [ModelSpec] from several individually-fetched files in one
     * Hugging Face repo — e.g. a VITS voice's .onnx plus its tokens.txt, which
     * sherpa-onnx requires together in one directory (see ModelDownloader). [files]
     * maps the plain filename to write (e.g. "tokens.txt") to its Hugging Face
     * download URL. No sha256 is attached — see ModelDownloader.downloadMultiFileSpec
     * for what integrity check applies instead.
     */
    fun createMultiFileModelSpec(
        id: String,
        name: String,
        category: String,
        repo: String,
        targetDirName: String,
        files: Map<String, String>,
        approxBytes: Long,
        revision: String = "main",
        backend: String? = null,
    ): ModelSpec = ModelSpec(
        id = id,
        category = category,
        name = name,
        url = files.values.firstOrNull() ?: "",
        source = ModelSource.HUGGING_FACE,
        targetPath = targetDirName,
        archive = true,
        multiFileUrls = files,
        approxBytes = approxBytes,
        sha256 = null,
        version = revision,
        backend = backend,
    )
}
