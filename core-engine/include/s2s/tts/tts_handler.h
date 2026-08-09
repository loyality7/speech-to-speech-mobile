#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <vector>
#include <string>
#include <unordered_map>
#include <memory>

namespace s2s {

/**
 * @brief Real-time Text-to-Speech synthesizer with block streaming & polyphase resampling.
 * Synchronized with Python speech_to_speech/TTS/kokoro_handler.py.
 */
class TTSHandler : public BaseHandler<SentenceChunk, AudioChunk> {
public:
    TTSHandler(
        std::shared_ptr<SafeQueue<SentenceChunk>> queueIn,
        std::shared_ptr<SafeQueue<AudioChunk>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    );

    ~TTSHandler() override;

    bool initialize() override;

    void onSessionEnd() override {}

    // Set active voice and language
    void setVoice(const std::string& voice);
    void setLanguage(const std::string& langCode);
    void setSpeed(float speed);

protected:
    void process(SentenceChunk sentence) override;
    void cleanup() override;

private:
    EngineConfig config_;
    std::string voice_ = "af_heart";
    std::string langCode_ = "a"; // Default American English
    float speed_ = 1.0f;
    int blockSizeSamples_ = 512; // 32ms streaming chunks at 16kHz
    int nativeSampleRate_ = 24000; // Kokoro / Qwen3 native 24kHz

    // Polyphase FIR / Linear Resampler (24kHz -> 16kHz)
    std::vector<float> resamplePoly(const std::vector<float>& input, int inRate, int outRate);

    // Audio Silence Trimming (detects non-silent audio, trims start/end with 5ms padding)
    std::vector<float> trimSilence(const std::vector<float>& input, float threshold, int paddingSamples);

    // Synthesizes text sentence into raw 24kHz audio via neural runtime (Kokoro/Piper ONNX)
    std::vector<float> synthesizeRaw(const std::string& text, const std::string& voice, float speed);

    // Language code to voice resolution
    std::string resolveVoiceForLanguage(const std::string& lang);
};

} // namespace s2s
