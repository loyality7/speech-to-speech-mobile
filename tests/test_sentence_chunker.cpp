#include "s2s/llm/sentence_chunker.h"
#include "s2s/cancel_scope.h"
#include "s2s/safe_queue.h"
#include <iostream>
#include <cassert>
#include <thread>
#include <chrono>

namespace s2s {
namespace test {

bool testSentenceChunkerSplitting() {
    std::cout << "[TEST] Running testSentenceChunkerSplitting..." << std::endl;

    auto inQueue = std::make_shared<SafeQueue<LLMToken>>();
    auto outQueue = std::make_shared<SafeQueue<SentenceChunk>>();
    auto cancelScope = std::make_shared<CancelScope>();

    SentenceChunker chunker(inQueue, outQueue, cancelScope);
    chunker.start();

    // Push tokens: "Hello world! How are you today? I am great."
    std::vector<std::string> tokens = {
        "Hello", " world", "! ", "How", " are", " you", " today", "? ", "I", " am", " great", "."
    };

    for (const auto& tok : tokens) {
        LLMToken t;
        t.text = tok;
        t.isFinal = false;
        t.generationId = 1;
        inQueue->push(t);
    }

    // Mark end of response
    LLMToken finalToken;
    finalToken.text = "";
    finalToken.isFinal = true;
    finalToken.generationId = 1;
    inQueue->push(finalToken);

    // Collect sentence chunks from outQueue
    std::vector<std::string> sentences;
    for (int i = 0; i < 3; ++i) {
        auto item = outQueue->pop(1000);
        assert(item.has_value());
        sentences.push_back(item->text);
    }

    assert(sentences.size() == 3);
    assert(sentences[0] == "Hello world!");
    assert(sentences[1] == "How are you today?");
    assert(sentences[2] == "I am great.");

    chunker.stop();
    std::cout << "  -> testSentenceChunkerSplitting PASSED!" << std::endl;
    return true;
}

bool testSentenceChunkerAbbreviationExclusion() {
    std::cout << "[TEST] Running testSentenceChunkerAbbreviationExclusion (e.g. Mr. Dr. etc)..." << std::endl;

    auto inQueue = std::make_shared<SafeQueue<LLMToken>>();
    auto outQueue = std::make_shared<SafeQueue<SentenceChunk>>();
    auto cancelScope = std::make_shared<CancelScope>();

    SentenceChunker chunker(inQueue, outQueue, cancelScope);
    chunker.start();

    // "Dr. Smith went home." should NOT split at "Dr."
    std::vector<std::string> tokens = { "Dr", ". ", "Smith", " went", " home", "." };
    for (const auto& tok : tokens) {
        LLMToken t;
        t.text = tok;
        t.isFinal = false;
        t.generationId = 1;
        inQueue->push(t);
    }

    LLMToken finalToken;
    finalToken.text = "";
    finalToken.isFinal = true;
    finalToken.generationId = 1;
    inQueue->push(finalToken);

    auto item = outQueue->pop(1000);
    assert(item.has_value());
    assert(item->text == "Dr. Smith went home.");

    chunker.stop();
    std::cout << "  -> testSentenceChunkerAbbreviationExclusion PASSED!" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
