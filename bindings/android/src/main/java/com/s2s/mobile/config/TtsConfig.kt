package com.s2s.mobile.config

import com.s2s.mobile.pipeline.TtsBackend

/**
 * Speech synthesis configuration tuned for low latency on mobile hardware.
 *
 * [backend] must match the bundle in [ModelPaths.ttsDir] — each family expects a
 * different set of files.
 */
data class TtsConfig(
    val backend: TtsBackend = TtsBackend.KOKORO,
    /** Speaker index within the bundle. Ignored by single-voice models. */
    val speakerId: Int = 0,
    /** Playback rate multiplier. Above 1.0 is faster speech. */
    val speed: Float = 1.05f,
    /**
     * 2 threads prevents thread contention with the LLM while maintaining
     * low-latency synthesis on mobile CPU clusters.
     */
    val numThreads: Int = 2,
    val preferInt8: Boolean = true,
    /**
     * Run a throwaway synthesis at load time so the first real reply does not pay
     * ONNX graph allocation and espeak dictionary loading.
     */
    val warmUp: Boolean = true,
    /** VITS and Matcha only: variation in the generated prosody. */
    val noiseScale: Float = 0.667f,
    /** VITS only: variation in phoneme duration. */
    val noiseScaleW: Float = 0.8f,
    /**
     * Lowered to 12 chars so initial Time-To-First-Audio (TTFA) starts after the
     * first 2-3 words.
     */
    val firstChunkMinChars: Int = 12,
    /**
     * Longest run of text sent to the synthesiser in one call.
     * Capped at 80 chars so synthesis completes faster per phrase.
     */
    val maxChunkChars: Int = 80,
    /**
     * Shortest run of text worth a synthesis call.
     */
    val minChunkChars: Int = 10,
)
