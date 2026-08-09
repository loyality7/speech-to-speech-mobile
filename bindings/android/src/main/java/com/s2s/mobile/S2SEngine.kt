package com.s2s.mobile

/**
 * SpeechToSpeech Mobile On-Device SDK (Kotlin Wrapper)
 */
class S2SEngine {

    companion object {
        init {
            System.loadLibrary("s2s_core")
        }
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
    private external fun nativeInterrupt()

    fun initialize(vadPath: String, sttPath: String, llmPath: String, ttsPath: String): Boolean {
        return nativeInitialize(vadPath, sttPath, llmPath, ttsPath)
    }

    fun start(): Boolean = nativeStart()

    fun stop() = nativeStop()

    fun feedAudio(samples: FloatArray) = nativeFeedAudioFloat(samples)

    fun feedAudio(samples: ShortArray) = nativeFeedAudioShort(samples)

    fun interrupt() = nativeInterrupt()
}
