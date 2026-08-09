#pragma once

#include "s2s/arguments/engine_arguments.h"
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <memory>

namespace s2s {

using GenerationId = uint32_t;

enum class EngineState {
    IDLE,
    LISTENING,
    PROCESSING_SPEECH,
    GENERATING_RESPONSE,
    SPEAKING,
    INTERRUPTED
};

struct AudioConfig {
    int sampleRate = 16000;
    int channels = 1;
    int frameSizeSamples = 512; // 32ms at 16kHz
};

struct AudioChunk {
    std::vector<float> samples;
    int sampleRate = 16000;
    GenerationId generationId = 0;
    bool isSpeech = false;
    int64_t timestampMs = 0;
};

struct SpeechSegment {
    std::vector<float> samples;
    int sampleRate = 16000;
    bool isFinal = true;
    GenerationId generationId = 0;
    int64_t timestampMs = 0;
};

struct STTTranscript {
    std::string text;
    std::string detectedLanguage = "en";
    bool isFinal = true;
    GenerationId generationId = 0;
};

struct LLMToken {
    std::string text;
    bool isFinal = false;
    GenerationId generationId = 0;
};

struct SentenceChunk {
    std::string text;
    bool isFinal = false;
    GenerationId generationId = 0;
};

// Event callback signatures
using StateCallback = std::function<void(EngineState)>;
using TranscriptCallback = std::function<void(const std::string& text, bool isUser, bool isFinal)>;
using AudioOutputCallback = std::function<void(const std::vector<float>& pcmSamples, GenerationId genId)>;
using ErrorCallback = std::function<void(const std::string& error)>;

} // namespace s2s
