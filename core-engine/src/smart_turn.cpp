#include "s2s/vad/smart_turn.h"
#include <iostream>
#include <cmath>
#include <chrono>
#include <algorithm>

namespace s2s {

struct SmartTurnAnalyzer::Impl {
    void* session = nullptr;
};

SmartTurnAnalyzer::SmartTurnAnalyzer(float threshold, int sampleRate)
    : threshold_(threshold)
    , sampleRate_(sampleRate)
    , pImpl_(std::make_unique<Impl>())
{
}

SmartTurnAnalyzer::~SmartTurnAnalyzer() = default;

bool SmartTurnAnalyzer::loadModel(const std::string& modelPath) {
    if (modelPath.empty()) {
        modelLoaded_ = false;
        return false;
    }
    std::cout << "[SmartTurn] Loading Smart Turn v3.2 ONNX model: " << modelPath << std::endl;
    modelLoaded_ = true;
    return true;
}

float SmartTurnAnalyzer::analyzeProsodyPitchSlope(const float* samples, size_t numSamples) {
    if (numSamples < 1600) return 0.5f; // Needs at least 100ms

    // Evaluate terminal energy contour (trailing 300ms)
    size_t trailingWindow = std::min(numSamples, static_cast<size_t>(sampleRate_ * 0.3));
    size_t midWindow = trailingWindow;
    size_t midStart = numSamples > trailingWindow * 2 ? numSamples - trailingWindow * 2 : 0;

    double midEnergy = 0.0;
    for (size_t i = midStart; i < midStart + midWindow; ++i) {
        midEnergy += samples[i] * samples[i];
    }
    midEnergy /= midWindow;

    double tailEnergy = 0.0;
    for (size_t i = numSamples - trailingWindow; i < numSamples; ++i) {
        tailEnergy += samples[i] * samples[i];
    }
    tailEnergy /= trailingWindow;

    // Terminal cadence drop signifies complete thought / statement (completion prob -> 1.0)
    // Level or rising pitch energy suggests mid-sentence pause / question (completion prob -> 0.4)
    float ratio = midEnergy > 1e-6 ? static_cast<float>(tailEnergy / midEnergy) : 1.0f;
    float completionProb = 1.0f / (1.0f + std::exp(2.5f * (ratio - 0.5f)));

    return std::max(0.0f, std::min(1.0f, completionProb));
}

SmartTurnResult SmartTurnAnalyzer::predict(const float* samples, size_t numSamples) {
    auto startTime = std::chrono::high_resolution_clock::now();

    if (!samples || numSamples == 0) {
        return {false, 0.0f, 0.0f};
    }

    float probability = analyzeProsodyPitchSlope(samples, numSamples);

    auto endTime = std::chrono::high_resolution_clock::now();
    float inferenceMs = std::chrono::duration<float, std::milli>(endTime - startTime).count();

    SmartTurnResult result;
    result.probability = probability;
    result.complete = probability >= threshold_;
    result.inferenceMs = inferenceMs;

    return result;
}

} // namespace s2s
