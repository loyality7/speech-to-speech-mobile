package com.s2s.demo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the four model bundles into the app's files directory.
 *
 * sherpa ships its models as `.tar.bz2`, so archives are extracted rather than
 * saved as-is — the previous demo stored a tarball under a `.onnx` name and
 * never unpacked it, which is why nothing could ever load.
 */
class ModelDownloader(private val modelsDir: File) {

    data class Spec(
        val label: String,
        val url: String,
        /** File name, or directory name when [archive] is true. */
        val target: String,
        val archive: Boolean,
        val approxBytes: Long,
    )

    /** Progress for one model. [percent] is -1 while extracting. */
    data class Progress(val label: String, val percent: Int, val extracting: Boolean = false)

    fun missing(): List<Spec> = MODELS.filterNot { present(it) }

    fun present(spec: Spec): Boolean {
        val target = File(modelsDir, spec.target)
        return if (spec.archive) {
            // A bundle is only usable once its tokens file is on disk.
            target.isDirectory && File(target, "tokens.txt").isFile
        } else {
            // Guard against a half-written file from an interrupted download.
            target.isFile && target.length() > spec.approxBytes / 2
        }
    }

    suspend fun downloadAll(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        for (spec in MODELS) {
            if (present(spec)) continue
            onProgress(Progress(spec.label, 0))
            download(spec, onProgress)
        }
    }

    private fun download(spec: Spec, onProgress: (Progress) -> Unit) {
        val temp = File(modelsDir, "${spec.target}.part")
        temp.parentFile?.mkdirs()

        var connection = URL(spec.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.connect()

        // HttpURLConnection will not follow a redirect across protocols, which is
        // exactly what the GitHub and Hugging Face CDNs do.
        var redirects = 0
        while (connection.responseCode in 300..399 && redirects++ < 5) {
            val next = connection.getHeaderField("Location") ?: break
            connection.disconnect()
            connection = (URL(next).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                connect()
            }
        }
        check(connection.responseCode == 200) { "${spec.label}: HTTP ${connection.responseCode}" }

        val total = connection.contentLengthLong.takeIf { it > 0 } ?: spec.approxBytes
        var read = 0L
        var lastPercent = -1

        connection.inputStream.use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(Progress(spec.label, percent))
                    }
                }
            }
        }
        connection.disconnect()

        val target = File(modelsDir, spec.target)
        if (spec.archive) {
            onProgress(Progress(spec.label, -1, extracting = true))
            target.deleteRecursively()
            extractTarBz2(temp, target)
            temp.delete()
        } else {
            target.delete()
            check(temp.renameTo(target)) { "${spec.label}: rename failed" }
        }
        Log.i(TAG, "${spec.label} ready at ${target.absolutePath}")
    }

    /**
     * Unpacks into [destination], dropping the archive's single top-level folder
     * so the model files land directly in the directory the SDK is pointed at.
     */
    private fun extractTarBz2(archive: File, destination: File) {
        destination.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isEmpty()) continue

                val out = File(destination, relative)
                // Refuse entries that would escape the destination directory.
                if (!out.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    Log.w(TAG, "skipping unsafe entry ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }

    companion object {
        private const val TAG = "S2S-Download"

        private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

        val MODELS = listOf(
            Spec(
                label = "Silero VAD",
                url = "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx",
                target = "silero_vad.onnx",
                archive = false,
                approxBytes = 2_327_524,
            ),
            Spec(
                label = "STT (Zipformer)",
                url = "$SHERPA/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
                target = "stt",
                archive = true,
                approxBytes = 310_414_022,
            ),
            Spec(
                label = "TTS (Kokoro int8)",
                url = "$SHERPA/tts-models/kokoro-int8-en-v0_19.tar.bz2",
                target = "tts",
                archive = true,
                approxBytes = 103_248_205,
            ),
            Spec(
                label = "LLM (Qwen2.5 0.5B)",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/" +
                    "resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                target = "model.gguf",
                archive = false,
                approxBytes = 491_400_032,
            ),
        )
    }
}
