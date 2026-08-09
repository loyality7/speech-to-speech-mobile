#pragma once

#include <string>
#include <vector>
#include <mutex>

namespace s2s {

struct ChatMessage {
    std::string role; // "system", "user", "assistant"
    std::string content;
};

/**
 * @brief Thread-safe conversation history manager with bounded context window.
 * Synchronized with Python speech_to_speech/LLM/chat.py.
 */
class ChatHistory {
public:
    explicit ChatHistory(std::string systemPrompt = "", size_t maxTurns = 8);

    void setSystemPrompt(const std::string& prompt);
    void addUserMessage(const std::string& text);
    void addAssistantMessage(const std::string& text);
    
    // Builds formatted prompt for llama.cpp / Ollama using standard ChatML template
    std::string buildPrompt() const;

    // Compacts old history if it exceeds max turns to conserve mobile RAM / KV-cache
    void compactHistoryIfNeeded();

    void clear();

    const std::vector<ChatMessage>& getMessages() const;

private:
    std::string systemPrompt_;
    size_t maxTurns_;
    std::vector<ChatMessage> messages_;
    mutable std::mutex mutex_;
};

} // namespace s2s
