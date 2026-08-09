#include "s2s/s2s_engine.h"
#include <iostream>

namespace s2s {

SpeechToSpeechEngine::SpeechToSpeechEngine(const EngineConfig& config)
    : config_(config) {
    // Allocate thread-safe bounded queues
    rawAudioQueue_ = std::make_shared<SafeQueue<AudioChunk>>(500);
    vadSpeechQueue_ = std::make_shared<SafeQueue<SpeechSegment>>(100);
    sttTextQueue_ = std::make_shared<SafeQueue<STTTranscript>>(50);
    llmTokenQueue_ = std::make_shared<SafeQueue<LLMToken>>(500);
    sentenceQueue_ = std::make_shared<SafeQueue<SentenceChunk>>(50);
    ttsAudioOutputQueue_ = std::make_shared<SafeQueue<AudioChunk>>(200);

    // Instantiate controllers
    cancelScope_ = std::make_shared<CancelScope>();
    chatHistory_ = std::make_shared<ChatHistory>(config.llm.systemPrompt);
    toolRegistry_ = std::make_shared<ToolRegistry>();

    // Instantiate handlers
    vadHandler_ = std::make_unique<VADHandler>(rawAudioQueue_, vadSpeechQueue_, cancelScope_, config_);
    sttHandler_ = std::make_unique<STTHandler>(vadSpeechQueue_, sttTextQueue_, cancelScope_, config_);
    llmHandler_ = std::make_unique<LLMHandler>(sttTextQueue_, llmTokenQueue_, cancelScope_, config_, chatHistory_, toolRegistry_);
    sentenceChunker_ = std::make_unique<SentenceChunker>(llmTokenQueue_, sentenceQueue_, cancelScope_);
    ttsHandler_ = std::make_unique<TTSHandler>(sentenceQueue_, ttsAudioOutputQueue_, cancelScope_, config_);
}

SpeechToSpeechEngine::~SpeechToSpeechEngine() {
    stop();
}

bool SpeechToSpeechEngine::initialize() {
    std::cout << "==================================================" << std::endl;
    std::cout << "   Initializing 100% On-Device S2S Core Engine    " << std::endl;
    std::cout << "==================================================" << std::endl;

    if (!vadHandler_->initialize() ||
        !sttHandler_->initialize() ||
        !llmHandler_->initialize() ||
        !sentenceChunker_->initialize() ||
        !ttsHandler_->initialize()) {
        if (errorCb_) errorCb_("Failed to initialize one or more engine modules.");
        return false;
    }
    std::cout << "[S2SEngine] All 5 Pipeline Handlers successfully initialized." << std::endl;
    return true;
}

bool SpeechToSpeechEngine::start() {
    rawAudioQueue_->restart();
    vadSpeechQueue_->restart();
    sttTextQueue_->restart();
    llmTokenQueue_->restart();
    sentenceQueue_->restart();
    ttsAudioOutputQueue_->restart();

    vadHandler_->start();
    sttHandler_->start();
    llmHandler_->start();
    sentenceChunker_->start();
    ttsHandler_->start();

    isDispatching_ = true;
    outputDispatchThread_ = std::thread(&SpeechToSpeechEngine::outputDispatchLoop, this);

    setState(EngineState::IDLE);
    std::cout << "[S2SEngine] Pipeline threads running in background." << std::endl;
    return true;
}

void SpeechToSpeechEngine::stop() {
    isDispatching_ = false;
    if (ttsAudioOutputQueue_) ttsAudioOutputQueue_->stop();

    if (outputDispatchThread_.joinable()) {
        outputDispatchThread_.join();
    }

    if (vadHandler_) vadHandler_->stop();
    if (sttHandler_) sttHandler_->stop();
    if (llmHandler_) llmHandler_->stop();
    if (sentenceChunker_) sentenceChunker_->stop();
    if (ttsHandler_) ttsHandler_->stop();

    setState(EngineState::IDLE);
}

void SpeechToSpeechEngine::feedAudioInput(const float* pcmData, size_t sampleCount) {
    if (!pcmData || sampleCount == 0 || !rawAudioQueue_) return;

    // Half-duplex echo gate: If assistant is speaking or thinking, ignore mic input
    EngineState cur = state_.load();
    if (cur == EngineState::SPEAKING || cur == EngineState::GENERATING_RESPONSE) {
        return;
    }

    AudioChunk chunk;
    chunk.samples.assign(pcmData, pcmData + sampleCount);
    chunk.sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    chunk.generationId = cancelScope_->getGeneration();
    chunk.isSpeech = false;
    chunk.timestampMs = 0;

    rawAudioQueue_->push(std::move(chunk));
}

void SpeechToSpeechEngine::feedAudioInput(const int16_t* pcmData, size_t sampleCount) {
    if (!pcmData || sampleCount == 0) return;

    std::vector<float> floatSamples(sampleCount);
    for (size_t i = 0; i < sampleCount; ++i) {
        floatSamples[i] = static_cast<float>(pcmData[i]) / 32768.0f;
    }
    feedAudioInput(floatSamples.data(), sampleCount);
}

void SpeechToSpeechEngine::feedTextPrompt(const std::string& text) {
    if (text.empty() || !sttTextQueue_) return;

    STTTranscript transcript;
    transcript.text = text;
    transcript.detectedLanguage = "en";
    transcript.isFinal = true;
    transcript.generationId = cancelScope_ ? cancelScope_->getGeneration() : 0;

    setState(EngineState::GENERATING_RESPONSE);
    sttTextQueue_->push(std::move(transcript));
}

void SpeechToSpeechEngine::interrupt() {
    cancelScope_->cancel();
    // Flush downstream queues instantly
    llmTokenQueue_->clear();
    sentenceQueue_->clear();
    ttsAudioOutputQueue_->clear();
    setState(EngineState::INTERRUPTED);
}

void SpeechToSpeechEngine::resetConversation() {
    interrupt();
    if (chatHistory_) {
        chatHistory_->clear();
    }
    if (rawAudioQueue_) rawAudioQueue_->clear();
    if (vadSpeechQueue_) vadSpeechQueue_->clear();
    if (sttTextQueue_) sttTextQueue_->clear();
    if (llmTokenQueue_) llmTokenQueue_->clear();
    if (sentenceQueue_) sentenceQueue_->clear();
    if (ttsAudioOutputQueue_) ttsAudioOutputQueue_->clear();

    if (vadHandler_) vadHandler_->onSessionEnd();
    if (sttHandler_) sttHandler_->onSessionEnd();
    if (llmHandler_) llmHandler_->onSessionEnd();
    if (sentenceChunker_) sentenceChunker_->onSessionEnd();
    if (ttsHandler_) ttsHandler_->onSessionEnd();

    setState(EngineState::IDLE);
}

void SpeechToSpeechEngine::setSystemPrompt(const std::string& prompt) {
    config_.llm.systemPrompt = prompt;
    if (chatHistory_) {
        chatHistory_->setSystemPrompt(prompt);
    }
}

void SpeechToSpeechEngine::registerTool(const ToolDefinition& def, ToolFunction func) {
    if (toolRegistry_) {
        toolRegistry_->registerTool(def, std::move(func));
    }
}

void SpeechToSpeechEngine::setStateCallback(StateCallback cb) { stateCb_ = std::move(cb); }
void SpeechToSpeechEngine::setTranscriptCallback(TranscriptCallback cb) { transcriptCb_ = std::move(cb); }
void SpeechToSpeechEngine::setAudioOutputCallback(AudioOutputCallback cb) { audioOutCb_ = std::move(cb); }
void SpeechToSpeechEngine::setErrorCallback(ErrorCallback cb) { errorCb_ = std::move(cb); }

EngineState SpeechToSpeechEngine::getState() const { return state_.load(); }
const EngineConfig& SpeechToSpeechEngine::getConfig() const { return config_; }

void SpeechToSpeechEngine::setState(EngineState newState) {
    state_ = newState;
    if (stateCb_) {
        stateCb_(newState);
    }
}

void SpeechToSpeechEngine::outputDispatchLoop() {
    while (isDispatching_) {
        auto audioChunkOpt = ttsAudioOutputQueue_->popWithTimeout(50);
        if (audioChunkOpt.has_value()) {
            auto& chunk = audioChunkOpt.value();
            if (!cancelScope_->isStale(chunk.generationId)) {
                setState(EngineState::SPEAKING);
                if (audioOutCb_) {
                    audioOutCb_(chunk.samples, chunk.generationId);
                }
            }
        }
    }
}

} // namespace s2s
