#pragma once

#include <string>

namespace s2s {

/**
 * @brief Configuration parameters for Text-to-Speech (TTS).
 * Synchronized with Python KokoroTTSArguments / Qwen3TTSArguments.
 */
struct TTSArguments {
    std::string voice = "af_heart";
    std::string voicePath = "";
    int nativeSampleRate = 24000;
    int pipelineSampleRate = 16000;
    float speed = 1.0f;
    std::string language = "en";
    int blockSizeSamples = 512;
    int silencePaddingMs = 5;
};

} // namespace s2s
