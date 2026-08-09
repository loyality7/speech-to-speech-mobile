#include "s2s/stt/whisper_decoder.h"
#include <sstream>

namespace s2s {

WhisperDecoder::WhisperDecoder() {
    initVocab();
}

WhisperDecoder::~WhisperDecoder() = default;

void WhisperDecoder::initVocab() {
    // Standard Whisper basic token mappings
    vocab_[SOT] = "";
    vocab_[EN] = "";
    vocab_[TRANSCRIBE] = "";
    vocab_[NO_TIMESTAMPS] = "";
    vocab_[EOT] = "";
    vocab_[220] = " ";
    vocab_[13] = "\n";
}

std::vector<int> WhisperDecoder::getInitialPromptTokens(const std::string& language) const {
    (void)language;
    return {SOT, EN, TRANSCRIBE, NO_TIMESTAMPS};
}

std::string WhisperDecoder::decode(const std::vector<int>& tokenIds) const {
    std::string text;
    for (int id : tokenIds) {
        if (id == EOT) break;
        auto it = vocab_.find(id);
        if (it != vocab_.end()) {
            text += it->second;
        }
    }
    return text;
}

} // namespace s2s
