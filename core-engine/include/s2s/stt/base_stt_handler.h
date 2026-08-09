#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <string>
#include <vector>
#include <memory>

namespace s2s {
namespace stt {

/**
 * @brief Base STT Handler interface.
 * Synchronized with Python speech_to_speech/STT/base_stt_handler.py.
 */
class BaseSTTHandler : public BaseHandler<SpeechSegment, STTTranscript> {
public:
    BaseSTTHandler(
        const std::string& name,
        std::shared_ptr<SafeQueue<SpeechSegment>> queueIn,
        std::shared_ptr<SafeQueue<STTTranscript>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config
    )
        : BaseHandler(name, std::move(queueIn), std::move(queueOut), std::move(cancelScope))
        , config_(config)
    {}

    virtual ~BaseSTTHandler() = default;

    virtual std::string transcribe(const std::vector<float>& pcmSamples) = 0;

protected:
    EngineConfig config_;
};

} // namespace stt
} // namespace s2s
