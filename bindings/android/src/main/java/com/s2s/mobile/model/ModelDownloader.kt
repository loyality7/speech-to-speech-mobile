package com.s2s.mobile.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Robust model bundle downloader and validator integrated into the S2S SDK.
 *
 * Features:
 * - Storage space pre-check before downloading
 * - Resumable HTTP downloads (HTTP 206 Range requests)
 * - SHA256 integrity verification (prevents truncated GGUF native crashes)
 * - Safe archive extraction with path traversal guards
 */
class ModelDownloader(private val modelsDir: File) {

    /** Deletes every downloaded model — hundreds of MB, so never on the caller's thread. */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        if (modelsDir.exists()) {
            modelsDir.deleteRecursively().also { modelsDir.mkdirs() }
        } else {
            true
        }
    }

    /** Deletes a specific model bundle from disk. */
    suspend fun deleteModel(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        val target = File(modelsDir, spec.targetPath)
        if (!target.exists()) return@withContext true
        if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }

    /** Calculates exact disk space in bytes used by a model (file or extracted archive directory). */
    fun diskUsage(spec: ModelSpec): Long {
        val target = File(modelsDir, spec.targetPath)
        if (!target.exists()) return 0L
        return if (target.isDirectory) {
            target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            target.length()
        }
    }

    /** Total disk space in bytes used by all files in the models directory. */
    fun totalDiskUsage(): Long {
        if (!modelsDir.exists()) return 0L
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Returns disk accounting and installation status for every known model specification. */
    fun getInstalledModels(allSpecs: List<ModelSpec> = ModelRegistry.ALL_MODELS): List<InstalledModelInfo> {
        return allSpecs.map { spec ->
            InstalledModelInfo(
                spec = spec,
                isInstalled = present(spec),
                diskUsageBytes = diskUsage(spec),
                targetFile = File(modelsDir, spec.targetPath),
            )
        }
    }

    fun missing(specs: List<ModelSpec> = ModelRegistry.DEFAULT_STACK): List<ModelSpec> =
        specs.filterNot { present(it) }

    /**
     * Cheap structural check — stats files, never reads them.
     *
     * Deliberately does NOT verify the checksum: this is called from UI callbacks on
     * every status refresh, and hashing a 491 MB GGUF there is an ANR. Integrity is
     * checked once, in downloadSpec, before the file is ever moved into place.
     */
    fun present(spec: ModelSpec): Boolean {
        val target = File(modelsDir, spec.targetPath)
        return if (spec.archive) {
            val files = target.takeIf { it.isDirectory }?.listFiles() ?: return false
            files.any {
                it.isFile && (it.name.endsWith(".onnx") || it.name == "tokens.txt" || it.name.endsWith(".bin"))
            }
        } else {
            target.isFile && target.length() >= (spec.approxBytes * 9 / 10)
        }
    }

    @Volatile
    private var isCancelled = false

    fun cancelDownload() {
        isCancelled = true
    }

    suspend fun downloadAll(
        specs: List<ModelSpec> = ModelRegistry.DEFAULT_STACK,
        onProgress: (ModelProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        isCancelled = false
        modelsDir.mkdirs()

        val missingSpecs = specs.filterNot { present(it) }
        if (missingSpecs.isEmpty()) return@withContext

        // Pre-check storage space
        val totalBytesNeeded = missingSpecs.sumOf { it.approxBytes }
        val usableSpace = modelsDir.usableSpace
        if (usableSpace > 0 && usableSpace < totalBytesNeeded) {
            val msg = "Insufficient storage space: need ${totalBytesNeeded / (1024 * 1024)} MB, " +
                "available ${usableSpace / (1024 * 1024)} MB"
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
        }

        for (spec in missingSpecs) {
            if (isCancelled) {
                Log.i(TAG, "Download cancelled before starting ${spec.name}")
                break
            }
            onProgress(ModelProgress(spec.name, 0, 0L, spec.approxBytes, ModelProgress.Status.PRECHECK))
            downloadSpec(spec, onProgress)
        }
    }

    private fun downloadSpec(spec: ModelSpec, onProgress: (ModelProgress) -> Unit) {
        val tempFile = File(modelsDir, "${spec.targetPath}.part")
        tempFile.parentFile?.mkdirs()
        val etagFile = File(modelsDir, "${spec.targetPath}.etag")

        // Resuming means appending to bytes we cannot re-inspect, so it is only safe
        // when the server confirms it is still serving the same bytes. Without a
        // validator from the first response there is nothing to confirm against —
        // and with no checksums in the registry, a mismatch would go undetected all
        // the way to a corrupt model failing inside native code. Start over instead.
        val knownEtag = etagFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
        var existingBytes = if (tempFile.isFile && knownEtag != null) tempFile.length() else 0L
        if (existingBytes == 0L) tempFile.delete()

        var connection = openConnectionWithRedirects(spec.url, existingBytes, knownEtag)
        val responseCode = connection.responseCode

        var resuming = false
        if (existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
            resuming = true
            Log.i(TAG, "Resuming download for ${spec.name} from offset $existingBytes bytes")
        } else if (responseCode == HttpURLConnection.HTTP_OK) {
            // Either a fresh start, or If-Range told the server the file changed and
            // it sent the whole thing instead. Both mean: discard what we had.
            existingBytes = 0L
            tempFile.delete()
            val etag = connection.getHeaderField("ETag")
            if (etag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(etag)
        } else {
            connection.disconnect()
            throw IllegalStateException("${spec.name}: Server returned HTTP $responseCode")
        }

        val totalContentLength = connection.contentLengthLong
        val totalBytes = if (resuming && totalContentLength > 0) {
            existingBytes + totalContentLength
        } else if (totalContentLength > 0) {
            totalContentLength
        } else {
            spec.approxBytes
        }

        var downloadedBytes = existingBytes
        var lastPercent = -1

        try {
            connection.inputStream.use { input ->
                RandomAccessFile(tempFile, "rw").use { raf ->
                    if (resuming) {
                        raf.seek(existingBytes)
                    } else {
                        raf.setLength(0)
                    }

                    val buffer = ByteArray(1 shl 16)
                    while (!isCancelled) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        raf.write(buffer, 0, n)
                        downloadedBytes += n

                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(
                                ModelProgress(
                                    spec.name,
                                    percent,
                                    downloadedBytes,
                                    totalBytes,
                                    ModelProgress.Status.DOWNLOADING,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (isCancelled) {
            Log.i(TAG, "Download of ${spec.name} stopped by user request. Partial file kept at ${tempFile.name}")
            return
        }

        // A stream that ends early is the common failure — a dropped connection
        // yields a short file that looks complete and then fails deep inside
        // llama.cpp/onnxruntime with an unreadable error. The server told us the
        // exact length; hold it to that. Only when the length was actually known.
        val expected = if (resuming) existingBytes + totalContentLength else totalContentLength
        if (totalContentLength > 0 && downloadedBytes != expected) {
            tempFile.delete()
            etagFile.delete()
            throw IllegalStateException(
                "${spec.name}: truncated download — got $downloadedBytes bytes, expected $expected",
            )
        }

        // SHA256 Verification if provided
        if (!spec.sha256.isNullOrBlank()) {
            onProgress(
                ModelProgress(
                    spec.name,
                    100,
                    downloadedBytes,
                    totalBytes,
                    ModelProgress.Status.VERIFYING,
                ),
            )
            val hash = calculateSha256(tempFile)
            if (!hash.equals(spec.sha256, ignoreCase = true)) {
                tempFile.delete()
                val errorMsg = "SHA256 checksum failed for ${spec.name}: expected ${spec.sha256}, got $hash"
                Log.e(TAG, errorMsg)
                throw IllegalStateException(errorMsg)
            }
        }

        val target = File(modelsDir, spec.targetPath)
        if (spec.archive) {
            onProgress(
                ModelProgress(
                    spec.name,
                    0,
                    0L,
                    tempFile.length(),
                    ModelProgress.Status.EXTRACTING,
                ),
            )
            target.deleteRecursively()
            val archiveSize = tempFile.length().coerceAtLeast(1L)
            extractTarBz2(tempFile, target) { extractedBytes ->
                val percent = ((extractedBytes * 100) / archiveSize).toInt().coerceIn(0, 99)
                onProgress(
                    ModelProgress(
                        spec.name,
                        percent,
                        extractedBytes,
                        archiveSize,
                        ModelProgress.Status.EXTRACTING,
                    ),
                )
            }
            tempFile.delete()
        } else {
            target.delete()
            check(tempFile.renameTo(target)) { "${spec.name}: rename temp file to target failed" }
        }

        etagFile.delete()
        onProgress(
            ModelProgress(
                spec.name,
                100,
                downloadedBytes,
                totalBytes,
                ModelProgress.Status.COMPLETED,
            ),
        )
        Log.i(TAG, "${spec.name} downloaded & ready at ${target.absolutePath}")
    }

    private fun openConnectionWithRedirects(
        urlStr: String,
        resumeBytes: Long,
        etag: String? = null,
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                if (resumeBytes > 0) {
                    setRequestProperty("Range", "bytes=$resumeBytes-")
                    // Makes the range conditional: if the resource changed, the server
                    // ignores the range and sends 200 with the whole file.
                    if (etag != null) setRequestProperty("If-Range", etag)
                }
            }
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = next
                redirects++
            } else {
                return conn
            }
        }
        return (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            if (resumeBytes > 0) {
                setRequestProperty("Range", "bytes=$resumeBytes-")
                if (etag != null) setRequestProperty("If-Range", etag)
            }
            connect()
        }
    }

    private fun extractTarBz2(
        archive: File,
        destination: File,
        onProgress: (extractedBytes: Long) -> Unit,
    ) {
        destination.mkdirs()
        var totalExtractedBytes = 0L
        var lastEmittedBytes = 0L
        val buffer = ByteArray(1 shl 16)

        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val relative = if (entry.name.contains('/')) {
                    entry.name.substringAfter('/')
                } else {
                    entry.name
                }
                if (relative.isEmpty()) continue

                val out = File(destination, relative)
                if (!out.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    Log.w(TAG, "skipping unsafe entry ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { output ->
                    while (true) {
                        val read = tar.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalExtractedBytes += read
                        // Throttle progress updates to every 64KB
                        if (totalExtractedBytes - lastEmittedBytes >= 64 * 1024) {
                            lastEmittedBytes = totalExtractedBytes
                            onProgress(totalExtractedBytes)
                        }
                    }
                }
            }
        }
        onProgress(totalExtractedBytes)
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "S2S-ModelDownloader"
    }
}
