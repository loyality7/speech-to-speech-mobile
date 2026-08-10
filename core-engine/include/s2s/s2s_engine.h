#pragma once

#include "s2s/types.h"
#include "s2s/safe_queue.h"
#include "s2s/cancel_scope.h"
#include "s2s/chat_history.h"
#include "s2s/tool_registry.h"
#include "s2s/vad_handler.h"
#include "s2s/stt_handler.h"
#include "s2s/llm_handler.h"
#include "s2s/sentence_chunker.h"
#include "s2s/tts_handler.h"

#include <memory>
#include <thread>
#include <atomic>

namespace s2s {

class SpeechToSpeechEngine {
public:
    explicit SpeechToSpeechEngine(const EngineConfig& config);
    ~SpeechToSpeechEngine();

    // Initialize all AI model runtimes and pipelines
    bool initialize();

    // Start pipeline audio ingestion and background workers
    bool start();

    // Stop pipeline
    void stop();

    // Feed raw microphone PCM float audio chunk (e.g. from Oboe / AVAudioEngine)
    void feedAudioInput(const float* pcmData, size_t sampleCount);

    // Feed raw microphone PCM int16 audio chunk
    void feedAudioInput(const int16_t* pcmData, size_t sampleCount);

    // Feed direct text message into the pipeline
    void feedTextPrompt(const std::string& text);

    // Manually interrupt the assistant (barge-in)
    void interrupt();

    // Reset conversation history
    void resetConversation();

    // Set new system prompt instructions
    void setSystemPrompt(const std::string& prompt);

    // Register a native mobile tool / device action
    void registerTool(const ToolDefinition& def, ToolFunction func);

    // Event Callbacks
    void setStateCallback(StateCallback cb);
    void setTranscriptCallback(TranscriptCallback cb);
    void setAudioOutputCallback(AudioOutputCallback cb);
    void setErrorCallback(ErrorCallback cb);
    void setTTSSynthesizeCallback(std::function<std::vector<float>(const std::string&)> cb);
    void setSTTTranscribeCallback(std::function<std::string(const std::vector<float>&)> cb);

    EngineState getState() const;
    const EngineConfig& getConfig() const;

private:
    EngineConfig config_;
    std::atomic<EngineState> state_{EngineState::IDLE};

    // Shared thread-safe queues
    std::shared_ptr<SafeQueue<AudioChunk>> rawAudioQueue_;
    std::shared_ptr<SafeQueue<SpeechSegment>> vadSpeechQueue_;
    std::shared_ptr<SafeQueue<STTTranscript>> sttTextQueue_;
    std::shared_ptr<SafeQueue<LLMToken>> llmTokenQueue_;
    std::shared_ptr<SafeQueue<SentenceChunk>> sentenceQueue_;
    std::shared_ptr<SafeQueue<AudioChunk>> ttsAudioOutputQueue_;

    // Core controllers
    std::shared_ptr<CancelScope> cancelScope_;
    std::shared_ptr<ChatHistory> chatHistory_;
    std::shared_ptr<ToolRegistry> toolRegistry_;

    // Handlers
    std::unique_ptr<VADHandler> vadHandler_;
    std::unique_ptr<STTHandler> sttHandler_;
    std::unique_ptr<LLMHandler> llmHandler_;
    std::unique_ptr<SentenceChunker> sentenceChunker_;
    std::unique_ptr<TTSHandler> ttsHandler_;

    // Audio output dispatch thread
    std::thread outputDispatchThread_;
    std::atomic<bool> isDispatching_{false};
    void outputDispatchLoop();

    // Callbacks
    StateCallback stateCb_;
    TranscriptCallback transcriptCb_;
    AudioOutputCallback audioOutCb_;
    ErrorCallback errorCb_;

    void setState(EngineState newState);
};

} // namespace s2s
