#pragma once

#include <vector>
#include <cstdint>
#include <cmath>

namespace s2s {

/**
 * @brief Standalone Log-Mel Spectrogram Extractor for Whisper ONNX & Sherpa models.
 * Computes 80-channel log-Mel filterbank energies from 16kHz PCM audio without external dependencies.
 */
class WhisperFeatureExtractor {
public:
    static constexpr int SAMPLE_RATE = 16000;
    static constexpr int N_FFT = 400;
    static constexpr int HOP_LENGTH = 160;
    static constexpr int N_MELS = 80;
    static constexpr int CHUNK_LENGTH_SECONDS = 30;
    static constexpr int MAX_FRAMES = 3000; // 30s * 100 frames/sec

    WhisperFeatureExtractor();
    ~WhisperFeatureExtractor();

    /**
     * @brief Computes 80x3000 log-Mel spectrogram feature matrix from 16kHz audio.
     * @param samples Pointer to raw 16kHz mono audio float samples.
     * @param numSamples Number of input audio samples.
     * @return 1D flattened vector of size (80 * numFrames) in row-major layout.
     */
    std::vector<float> extractFeatures(const float* samples, size_t numSamples);

private:
    std::vector<float> hannWindow_;
    std::vector<std::vector<float>> melFilters_;

    void initHannWindow();
    void initMelFilters();
    static float hzToMel(float hz);
    static float melToHz(float mel);
};

} // namespace s2s
