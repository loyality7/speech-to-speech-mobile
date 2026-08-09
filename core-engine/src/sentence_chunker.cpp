#include "s2s/sentence_chunker.h"
#include <iostream>
#include <algorithm>
#include <cctype>
#include <unordered_set>

namespace s2s {

// Common English abbreviations that should NOT trigger a sentence split
static const std::unordered_set<std::string> ABBREVIATIONS = {
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr",
    "vs", "etc", "eg", "ie", "al", "approx",
    "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
};

SentenceChunker::SentenceChunker(
    std::shared_ptr<SafeQueue<LLMToken>> queueIn,
    std::shared_ptr<SafeQueue<SentenceChunk>> queueOut,
    std::shared_ptr<CancelScope> cancelScope
)
    : BaseHandler("SentenceChunker", queueIn, queueOut, cancelScope)
{
}

SentenceChunker::~SentenceChunker() {
    stop();
}

bool SentenceChunker::initialize() {
    reset();
    std::cout << "[SentenceChunker] Initialized Real-Time Sentence Parser" << std::endl;
    return true;
}

void SentenceChunker::reset() {
    accumulatedText_.clear();
    insideCodeFence_ = false;
    currentGenerationId_ = 0;
}

bool SentenceChunker::isAbbreviation(const std::string& text, size_t dotIdx) {
    if (dotIdx == 0) return false;

    // Check for decimal number (e.g. 3.14)
    if (dotIdx > 0 && dotIdx + 1 < text.size()) {
        if (std::isdigit(text[dotIdx - 1]) && std::isdigit(text[dotIdx + 1])) {
            return true;
        }
    }

    // Find start of the word preceding the dot
    size_t start = dotIdx - 1;
    while (start > 0 && std::isalpha(text[start - 1])) {
        start--;
    }

    std::string word = text.substr(start, dotIdx - start);
    // Convert to lowercase
    std::transform(word.begin(), word.end(), word.begin(), [](unsigned char c) {
        return std::tolower(c);
    });

    return ABBREVIATIONS.find(word) != ABBREVIATIONS.end();
}

bool SentenceChunker::isValidSentenceBoundary(const std::string& text, size_t idx) {
    if (idx >= text.size()) return false;
    char c = text[idx];

    // CJK Full-width sentence terminators
    if (static_cast<unsigned char>(c) >= 0xE0) {
        // UTF-8 multi-byte check for CJK punctuation: 。 (E3 80 82), ！ (EF BC 81), ？ (EF BC 9F)
        if (idx + 2 < text.size()) {
            std::string utf8char = text.substr(idx, 3);
            if (utf8char == "。" || utf8char == "！" || utf8char == "？") {
                return true;
            }
        }
    }

    // Newlines are always boundary points
    if (c == '\n') return true;

    // Standard ASCII punctuation
    if (c == '!' || c == '?') {
        return true;
    }

    if (c == '.') {
        // Check if abbreviation or decimal
        if (isAbbreviation(text, idx)) {
            return false;
        }
        // Ensure followed by whitespace or end of string
        if (idx + 1 < text.size() && !std::isspace(text[idx + 1])) {
            return false;
        }
        return true;
    }

    // Low-latency clause split for long sentences (> 70 chars): split on comma/semicolon/colon
    if ((c == ',' || c == ';' || c == ':') && text.size() > 70) {
        if (idx + 1 < text.size() && std::isspace(text[idx + 1])) {
            return true;
        }
    }

    return false;
}

void SentenceChunker::emitSentence(const std::string& sentence, bool isFinal, uint32_t genId) {
    // Trim leading/trailing whitespace
    size_t start = sentence.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return;
    size_t end = sentence.find_last_not_of(" \t\r\n");
    std::string clean = sentence.substr(start, end - start + 1);

    if (clean.empty()) return;

    SentenceChunk chunk;
    chunk.text = clean;
    chunk.generationId = genId;
    chunk.isFinal = isFinal;

    std::cout << "[SentenceChunker] Emitting Chunk -> \"" << clean << "\" (Gen: " << genId << ")" << std::endl;
    queueOut_->push(chunk);
}

void SentenceChunker::process(LLMToken token) {
    // Check if token is stale due to barge-in
    if (cancelScope_ && cancelScope_->isStale(token.generationId)) {
        reset();
        return;
    }

    // Detect generation change
    if (currentGenerationId_ != token.generationId) {
        reset();
        currentGenerationId_ = token.generationId;
    }

    // Handle end of turn signal
    if (token.isFinal) {
        if (!accumulatedText_.empty()) {
            emitSentence(accumulatedText_, true, token.generationId);
            accumulatedText_.clear();
        }
        return;
    }

    // Append token to stream accumulator
    accumulatedText_ += token.text;

    // Check for Markdown code fence toggle (```)
    if (accumulatedText_.find("```") != std::string::npos) {
        insideCodeFence_ = !insideCodeFence_;
    }

    // Do not split inside code blocks
    if (insideCodeFence_) {
        return;
    }

    // Scan for valid sentence boundary
    for (size_t i = 0; i < accumulatedText_.size(); ++i) {
        if (isValidSentenceBoundary(accumulatedText_, i)) {
            // Found boundary! Split sentence
            size_t splitLen = i + 1;
            
            std::string sentence = accumulatedText_.substr(0, splitLen);
            accumulatedText_ = accumulatedText_.substr(splitLen);

            emitSentence(sentence, false, token.generationId);
            break;
        }
    }
}

void SentenceChunker::cleanup() {
    reset();
    std::cout << "[SentenceChunker] Cleanup completed." << std::endl;
}

} // namespace s2s
