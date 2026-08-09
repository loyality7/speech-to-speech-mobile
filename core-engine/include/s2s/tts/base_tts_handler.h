#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <string>
#include <vector>
#include <memory>

namespace s2s {
namespace tts {

/**
 * @brief Base TTS Handler interface.
 * Synchronized with Python speech_to_speech/TTS/base_tts_handler.py.
 */
class BaseTTSHandler : public BaseHandler<SentenceChunk, AudioChunk> {
public:
    BaseTTSHandler(
        const std::string& name,
        std::shared_ptr<SafeQueue<SentenceChunk>> queueIn,
        std::shared_ptr<SafeQueue<AudioChunk>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    )
        : BaseHandler(name, std::move(queueIn), std::move(queueOut), std::move(cancelScope))
        , config_(config)
    {}

    virtual ~BaseTTSHandler() = default;

    virtual std::vector<float> synthesize(const std::string& text, const std::string& voice, float speed) = 0;

protected:
    EngineConfig config_;
};

} // namespace tts
} // namespace s2s
