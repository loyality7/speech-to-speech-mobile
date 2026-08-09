#pragma once

#include "s2s/arguments/audio_arguments.h"
#include "s2s/arguments/vad_arguments.h"
#include "s2s/arguments/stt_arguments.h"
#include "s2s/arguments/llm_arguments.h"
#include "s2s/arguments/tts_arguments.h"

namespace s2s {

/**
 * @brief Unified Engine Arguments aggregating all subsystem argument classes.
 * Synchronized with Python ModuleArguments / ParsedArguments.
 */
struct EngineArguments {
    AudioArguments audio;
    VADArguments vad;
    STTArguments stt;
    LLMArguments llm;
    TTSArguments tts;
};

// Alias for backwards compatibility with EngineConfig
using EngineConfig = EngineArguments;

} // namespace s2s
