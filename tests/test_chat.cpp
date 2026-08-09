#include "s2s/llm/chat_history.h"
#include <iostream>
#include <cassert>

namespace s2s {
namespace test {

bool testChatHistoryBasics() {
    std::cout << "[TEST] Running testChatHistoryBasics..." << std::endl;

    ChatHistory history("You are a helpful assistant.", 4);
    assert(history.getMessages().empty());

    history.addUserMessage("Hello!");
    history.addAssistantMessage("Hi there! How can I help you today?");

    const auto& msgs = history.getMessages();
    assert(msgs.size() == 2);
    assert(msgs[0].role == "user" && msgs[0].content == "Hello!");
    assert(msgs[1].role == "assistant" && msgs[1].content == "Hi there! How can I help you today?");

    std::string prompt = history.buildPrompt();
    assert(prompt.find("<|im_start|>system\nYou are a helpful assistant.<|im_end|>") != std::string::npos);
    assert(prompt.find("<|im_start|>user\nHello!<|im_end|>") != std::string::npos);
    assert(prompt.find("<|im_start|>assistant\n") != std::string::npos);

    std::cout << "  -> testChatHistoryBasics PASSED!" << std::endl;
    return true;
}

bool testChatHistoryCompaction() {
    std::cout << "[TEST] Running testChatHistoryCompaction (Bounded Turn Memory)..." << std::endl;

    // Max 2 user turns
    ChatHistory history("System Prompt", 2);

    history.addUserMessage("Turn 1");
    history.addAssistantMessage("Reply 1");

    history.addUserMessage("Turn 2");
    history.addAssistantMessage("Reply 2");

    assert(history.getMessages().size() == 4);

    // Adding 3rd user turn should trigger compaction of Turn 1
    history.addUserMessage("Turn 3");
    history.addAssistantMessage("Reply 3");

    const auto& msgs = history.getMessages();
    assert(msgs.size() == 4); // Still 4 messages (Turn 2 + Turn 3)
    assert(msgs[0].content == "Turn 2");
    assert(msgs[1].content == "Reply 2");
    assert(msgs[2].content == "Turn 3");
    assert(msgs[3].content == "Reply 3");

    std::cout << "  -> testChatHistoryCompaction PASSED!" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
