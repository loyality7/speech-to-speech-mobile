#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <deque>
#include <vector>
#include <memory>

namespace s2s {

enum class VADState {
    IDLE,
    PRE_SPEECH,
    TRIGGERED,
    SILENCE_COUNTING
};

/**
 * @brief Real-time Voice Activity Detection state machine with hysteresis.
 * Synchronized with Python speech_to_speech/VAD/vad_handler.py.
 */
class VADHandler : public BaseHandler<AudioChunk, SpeechSegment> {
public:
    VADHandler(
        std::shared_ptr<SafeQueue<AudioChunk>> queueIn,
        std::shared_ptr<SafeQueue<SpeechSegment>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    );

    ~VADHandler() override;

    bool initialize() override;

    void onSessionEnd() override { resetState(); }

    // Runtime parameter adjustments matching Python VADHandlerArguments
    void setThreshold(float threshold);
    void setMinSilenceMs(int ms);
    void setSpeechPadMs(int ms);
    void setMinSpeechMs(int ms);

protected:
    void process(AudioChunk chunk) override;
    void cleanup() override;

private:
    EngineConfig config_;
    
    // Algorithmic Parameters matching Python VADIterator
    float threshold_ = 0.5f;
    float negThreshold_ = 0.35f; // Hysteresis: threshold - 0.15
    int minSilenceSamples_ = 4800; // 300ms @ 16kHz
    int speechPadSamples_ = 480;   // 30ms @ 16kHz
    int minSpeechSamples_ = 6144;  // 384ms @ 16kHz

    // State Tracking
    VADState state_ = VADState::IDLE;
    bool triggered_ = false;
    int continuousSilenceSamples_ = 0;
    int currentSpeechSamples_ = 0;
    int64_t speechStartTimestampMs_ = 0;

    // Rolling Deque Pre-Speech Ring Buffer (preserves initial 30ms plosives/fricatives)
    std::deque<float> preSpeechBuffer_;
    
    // Utterance accumulation buffer
    std::vector<float> speechBuffer_;

    // Advanced Mobile AI Model Evaluators
    std::unique_ptr<class SileroVAD> sileroModel_;
    std::unique_ptr<class SmartTurnAnalyzer> smartTurnModel_;

    void resetState();
    void finalizeUtterance(bool isComplete);
    float evaluateFrame(const std::vector<float>& frame);
};

} // namespace s2s
