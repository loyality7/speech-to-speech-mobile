#pragma once

#include <string>

namespace s2s {

/**
 * @brief Configuration parameters for Large Language Model (LLM).
 * Synchronized with Python LanguageModelArguments / BaseOpenAIArguments.
 */
struct LLMArguments {
    std::string modelName = "minicpm-v4.6:latest";
    std::string endpoint = "http://127.0.0.1:11434";
    std::string systemPrompt = "You are a direct, extremely fast, natural conversational AI assistant running 100% on-device. Reply in one short spoken sentence with NO thinking tags.";
    float temperature = 0.7f;
    float topP = 0.9f;
    int maxTokens = 256;
    int maxContextTurns = 8;
    int connectTimeoutMs = 5000;
    int readTimeoutMs = 15000;
    int numThreads = 4;
};

} // namespace s2s
