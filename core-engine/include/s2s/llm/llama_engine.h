#pragma once

#include <string>
#include <vector>
#include <functional>
#include <memory>

namespace s2s {

/**
 * @brief Embedded llama.cpp Engine for direct GGUF inference on mobile ARM & GPU.
 * Supports SmolLM2, Qwen2.5, and Llama-3 quantized models (Q4_K_M, Q5_K_M).
 */
class LlamaEngine {
public:
    using TokenCallback = std::function<void(const std::string& token, bool isFinal)>;

    LlamaEngine(int nThreads = 4, int nCtx = 2048);
    ~LlamaEngine();

    bool loadModel(const std::string& modelPath);
    void unloadModel();

    /**
     * @brief Generates streaming response tokens for a formatted prompt.
     * @param prompt Full formatted prompt with system & user turns.
     * @param onToken Callback invoked on each generated token.
     * @param temperature Sampling temperature (default: 0.7).
     * @param maxTokens Maximum tokens to generate (default: 512).
     */
    void generate(
        const std::string& prompt,
        TokenCallback onToken,
        float temperature = 0.7f,
        int maxTokens = 512
    );

    bool isModelLoaded() const { return modelLoaded_; }

private:
    int nThreads_ = 4;
    int nCtx_ = 2048;
    bool modelLoaded_ = false;

    struct Impl;
    std::unique_ptr<Impl> pImpl_;
};

} // namespace s2s
