#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include "s2s/llm/chat_history.h"
#include "s2s/llm/tool_registry.h"
#include <string>
#include <memory>

namespace s2s {

/**
 * @brief Real-time LLM inference client with streaming output and socket pooling.
 * Synchronized with Python speech_to_speech/LLM/language_model.py.
 */
class LLMHandler : public BaseHandler<STTTranscript, LLMToken> {
public:
    LLMHandler(
        std::shared_ptr<SafeQueue<STTTranscript>> queueIn,
        std::shared_ptr<SafeQueue<LLMToken>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        const EngineConfig& config,
        std::shared_ptr<ChatHistory> chatHistory = nullptr,
        std::shared_ptr<ToolRegistry> toolRegistry = nullptr
    );

    ~LLMHandler() override;

    bool initialize() override;

protected:
    void process(STTTranscript transcript) override;
    void cleanup() override;

private:
    EngineConfig config_;
    std::shared_ptr<ChatHistory> chatHistory_;
    std::shared_ptr<ToolRegistry> toolRegistry_;
};

} // namespace s2s
