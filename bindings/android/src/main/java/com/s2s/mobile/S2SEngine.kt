package com.s2s.mobile

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/**
 * 100% On-Device Speech-to-Speech Mobile Engine (Android Kotlin SDK)
 */
class S2SEngine {

    companion object {
        init {
            System.loadLibrary("s2s_jni")
        }
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    var onTranscript: ((String, Boolean) -> Unit)? = null
    var onAudioChunk: ((FloatArray) -> Unit)? = null
    var onBargeIn: (() -> Unit)? = null

    var onSynthesizeTTS: ((String) -> FloatArray)? = null
    var onTranscribeAudio: ((FloatArray) -> String)? = null

    fun onNativeTranscript(text: String, isFinal: Boolean) {
        onTranscript?.invoke(text, isFinal)
    }

    fun onNativeAudioChunk(samples: FloatArray) {
        Log.d("S2SEngine", "onNativeAudioChunk: ${samples.size} samples, callback=${onAudioChunk != null}")
        onAudioChunk?.invoke(samples)
    }

    fun onNativeBargeIn() {
        onBargeIn?.invoke()
    }

    fun onNativeSynthesizeTTS(text: String): FloatArray {
        return onSynthesizeTTS?.invoke(text) ?: floatArrayOf()
    }

    fun onNativeTranscribeAudio(samples: FloatArray): String {
        return onTranscribeAudio?.invoke(samples) ?: ""
    }

    private external fun nativeInitialize(
        vadPath: String,
        sttPath: String,
        llmPath: String,
        ttsPath: String
    ): Boolean

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeFeedAudioFloat(pcmData: FloatArray)
    private external fun nativeFeedAudioShort(pcmData: ShortArray)
    private external fun nativeFeedTextPrompt(promptText: String)
    private external fun nativeInterrupt()

    fun initialize(
        vadPath: String = "",
        sttPath: String = "",
        llmPath: String = "",
        ttsPath: String = ""
    ): Boolean {
        return nativeInitialize(vadPath, sttPath, llmPath, ttsPath)
    }

    var isVADActive = true

    fun start(): Boolean {
        if (!nativeStart()) return false

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            // Enable Hardware Acoustic Echo Cancellation (AEC)
            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true, name = "S2SMicThread") {
                val shortBuffer = ShortArray(512)
                while (isRecording && audioRecord != null) {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (read > 0 && isVADActive) {
                        feedAudio(shortBuffer)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(500)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        nativeStop()
    }

    fun initLlamatik(
        ggufModelPath: String,
        whisperModelPath: String = "",
        contextLength: Int = 2048,
        numThreads: Int = 4,
        gpuLayers: Int = 0
    ): Boolean {
        return try {
            com.llamatik.library.platform.LlamaBridge.updateGenerateParams(
                temperature = 0.7f,
                maxTokens = 512,
                topP = 0.95f,
                topK = 40,
                repeatPenalty = 1.1f,
                contextLength = contextLength,
                numThreads = numThreads,
                useMmap = true,
                flashAttention = false,
                batchSize = 512,
                gpuLayers = gpuLayers
            )
            val llmLoaded = com.llamatik.library.platform.LlamaBridge.initGenerateModel(ggufModelPath)
            if (whisperModelPath.isNotEmpty()) {
                com.llamatik.library.platform.WhisperBridge.initModel(whisperModelPath)
            }
            try {
                val methods = com.llamatik.library.platform.WhisperBridge::class.java.declaredMethods
                for (m in methods) {
                    Log.d("S2S_REFLECT", "WhisperBridge method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            llmLoaded
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    fun feedAudio(samples: FloatArray) = nativeFeedAudioFloat(samples)

    fun feedAudio(samples: ShortArray) = nativeFeedAudioShort(samples)

    fun feedTextPrompt(text: String) = nativeFeedTextPrompt(text)

    fun interrupt() {
        try {
            com.llamatik.library.platform.LlamaBridge.nativeCancelGenerate()
        } catch (_: Throwable) {}
        nativeInterrupt()
        onBargeIn?.invoke()
    }
}
