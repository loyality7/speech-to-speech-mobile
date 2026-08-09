#include "s2s/vad/vad_handler.h"
#include "s2s/cancel_scope.h"
#include "s2s/safe_queue.h"
#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>

namespace s2s {
namespace test {

bool testVADSignalDetection() {
    std::cout << "[TEST] Running testVADSignalDetection..." << std::endl;

    auto inQueue = std::make_shared<SafeQueue<AudioChunk>>();
    auto outQueue = std::make_shared<SafeQueue<SpeechSegment>>();
    auto cancelScope = std::make_shared<CancelScope>();

    EngineConfig config;
    config.vad.threshold = 0.45f;
    config.vad.minSilenceMs = 300; // 300ms silence ends turn

    VADHandler vad(inQueue, outQueue, cancelScope, config);
    vad.start();

    int sampleRate = 16000;
    int frameSize = 512; // 32ms

    // 1. Send 5 frames of silence (RMS ~ 0.001)
    for (int f = 0; f < 5; ++f) {
        AudioChunk chunk;
        chunk.samples.assign(frameSize, 0.001f);
        chunk.sampleRate = sampleRate;
        inQueue->push(chunk);
    }

    // 2. Send 20 frames of high-amplitude voice tone (RMS ~ 0.25)
    for (int f = 0; f < 20; ++f) {
        AudioChunk chunk;
        chunk.samples.resize(frameSize);
        for (int i = 0; i < frameSize; ++i) {
            chunk.samples[i] = 0.35f * std::sin(2.0f * 3.14159f * 440.0f * (f * frameSize + i) / sampleRate);
        }
        chunk.sampleRate = sampleRate;
        inQueue->push(chunk);
    }

    // 3. Send 15 frames of silence to trigger turn-end
    for (int f = 0; f < 15; ++f) {
        AudioChunk chunk;
        chunk.samples.assign(frameSize, 0.0005f);
        chunk.sampleRate = sampleRate;
        inQueue->push(chunk);
    }

    // Expect a valid SpeechSegment in outQueue
    auto segment = outQueue->pop(1500);
    assert(segment.has_value());
    assert(!segment->samples.empty());
    assert(segment->isFinal == true);

    vad.stop();
    std::cout << "  -> testVADSignalDetection PASSED! (Segment size: " << segment->samples.size() << " samples)" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
