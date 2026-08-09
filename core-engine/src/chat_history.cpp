#include "s2s/chat_history.h"
#include <sstream>
#include <iostream>

namespace s2s {

ChatHistory::ChatHistory(std::string systemPrompt, size_t maxTurns)
    : systemPrompt_(std::move(systemPrompt))
    , maxTurns_(maxTurns > 0 ? maxTurns : 8)
{
    if (systemPrompt_.empty()) {
        systemPrompt_ = "You are a helpful, extremely fast, natural conversational AI assistant running 100% on-device.";
    }
}

void ChatHistory::setSystemPrompt(const std::string& prompt) {
    std::lock_guard<std::mutex> lock(mutex_);
    systemPrompt_ = prompt;
}

void ChatHistory::addUserMessage(const std::string& text) {
    std::lock_guard<std::mutex> lock(mutex_);
    messages_.push_back(ChatMessage{"user", text});
    compactHistoryIfNeeded();
}

void ChatHistory::addAssistantMessage(const std::string& text) {
    std::lock_guard<std::mutex> lock(mutex_);
    messages_.push_back(ChatMessage{"assistant", text});
    compactHistoryIfNeeded();
}

// Builds standard ChatML prompt template for SmolLM2 / Llama-3 / Qwen models:
// <|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n
std::string ChatHistory::buildPrompt() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::stringstream ss;

    // System prompt
    if (!systemPrompt_.empty()) {
        ss << "<|im_start|>system\n" << systemPrompt_ << "<|im_end|>\n";
    }

    // Turns
    for (const auto& msg : messages_) {
        ss << "<|im_start|>" << msg.role << "\n" << msg.content << "<|im_end|>\n";
    }

    // Open assistant turn for streaming generation
    ss << "<|im_start|>assistant\n";
    return ss.str();
}

// Bounded memory management: evicts the oldest user+assistant turn pair if user turns exceed maxTurns_
void ChatHistory::compactHistoryIfNeeded() {
    size_t userTurnCount = 0;
    for (const auto& msg : messages_) {
        if (msg.role == "user") {
            userTurnCount++;
        }
    }

    // If turns exceed max allowed, evict oldest turn boundary (User + Assistant)
    while (userTurnCount > maxTurns_ && !messages_.empty()) {
        if (messages_.front().role == "user") {
            userTurnCount--;
        }
        messages_.erase(messages_.begin());
        // Also remove following assistant message to keep turns paired
        while (!messages_.empty() && messages_.front().role != "user") {
            messages_.erase(messages_.begin());
        }
        std::cout << "[ChatHistory] Evicted oldest conversation turn to conserve mobile KV-cache memory." << std::endl;
    }
}

void ChatHistory::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    messages_.clear();
}

const std::vector<ChatMessage>& ChatHistory::getMessages() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return messages_;
}

} // namespace s2s
