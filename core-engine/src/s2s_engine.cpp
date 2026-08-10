#include "s2s/s2s_engine.h"
#include <iostream>

#ifdef __ANDROID__
#include <android/log.h>
#define S2S_LOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "S2S_ENGINE", fmt, ##__VA_ARGS__)
#else
#define S2S_LOG(fmt, ...) do { printf("[S2S_ENGINE] " fmt "\n", ##__VA_ARGS__); } while(0)
#endif

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
    S2S_LOG("=== Initializing S2S Core Engine ===");

    bool vadOk = vadHandler_->initialize();
    S2S_LOG("VADHandler init: %s", vadOk ? "OK" : "FAILED");
    bool sttOk = sttHandler_->initialize();
    S2S_LOG("STTHandler init: %s", sttOk ? "OK" : "FAILED");
    bool llmOk = llmHandler_->initialize();
    S2S_LOG("LLMHandler init: %s", llmOk ? "OK" : "FAILED");
    bool chunkOk = sentenceChunker_->initialize();
    S2S_LOG("SentenceChunker init: %s", chunkOk ? "OK" : "FAILED");
    bool ttsOk = ttsHandler_->initialize();
    S2S_LOG("TTSHandler init: %s", ttsOk ? "OK" : "FAILED");

    if (!vadOk || !sttOk || !llmOk || !chunkOk || !ttsOk) {
        S2S_LOG("FAILED to initialize one or more modules");
        if (errorCb_) errorCb_("Failed to initialize one or more engine modules.");
        return false;
    }
    S2S_LOG("All 5 Pipeline Handlers initialized successfully");
    return true;
}

bool SpeechToSpeechEngine::start() {
    S2S_LOG("start() - restarting all queues");
    rawAudioQueue_->restart();
    vadSpeechQueue_->restart();
    sttTextQueue_->restart();
    llmTokenQueue_->restart();
    sentenceQueue_->restart();
    ttsAudioOutputQueue_->restart();

    S2S_LOG("start() - launching handler threads");
    vadHandler_->start();
    sttHandler_->start();
    llmHandler_->start();
    sentenceChunker_->start();
    ttsHandler_->start();

    isDispatching_ = true;
    outputDispatchThread_ = std::thread(&SpeechToSpeechEngine::outputDispatchLoop, this);

    setState(EngineState::IDLE);
    S2S_LOG("start() - all pipeline threads running, outputDispatch active, audioOutCb_ set=%d", audioOutCb_ ? 1 : 0);
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
    S2S_LOG("feedTextPrompt called, text='%.80s' len=%zu sentenceQueue=%p", text.c_str(), text.size(), (void*)sentenceQueue_.get());
    if (text.empty() || !sentenceQueue_) {
        S2S_LOG("feedTextPrompt REJECTED (empty=%d, queue=%p)", text.empty()?1:0, (void*)sentenceQueue_.get());
        return;
    }

    SentenceChunk chunk;
    chunk.text = text;
    chunk.isFinal = true;
    chunk.generationId = cancelScope_ ? cancelScope_->getGeneration() : 0;

    S2S_LOG("feedTextPrompt pushing SentenceChunk genId=%u to sentenceQueue", chunk.generationId);
    setState(EngineState::SPEAKING);
    sentenceQueue_->push(std::move(chunk));
    S2S_LOG("feedTextPrompt push complete");
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
void SpeechToSpeechEngine::setTranscriptCallback(TranscriptCallback cb) {
    transcriptCb_ = cb;
    if (sttHandler_) {
        sttHandler_->setTranscriptCallback(cb);
    }
}
void SpeechToSpeechEngine::setAudioOutputCallback(AudioOutputCallback cb) { audioOutCb_ = std::move(cb); }
void SpeechToSpeechEngine::setErrorCallback(ErrorCallback cb) { errorCb_ = std::move(cb); }
void SpeechToSpeechEngine::setTTSSynthesizeCallback(std::function<std::vector<float>(const std::string&)> cb) {
    if (ttsHandler_) {
        ttsHandler_->setSynthesizeCallback(std::move(cb));
    }
}
void SpeechToSpeechEngine::setSTTTranscribeCallback(std::function<std::string(const std::vector<float>&)> cb) {
    if (sttHandler_) {
        sttHandler_->setTranscribeCallback(std::move(cb));
    }
}

EngineState SpeechToSpeechEngine::getState() const { return state_.load(); }
const EngineConfig& SpeechToSpeechEngine::getConfig() const { return config_; }

void SpeechToSpeechEngine::setState(EngineState newState) {
    state_ = newState;
    if (stateCb_) {
        stateCb_(newState);
    }
}

void SpeechToSpeechEngine::outputDispatchLoop() {
    S2S_LOG("outputDispatchLoop STARTED, audioOutCb_ set=%d", audioOutCb_ ? 1 : 0);
    int dispatchCount = 0;
    while (isDispatching_) {
        auto audioChunkOpt = ttsAudioOutputQueue_->popWithTimeout(50);
        if (audioChunkOpt.has_value()) {
            auto& chunk = audioChunkOpt.value();
            bool stale = cancelScope_->isStale(chunk.generationId);
            S2S_LOG("outputDispatch: got chunk #%d, %zu samples, genId=%u, stale=%d, hasCb=%d",
                    ++dispatchCount, chunk.samples.size(), chunk.generationId, stale?1:0, audioOutCb_?1:0);
            if (!stale) {
                setState(EngineState::SPEAKING);
                if (audioOutCb_) {
                    audioOutCb_(chunk.samples, chunk.generationId);
                } else {
                    S2S_LOG("outputDispatch: NO audioOutCb_ registered! Audio lost!");
                }
            } else {
                S2S_LOG("outputDispatch: chunk DROPPED (stale genId=%u, current=%u)",
                        chunk.generationId, cancelScope_->getGeneration());
            }
        }
    }
    S2S_LOG("outputDispatchLoop EXITED");
}

} // namespace s2s
