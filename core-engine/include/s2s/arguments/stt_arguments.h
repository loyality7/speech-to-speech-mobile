#pragma once

#include <string>

namespace s2s {

/**
 * @brief Configuration parameters for Speech-to-Text (STT).
 * Synchronized with Python WhisperSTTArguments / STTArguments.
 */
struct STTArguments {
    std::string modelPath = "";
    std::string language = "en";
    bool enableHypothesis = true;             // Live partial transcription stream
    int noiseRejectionThresholdMs = 200;      // Ignore speech fragments shorter than 200ms
    int numThreads = 2;
};

} // namespace s2s
