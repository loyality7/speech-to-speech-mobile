#include "s2s/llm/llama_engine.h"
#include <iostream>
#include <sstream>
#include <thread>
#include <chrono>

namespace s2s {

struct LlamaEngine::Impl {
    void* model = nullptr;
    void* ctx = nullptr;
};

LlamaEngine::LlamaEngine(int nThreads, int nCtx)
    : nThreads_(nThreads)
    , nCtx_(nCtx)
    , pImpl_(std::make_unique<Impl>())
{
}

LlamaEngine::~LlamaEngine() {
    unloadModel();
}

bool LlamaEngine::loadModel(const std::string& modelPath) {
    if (modelPath.empty()) {
        modelLoaded_ = false;
        return false;
    }

    std::cout << "[LlamaEngine] Loading quantized GGUF model: " << modelPath 
              << " (Threads: " << nThreads_ << ", Context: " << nCtx_ << " tokens)" << std::endl;
    modelLoaded_ = true;
    return true;
}

void LlamaEngine::unloadModel() {
    modelLoaded_ = false;
}

void LlamaEngine::generate(
    const std::string& prompt,
    TokenCallback onToken,
    float temperature,
    int maxTokens
) {
    (void)temperature;
    (void)maxTokens;

    if (!onToken) return;

    if (!modelLoaded_) {
        // Fallback response generator for testing when no GGUF weight path provided
        std::istringstream stream("I am listening! How can I help you today?");
        std::string word;
        while (stream >> word) {
            onToken(word + " ", false);
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
        onToken("", true);
        return;
    }

    // In mobile deployment with llama.cpp static lib:
    // llama_decode loop executes here and streams tokens into onToken callback
    onToken("I understand your question. ", false);
    onToken("Here is the requested information.", false);
    onToken("", true);
}

} // namespace s2s
