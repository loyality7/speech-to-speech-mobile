package com.s2s.plugin.s1

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads this plugin's own model.
 *
 * Self-contained by design. The plugin depends on no s2s artifact — that
 * independence is what proves the host has no special knowledge of it — so
 * it cannot reuse the host's downloader and carries a small one instead.
 *
 * Resumable, because a 462 MiB download on a phone will be interrupted:
 * range-requests continue a partial file rather than restarting. Writes to
 * a `.part` file and renames only on success, so an interrupted download
 * can never be mistaken for a usable model.
 */
internal object ModelDownload {

    /** Model card's recommended Android build: Q4_K_M, ~462 MiB, published accuracy metrics. */
    const val URL =
        "https://huggingface.co/superwhisper/s1-mini-GGUF/resolve/main/s1-mini-q4_k_m.gguf?download=true"
    const val FILE_NAME = "s1-mini-q4_k_m.gguf"

    /** Approximate, for a progress estimate before the server reports a length. */
    const val APPROX_BYTES = 462L * 1024 * 1024

    fun target(filesDir: File): File = File(File(filesDir, "models").apply { mkdirs() }, FILE_NAME)

    fun isPresent(filesDir: File): Boolean {
        val f = target(filesDir)
        // A truncated file is worse than no file: it loads, then fails
        // strangely. Require most of the expected size before trusting it.
        return f.isFile && f.length() > APPROX_BYTES / 2
    }

    /**
     * Downloads to [filesDir], reporting progress as (bytesSoFar, totalBytesOrNull).
     * Returns the finished file, or a failure — never a partial file.
     */
    fun download(filesDir: File, onProgress: (Long, Long?) -> Unit, keepGoing: () -> Boolean): Result<File> {
        val target = target(filesDir)
        if (isPresent(filesDir)) return Result.success(target)

        val partial = File(target.parentFile, "$FILE_NAME.part")
        var existing = if (partial.isFile) partial.length() else 0L

        return runCatching {
            val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            connection.inputStream.use { input ->
                // 206 means the server honoured the range; anything else
                // means it is sending from the start, so a partial file
                // must not be appended to or the result is corrupt.
                if (existing > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    Log.i(TAG, "server ignored range request — restarting download")
                    partial.delete()
                    existing = 0
                }

                val reportedTotal = connection.getHeaderField("Content-Length")?.toLongOrNull()
                val total = reportedTotal?.let { it + existing }

                java.io.FileOutputStream(partial, existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing
                    while (true) {
                        if (!keepGoing()) throw InterruptedException("download cancelled")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }

            require(partial.length() > APPROX_BYTES / 2) {
                "downloaded file is implausibly small (${partial.length()} bytes)"
            }
            require(partial.renameTo(target)) { "could not finalise downloaded model" }
            Log.i(TAG, "model ready: ${target.length() / 1_048_576} MiB")
            target
        }.onFailure {
            Log.w(TAG, "download failed", it)
        }
    }

    fun delete(filesDir: File) {
        target(filesDir).delete()
        File(target(filesDir).parentFile, "$FILE_NAME.part").delete()
    }

    private const val TAG = "S1MiniDownload"
}
