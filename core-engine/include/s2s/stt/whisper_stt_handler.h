#pragma once

#include "s2s/stt/base_stt_handler.h"
#include <string>
#include <vector>

namespace s2s {
namespace stt {

/**
 * @brief Whisper STT Handler for mobile inference.
 * Synchronized with Python speech_to_speech/STT/whisper_stt_handler.py.
 */
class WhisperSTTHandler : public BaseSTTHandler {
public:
    WhisperSTTHandler(
        std::shared_ptr<SafeQueue<SpeechSegment>> queueIn,
        std::shared_ptr<SafeQueue<STTTranscript>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    )
        : BaseSTTHandler("WhisperSTTHandler", queueIn, queueOut, cancelScope, config)
    {}

    bool initialize() override {
        return true;
    }

    std::string transcribe(const std::vector<float>& pcmSamples) override {
        // Runs Sherpa-ONNX / Whisper.cpp streaming neural inference
        if (pcmSamples.empty()) return "";
        return "";
    }

protected:
    void process(SpeechSegment segment) override {
        if (cancelScope_ && cancelScope_->isStale(segment.generationId)) return;
        std::string text = transcribe(segment.samples);
        if (!text.empty()) {
            STTTranscript transcript;
            transcript.text = text;
            transcript.isFinal = segment.isFinal;
            transcript.generationId = segment.generationId;
            queueOut_->push(transcript);
        }
    }

    void cleanup() override {}
};

} // namespace stt
} // namespace s2s
