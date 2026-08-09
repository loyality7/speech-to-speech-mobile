#include "s2s/llm/llama_engine.h"
#include <iostream>
#include <string>
#include <vector>

namespace s2s {
namespace test {

bool testLlamaEngineStreaming() {
    std::cout << "[TEST] Running testLlamaEngineStreaming (Streaming token generation)..." << std::endl;

    LlamaEngine engine(2, 512);
    
    std::vector<std::string> tokensReceived;
    bool streamCompleted = false;

    engine.generate(
        "Hello assistant",
        [&](const std::string& token, bool isFinal) {
            if (!token.empty()) {
                tokensReceived.push_back(token);
            }
            if (isFinal) {
                streamCompleted = true;
            }
        }
    );

    if (tokensReceived.empty()) {
        std::cerr << "❌ Failed: LlamaEngine generated no tokens" << std::endl;
        return false;
    }

    if (!streamCompleted) {
        std::cerr << "❌ Failed: LlamaEngine did not emit isFinal signal" << std::endl;
        return false;
    }

    std::cout << "  -> testLlamaEngineStreaming PASSED! (" << tokensReceived.size() 
              << " tokens streamed successfully)" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
