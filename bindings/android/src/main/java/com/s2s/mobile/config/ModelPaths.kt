package com.s2s.mobile.config

/**
 * Where the four model bundles live on disk.
 *
 * Directories are the extracted contents of the upstream release archives;
 * individual files are used as-is. Nothing here is downloaded by the SDK — the
 * host app owns fetching and caching.
 */
data class ModelPaths(
    /** Silero VAD v5 ONNX file, e.g. `<dir>/silero_vad.onnx`. */
    val vadModel: String,
    /** Directory holding a sherpa-onnx streaming recogniser bundle. */
    val sttDir: String,
    /** GGUF weights for llama.cpp. */
    val llmModel: String,
    /** Directory holding a sherpa-onnx TTS bundle matching [TtsConfig.backend]. */
    val ttsDir: String,
    /**
     * DPDFNet 48kHz HR speech-enhancement ONNX file, only read when
     * [TtsConfig.enableHdAudioRestorer] is true. Null disables the feature
     * regardless of the flag, since there is nothing to load.
     */
    val hdAudioRestorerModel: String? = null,
)
