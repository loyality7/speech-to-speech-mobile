package com.s2s.mobile.config

import com.s2s.mobile.pipeline.TtsBackend

/**
 * Speech synthesis.
 *
 * [backend] must match the bundle in [ModelPaths.ttsDir] — each family expects a
 * different set of files.
 */
data class TtsConfig(
    val backend: TtsBackend = TtsBackend.KOKORO,
    /** Speaker index within the bundle. Ignored by single-voice models. */
    val speakerId: Int = 0,
    /** Playback rate multiplier. Above 1.0 is faster speech. */
    val speed: Float = 1.0f,
    /**
     * Kokoro synthesises a whole sentence before returning its first chunk, so
     * this sets time-to-first-audio directly. 4 threads roughly halves it on a
     * modern phone versus 2.
     */
    val numThreads: Int = 4,
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
     * Characters after which the opening sentence is flushed even without
     * terminal punctuation. Trades a slightly early break for a shorter
     * time-to-first-audio on the first sentence only.
     */
    val firstChunkMinChars: Int = 24,
    /**
     * Longest run of text sent to the synthesiser in one call.
     *
     * Caps how long any single synthesis takes, so audio keeps arriving while the
     * next chunk is still being generated. Too high and playback stalls between
     * chunks; too low and prosody suffers, because the model needs a phrase to
     * shape intonation across.
     */
    val maxChunkChars: Int = 70,
    /**
     * Pick the voice from the language the recogniser reports, where the bundle
     * offers more than one language.
     */
    val followDetectedLanguage: Boolean = false,
    /** Language code to voice id, used when [followDetectedLanguage] is on. */
    val languageVoices: Map<String, Int> = emptyMap(),
)
