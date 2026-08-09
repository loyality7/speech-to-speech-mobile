#pragma once

#include <cstdint>

namespace s2s {

/**
 * @brief Configuration parameters for Voice Activity Detection (VAD).
 * Synchronized with Python VADHandlerArguments.
 */
struct VADArguments {
    float threshold = 0.5f;          // Speech start confidence threshold [0.0, 1.0]
    float negThreshold = 0.35f;      // Silence hysteresis threshold
    int sampleRate = 16000;          // Audio sample rate in Hz
    int minSilenceMs = 300;          // Minimum silence duration to finalize utterance (ms)
    int minSpeechMs = 384;           // Minimum active speech duration to accept utterance (ms)
    int speechPadMs = 30;            // Rolling pre-speech padding buffer (ms)
    int maxSpeechMs = 30000;         // Maximum continuous speech turn duration (ms)
    bool audioEnhancement = false;   // Pre-VAD noise filter
};

} // namespace s2s
