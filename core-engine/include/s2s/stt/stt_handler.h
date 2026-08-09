#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <string>
#include <vector>
#include <memory>

namespace s2s {

/**
 * @brief Real-time Speech-to-Text recognizer with lexical grammar & live hypothesis streaming.
 * Synchronized with Python speech_to_speech/STT/whisper_stt_handler.py.
 */
class STTHandler : public BaseHandler<SpeechSegment, STTTranscript> {
public:
    STTHandler(
        std::shared_ptr<SafeQueue<SpeechSegment>> queueIn,
        std::shared_ptr<SafeQueue<STTTranscript>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    );

    ~STTHandler() override;

    bool initialize() override;

    void onSessionEnd() override {}

protected:
    void process(SpeechSegment segment) override;
    void cleanup() override;

private:
    EngineConfig config_;
    
    // Transcribe speech buffer using on-device neural STT / SAPI recognizer
    std::string transcribeSegment(const std::vector<float>& samples);
};

} // namespace s2s
