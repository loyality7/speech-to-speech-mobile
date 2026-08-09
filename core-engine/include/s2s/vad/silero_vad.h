#pragma once

#include <vector>
#include <string>
#include <memory>
#include <cstdint>

namespace s2s {

/**
 * @brief Silero VAD v5 ONNX Model Wrapper & State Machine.
 * Manages frame chunking (512 samples @ 16kHz) and recurrent hidden states (h, c).
 */
class SileroVAD {
public:
    SileroVAD(float threshold = 0.5f, int sampleRate = 16000);
    ~SileroVAD();

    bool loadModel(const std::string& modelPath);
    void resetStates();

    /**
     * @brief Process a single frame of audio (512 samples @ 16kHz).
     * @return Speech probability between 0.0f and 1.0f.
     */
    float processFrame(const float* samples, size_t numSamples);

    bool isModelLoaded() const { return modelLoaded_; }
    float getThreshold() const { return threshold_; }
    void setThreshold(float threshold) { threshold_ = threshold; }

private:
    float threshold_ = 0.5f;
    int sampleRate_ = 16000;
    bool modelLoaded_ = false;

    // Recurrent state buffers for Silero VAD v5: 2 layers x 1 batch x 64 hidden dim
    std::vector<float> stateH_;
    std::vector<float> stateC_;

    struct Impl;
    std::unique_ptr<Impl> pImpl_;
};

} // namespace s2s
