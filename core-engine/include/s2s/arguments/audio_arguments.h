#pragma once

#include <cstdint>

namespace s2s {

/**
 * @brief Configuration parameters for Audio Capture and Playback.
 * Synchronized with Python LocalAudioArguments.
 */
struct AudioArguments {
    int sampleRate = 16000;
    int channels = 1;
    int frameSizeSamples = 512; // 32ms frames at 16kHz
    int captureBufferCapacity = 8192;
    bool enableAcousticEchoCancellation = true;
    bool enableNoiseSuppression = true;
};

} // namespace s2s
