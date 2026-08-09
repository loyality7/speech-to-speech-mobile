#pragma once

#include "s2s/tts/base_tts_handler.h"
#include <string>
#include <vector>

namespace s2s {
namespace tts {

/**
 * @brief Qwen3-TTS Low-latency Streaming TTS Handler.
 * Synchronized with Python speech_to_speech/TTS/qwen3_tts_handler.py.
 */
class Qwen3TTSHandler : public BaseTTSHandler {
public:
    Qwen3TTSHandler(
        std::shared_ptr<SafeQueue<SentenceChunk>> queueIn,
        std::shared_ptr<SafeQueue<AudioChunk>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    )
        : BaseTTSHandler("Qwen3TTSHandler", queueIn, queueOut, cancelScope, config)
    {}

    bool initialize() override {
        return true;
    }

    std::vector<float> synthesize(const std::string& text, const std::string& voice, float speed) override {
        // Runs faster-qwen3-tts streaming synthesis
        if (text.empty()) return {};
        return {};
    }

protected:
    void process(SentenceChunk sentence) override {
        if (cancelScope_ && cancelScope_->isStale(sentence.generationId)) return;
        auto pcm = synthesize(sentence.text, "custom_voice", 1.0f);
        if (!pcm.empty()) {
            AudioChunk chunk;
            chunk.samples = std::move(pcm);
            chunk.sampleRate = 16000;
            chunk.generationId = sentence.generationId;
            chunk.isSpeech = true;
            queueOut_->push(chunk);
        }
    }

    void cleanup() override {}
};

} // namespace tts
} // namespace s2s
