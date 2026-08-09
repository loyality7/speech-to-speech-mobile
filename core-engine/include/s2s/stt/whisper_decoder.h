#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <cstdint>

namespace s2s {

/**
 * @brief Whisper Autoregressive Tokenizer and Vocabulary Decoder.
 * Decodes Whisper-Tiny token ID sequences into clean UTF-8 transcriptions.
 */
class WhisperDecoder {
public:
    static constexpr int SOT = 50258;             // <|startoftranscript|>
    static constexpr int EN = 50259;              // <|en|>
    static constexpr int TRANSCRIBE = 50359;      // <|transcribe|>
    static constexpr int NO_TIMESTAMPS = 50363;   // <|notimestamps|>
    static constexpr int EOT = 50257;             // <|endoftranscript|>

    WhisperDecoder();
    ~WhisperDecoder();

    /**
     * @brief Decodes a sequence of Whisper BPE token IDs into text string.
     */
    std::string decode(const std::vector<int>& tokenIds) const;

    /**
     * @brief Generates initial prompt prompt tokens (<|startoftranscript|> <|en|> <|transcribe|> <|notimestamps|>).
     */
    std::vector<int> getInitialPromptTokens(const std::string& language = "en") const;

private:
    std::unordered_map<int, std::string> vocab_;
    void initVocab();
};

} // namespace s2s
