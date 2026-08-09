#pragma once

#include <vector>
#include <string>
#include <memory>
#include <cstdint>

namespace s2s {

struct SmartTurnResult {
    bool complete = false;
    float probability = 0.0f;
    float inferenceMs = 0.0f;
};

/**
 * @brief Smart Turn v3.2 Prosody & Acoustic Turn-Completion Analyzer.
 * Synchronized with Python speech_to_speech/VAD/smart_turn.py.
 * Evaluates linguistic & acoustic prosody to determine if an utterance is complete
 * or if the user is merely pausing mid-sentence.
 */
class SmartTurnAnalyzer {
public:
    SmartTurnAnalyzer(float threshold = 0.5f, int sampleRate = 16000);
    ~SmartTurnAnalyzer();

    bool loadModel(const std::string& modelPath);

    /**
     * @brief Evaluates an audio utterance to determine if turn is finished.
     * @param samples Pointer to 16kHz mono audio samples.
     * @param numSamples Number of audio samples (up to 8 seconds / 128,000 samples).
     * @return SmartTurnResult with completion status and confidence score.
     */
    SmartTurnResult predict(const float* samples, size_t numSamples);

    bool isModelLoaded() const { return modelLoaded_; }
    float getThreshold() const { return threshold_; }
    void setThreshold(float threshold) { threshold_ = threshold; }

private:
    float threshold_ = 0.5f;
    int sampleRate_ = 16000;
    bool modelLoaded_ = false;

    // Pitch & energy slope extraction for prosody evaluation
    float analyzeProsodyPitchSlope(const float* samples, size_t numSamples);

    struct Impl;
    std::unique_ptr<Impl> pImpl_;
};

} // namespace s2s
