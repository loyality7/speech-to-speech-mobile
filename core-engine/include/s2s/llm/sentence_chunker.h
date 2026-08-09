#pragma once

#include "s2s/base_handler.h"
#include "s2s/types.h"
#include <string>
#include <vector>
#include <unordered_set>

namespace s2s {

/**
 * @brief Real-time sentence parser and punctuation boundary splitter.
 * Synchronized with Python speech_to_speech/LLM/lm_output_processor.py.
 */
class SentenceChunker : public BaseHandler<LLMToken, SentenceChunk> {
public:
    SentenceChunker(
        std::shared_ptr<SafeQueue<LLMToken>> queueIn,
        std::shared_ptr<SafeQueue<SentenceChunk>> queueOut,
        std::shared_ptr<CancelScope> cancelScope
    );

    ~SentenceChunker() override;

    bool initialize() override;

    void onSessionEnd() override { reset(); }

    // Reset internal token buffers
    void reset();

protected:
    void process(LLMToken token) override;
    void cleanup() override;

private:
    std::string accumulatedText_;
    bool insideCodeFence_ = false;
    uint32_t currentGenerationId_ = 0;

    // Checks if the character at position 'idx' in 'text' is a valid sentence terminator
    bool isValidSentenceBoundary(const std::string& text, size_t idx);

    // Checks if word preceding a period is a known abbreviation (e.g. Mr., Dr., e.g., i.e.)
    bool isAbbreviation(const std::string& text, size_t dotIdx);

    // Flushes accumulated text as a sentence chunk
    void emitSentence(const std::string& sentence, bool isFinal, uint32_t genId);
};

} // namespace s2s
