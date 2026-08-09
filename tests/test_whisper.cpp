#include "s2s/stt/whisper_feature_extractor.h"
#include "s2s/stt/whisper_decoder.h"
#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>

namespace s2s {
namespace test {

bool testWhisperSubsystems() {
    std::cout << "[TEST] Running testWhisperSubsystems (Log-Mel Features & Decoder)..." << std::endl;

    // 1. Test Feature Extractor with a 16kHz audio buffer (1 second = 16000 samples)
    WhisperFeatureExtractor extractor;
    std::vector<float> audio(16000, 0.0f);
    
    // Generate a 440Hz test sine wave tone
    for (size_t i = 0; i < audio.size(); ++i) {
        audio[i] = 0.5f * std::sin(2.0f * 3.14159265f * 440.0f * i / 16000.0f);
    }

    auto features = extractor.extractFeatures(audio.data(), audio.size());
    if (features.empty()) {
        std::cerr << "❌ Failed: Whisper feature extractor returned empty mel matrix" << std::endl;
        return false;
    }

    // Number of frames for 16000 samples with hop=160: ~98-100 frames
    size_t expectedChannels = 80;
    if (features.size() < expectedChannels * 50) {
        std::cerr << "❌ Failed: Mel matrix dimension incorrect: " << features.size() << std::endl;
        return false;
    }

    // 2. Test Decoder Initial Prompt & Decoding
    WhisperDecoder decoder;
    auto promptTokens = decoder.getInitialPromptTokens("en");
    if (promptTokens.size() != 4 || promptTokens[0] != WhisperDecoder::SOT) {
        std::cerr << "❌ Failed: Whisper prompt tokens mismatch" << std::endl;
        return false;
    }

    std::cout << "  -> testWhisperSubsystems PASSED! (Extracted " << (features.size() / expectedChannels) 
              << " mel frames, 80 channels)" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
