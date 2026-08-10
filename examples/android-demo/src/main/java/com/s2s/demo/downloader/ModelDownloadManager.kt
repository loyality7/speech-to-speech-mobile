package com.s2s.demo.downloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class ModelType(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approximateSizeBytes: Long,
    val isUserSelectable: Boolean = true
) {
    SILERO_VAD(
        "silero_vad",
        "Silero VAD v5 (Neural Activity)",
        "silero_vad.onnx",
        "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx",
        1_900_000L,
        isUserSelectable = false
    ),
    WHISPER_TINY(
        "whisper_tiny",
        "Whisper-Tiny GGML (STT)",
        "ggml-tiny.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        77_700_000L,
        isUserSelectable = false
    ),
    QWEN_0_5B(
        "qwen_0_5b",
        "Qwen 2.5 0.5B Instruct Q4 (Fast LLM)",
        "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        398_000_000L,
        isUserSelectable = true
    ),
    SMOLLM2_1_7B(
        "smollm2_1_7b",
        "SmolLM2 1.7B Instruct Q4 (Balanced LLM)",
        "smollm2-1.7b-instruct-q4_k_m.gguf",
        "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
        1_050_000_000L,
        isUserSelectable = true
    ),
    PIPER_TTS_VOICE(
        "piper_tts",
        "Piper VITS Voice (en_US Lessac ONNX)",
        "en_US-lessac-medium.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        64_000_000L,
        isUserSelectable = true
    )
}

sealed class DownloadState {
    data class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getModelsDirectory(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isModelDownloaded(model: ModelType): Boolean {
        val file = File(getModelsDirectory(), model.fileName)
        return file.exists() && file.length() > (model.approximateSizeBytes * 0.8)
    }

    fun getModelFile(model: ModelType): File {
        return File(getModelsDirectory(), model.fileName)
    }

    fun downloadModel(model: ModelType): Flow<DownloadState> = flow {
        val targetFile = File(getModelsDirectory(), model.fileName)
        val tempFile = File(getModelsDirectory(), "${model.fileName}.tmp")

        if (isModelDownloaded(model)) {
            emit(DownloadState.Completed(targetFile))
            return@flow
        }

        try {
            val request = Request.Builder().url(model.url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful || response.body == null) {
                emit(DownloadState.Error("HTTP Error: ${response.code}"))
                return@flow
            }

            val body = response.body!!
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else model.approximateSizeBytes
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var lastEmittedPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

                        if (percent != lastEmittedPercent) {
                            lastEmittedPercent = percent
                            emit(DownloadState.Progress(percent, downloadedBytes, totalBytes))
                        }
                    }
                }
            }

            if (tempFile.renameTo(targetFile)) {
                emit(DownloadState.Completed(targetFile))
            } else {
                emit(DownloadState.Error("Failed to rename temporary file to target file"))
            }

        } catch (e: Exception) {
            emit(DownloadState.Error(e.localizedMessage ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)
}
