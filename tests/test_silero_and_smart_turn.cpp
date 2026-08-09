#include "s2s/vad/silero_vad.h"
#include "s2s/vad/smart_turn.h"
#include <iostream>
#include <vector>
#include <cmath>

namespace s2s {
namespace test {

bool testSileroAndSmartTurn() {
    std::cout << "[TEST] Running testSileroAndSmartTurn (Silero VAD + SmartTurn v3.2)..." << std::endl;

    // 1. Test SileroVAD Frame Evaluation
    SileroVAD vad(0.5f, 16000);
    
    // Silence frame (512 zeroes)
    std::vector<float> silenceFrame(512, 0.0f);
    float silenceProb = vad.processFrame(silenceFrame.data(), silenceFrame.size());
    if (silenceProb > 0.2f) {
        std::cerr << "❌ Failed: Silence frame gave high speech prob: " << silenceProb << std::endl;
        return false;
    }

    // Active speech frame (512 sine samples @ amplitude 0.2)
    std::vector<float> speechFrame(512, 0.0f);
    for (size_t i = 0; i < speechFrame.size(); ++i) {
        speechFrame[i] = 0.2f * std::sin(2.0f * 3.14159265f * 200.0f * i / 16000.0f);
    }
    float speechProb = vad.processFrame(speechFrame.data(), speechFrame.size());
    if (speechProb < 0.8f) {
        std::cerr << "❌ Failed: Speech frame gave low prob: " << speechProb << std::endl;
        return false;
    }

    // 2. Test SmartTurnAnalyzer Turn-Completion
    SmartTurnAnalyzer turnAnalyzer(0.5f, 16000);
    // Utterance with falling terminal cadence (signaling turn complete)
    std::vector<float> utterance(16000, 0.0f);
    for (size_t i = 0; i < 12000; ++i) {
        utterance[i] = 0.25f * std::sin(2.0f * 3.14159265f * 300.0f * i / 16000.0f);
    }
    // Trailing 4000 samples have decaying low energy
    for (size_t i = 12000; i < 16000; ++i) {
        utterance[i] = 0.02f * std::sin(2.0f * 3.14159265f * 150.0f * i / 16000.0f);
    }

    auto turnResult = turnAnalyzer.predict(utterance.data(), utterance.size());
    if (!turnResult.complete || turnResult.probability < 0.5f) {
        std::cerr << "❌ Failed: SmartTurn failed to detect complete turn (prob=" << turnResult.probability << ")" << std::endl;
        return false;
    }

    std::cout << "  -> testSileroAndSmartTurn PASSED! (Silence Prob: " << silenceProb 
              << ", Speech Prob: " << speechProb 
              << ", Turn Confidence: " << static_cast<int>(turnResult.probability * 100) << "%)" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
