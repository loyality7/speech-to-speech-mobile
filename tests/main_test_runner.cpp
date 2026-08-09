#include <iostream>
#include <vector>
#include <string>

namespace s2s {
namespace test {
    bool testChatHistoryBasics();
    bool testChatHistoryCompaction();
    bool testSentenceChunkerSplitting();
    bool testSentenceChunkerAbbreviationExclusion();
    bool testVADSignalDetection();
    bool testSileroAndSmartTurn();
    bool testWhisperSubsystems();
    bool testLlamaEngineStreaming();
    bool testToolRegistrationAndExecution();
    bool testCancelScopeBargeIn();
    bool testSafeQueueConcurrency();
    bool testRealtimeApiEvents();
}
}

int main() {
    std::cout << "===============================================================\n";
    std::cout << "     🧪  SPEECH-TO-SPEECH MOBILE ENGINE TEST SUITE  🧪         \n";
    std::cout << "===============================================================\n\n";

    int passed = 0;
    int total = 0;

    auto runTest = [&](const std::string& name, auto testFunc) {
        total++;
        std::cout << "---------------------------------------------------------------\n";
        try {
            if (testFunc()) {
                passed++;
            } else {
                std::cerr << "❌ [FAILED] " << name << std::endl;
            }
        } catch (const std::exception& e) {
            std::cerr << "💥 [EXCEPTION in " << name << "]: " << e.what() << std::endl;
        } catch (...) {
            std::cerr << "💥 [UNKNOWN EXCEPTION in " << name << "]" << std::endl;
        }
    };

    runTest("Chat History Basics", s2s::test::testChatHistoryBasics);
    runTest("Chat History Compaction (KV-Cache Limit)", s2s::test::testChatHistoryCompaction);
    runTest("Sentence Chunker Splitting", s2s::test::testSentenceChunkerSplitting);
    runTest("Sentence Chunker Abbreviation Protection", s2s::test::testSentenceChunkerAbbreviationExclusion);
    runTest("VAD Signal & Energy Detection", s2s::test::testVADSignalDetection);
    runTest("Silero VAD v5 + SmartTurn v3.2 Prosody", s2s::test::testSileroAndSmartTurn);
    runTest("Whisper STT Log-Mel Features & Decoder", s2s::test::testWhisperSubsystems);
    runTest("LlamaEngine GGUF Streaming Generator", s2s::test::testLlamaEngineStreaming);
    runTest("Tool & Function Call Registry", s2s::test::testToolRegistrationAndExecution);
    runTest("CancelScope & Interruption Barge-In", s2s::test::testCancelScopeBargeIn);
    runTest("SafeQueue Multi-Threaded Concurrency", s2s::test::testSafeQueueConcurrency);
    runTest("OpenAI Realtime API Protocol Events", s2s::test::testRealtimeApiEvents);

    std::cout << "\n===============================================================\n";
    std::cout << "  📊 TEST RESULTS SUMMARY: " << passed << "/" << total << " PASSED (" 
              << (passed * 100 / total) << "%)\n";
    std::cout << "===============================================================\n";

    return (passed == total) ? 0 : 1;
}
