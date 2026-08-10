#include "s2s/vad_handler.h"
#include "s2s/vad/silero_vad.h"
#include "s2s/vad/smart_turn.h"
#include <iostream>
#include <cmath>
#include <numeric>
#include <algorithm>

#ifdef __ANDROID__
#include <android/log.h>
#define VAD_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "S2S_VAD", __VA_ARGS__)
#else
#define VAD_LOGI(...)
#endif

namespace s2s {

VADHandler::VADHandler(
    std::shared_ptr<SafeQueue<AudioChunk>> queueIn,
    std::shared_ptr<SafeQueue<SpeechSegment>> queueOut,
    std::shared_ptr<CancelScope> cancelScope,
    const EngineConfig& config
)
    : BaseHandler("VADHandler", queueIn, queueOut, cancelScope)
    , config_(config)
{
    threshold_ = config_.vad.threshold > 0.0f ? config_.vad.threshold : 0.35f;
    negThreshold_ = threshold_ - 0.15f; // Hysteresis threshold
    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    int minSilenceMs = config_.vad.minSilenceMs > 0 ? config_.vad.minSilenceMs : 250;
    int speechPadMs = config_.vad.speechPadMs > 0 ? config_.vad.speechPadMs : 64;
    int minSpeechMs = (config_.vad.minSpeechMs > 0 && config_.vad.minSpeechMs <= 150) ? config_.vad.minSpeechMs : 100;

    minSilenceSamples_ = (minSilenceMs * sampleRate) / 1000;
    speechPadSamples_ = (speechPadMs * sampleRate) / 1000;
    minSpeechSamples_ = (minSpeechMs * sampleRate) / 1000;

    sileroModel_ = std::make_unique<SileroVAD>(threshold_, sampleRate);
    smartTurnModel_ = std::make_unique<SmartTurnAnalyzer>(0.5f, sampleRate);
}

VADHandler::~VADHandler() {
    stop();
}

bool VADHandler::initialize() {
    resetState();
    if (!config_.vad.modelPath.empty()) {
        sileroModel_->loadModel(config_.vad.modelPath);
    }
    std::cout << "[VADHandler] Initialized Silero VAD v5 State Machine (Threshold: " << threshold_ 
              << ", Hysteresis: " << negThreshold_ 
              << ", MinSilence: " << minSilenceSamples_ << " samples"
              << ", PrePad: " << speechPadSamples_ << " samples)" << std::endl;
    return true;
}

void VADHandler::setThreshold(float threshold) {
    threshold_ = threshold;
    negThreshold_ = threshold_ - 0.15f;
}

void VADHandler::setMinSilenceMs(int ms) {
    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    minSilenceSamples_ = (ms * sampleRate) / 1000;
}

void VADHandler::setSpeechPadMs(int ms) {
    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    speechPadSamples_ = (ms * sampleRate) / 1000;
}

void VADHandler::setMinSpeechMs(int ms) {
    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    minSpeechSamples_ = (ms * sampleRate) / 1000;
}

void VADHandler::resetState() {
    state_ = VADState::IDLE;
    triggered_ = false;
    continuousSilenceSamples_ = 0;
    currentSpeechSamples_ = 0;
    speechStartTimestampMs_ = 0;
    preSpeechBuffer_.clear();
    speechBuffer_.clear();
}

// Evaluates speech probability using Silero VAD v5 neural model / calibrated acoustic engine
float VADHandler::evaluateFrame(const std::vector<float>& frame) {
    if (frame.empty()) return 0.0f;

    if (sileroModel_) {
        return sileroModel_->processFrame(frame.data(), frame.size());
    }

    double sumSquares = 0.0;
    for (float sample : frame) {
        sumSquares += static_cast<double>(sample) * static_cast<double>(sample);
    }
    float rms = static_cast<float>(std::sqrt(sumSquares / frame.size()));

    float k = 300.0f;
    float x0 = 0.008f;
    float prob = 1.0f / (1.0f + std::exp(-k * (rms - x0)));
    return prob;
}

void VADHandler::process(AudioChunk chunk) {
    if (chunk.samples.empty()) return;

    // 1. Maintain rolling pre-speech ring buffer (preserves first 30ms plosives)
    for (float sample : chunk.samples) {
        preSpeechBuffer_.push_back(sample);
    }
    while (preSpeechBuffer_.size() > static_cast<size_t>(speechPadSamples_)) {
        preSpeechBuffer_.pop_front();
    }

    // 2. Compute speech probability for this audio frame
    float speechProb = evaluateFrame(chunk.samples);
    int frameSamples = static_cast<int>(chunk.samples.size());

    // 3. State Machine with Hysteresis & Debounce
    if (!triggered_) {
        // Looking for start of speech
        if (speechProb >= threshold_) {
            triggered_ = true;
            state_ = VADState::TRIGGERED;
            continuousSilenceSamples_ = 0;
            currentSpeechSamples_ = 0;
            speechStartTimestampMs_ = chunk.timestampMs;
            VAD_LOGI("Speech START detected (prob=%.2f >= %.2f)", speechProb, threshold_);

            // Only trigger Barge-In cancellation if assistant is actively speaking!
            if (cancelScope_ && cancelScope_->isSpeaking()) {
                cancelScope_->cancel();
                VAD_LOGI("BARGE-IN: User interrupted assistant speech (New Gen: %u)", cancelScope_->getGeneration());
                std::cout << "\n[VADHandler] >>> BARGE-IN: User interrupted assistant speech (New Gen: " 
                          << cancelScope_->getGeneration() << ") <<<" << std::endl;
            }

            // Copy the pre-speech pad buffer to catch the start of the word
            speechBuffer_.assign(preSpeechBuffer_.begin(), preSpeechBuffer_.end());
            currentSpeechSamples_ += static_cast<int>(preSpeechBuffer_.size());

            // Add current frame samples
            speechBuffer_.insert(speechBuffer_.end(), chunk.samples.begin(), chunk.samples.end());
            currentSpeechSamples_ += frameSamples;
        }
    } else {
        // Currently speaking -> append frame to active utterance
        speechBuffer_.insert(speechBuffer_.end(), chunk.samples.begin(), chunk.samples.end());
        currentSpeechSamples_ += frameSamples;

        // Check if utterance exceeded maximum duration (e.g. 15 seconds) to prevent unbounded memory
        int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
        int maxSpeechSamples = sampleRate * 15; // 15 seconds max chunk
        if (currentSpeechSamples_ >= maxSpeechSamples) {
            VAD_LOGI("Utterance max chunk size reached (15s). Emitting progressive segment.");
            finalizeUtterance(false);
            speechBuffer_.clear();
            currentSpeechSamples_ = 0;
        }

        if (speechProb < negThreshold_) {
            // Silence frame detected (below hysteresis threshold)
            continuousSilenceSamples_ += frameSamples;
            state_ = VADState::SILENCE_COUNTING;

            // Check if silence exceeded the minimum silence duration (300ms)
            if (continuousSilenceSamples_ >= minSilenceSamples_) {
                // Speech turn completed!
                if (currentSpeechSamples_ >= minSpeechSamples_) {
                    VAD_LOGI("Utterance finished (%d samples, %d ms). Emitting segment to STT.",
                             currentSpeechSamples_, (currentSpeechSamples_ * 1000 / sampleRate));
                    finalizeUtterance(true);
                } else {
                    VAD_LOGI("Utterance too short (%d samples < %d). Dropped as noise.",
                             currentSpeechSamples_, minSpeechSamples_);
                }
                resetState();
            }
        } else {
            // Voice active above negative threshold -> reset silence counter
            continuousSilenceSamples_ = 0;
            state_ = VADState::TRIGGERED;
        }
    }
}

void VADHandler::finalizeUtterance(bool isComplete) {
    if (speechBuffer_.empty()) return;

    if (smartTurnModel_ && isComplete) {
        auto turnResult = smartTurnModel_->predict(speechBuffer_.data(), speechBuffer_.size());
        std::cout << "[VADHandler] Smart Turn v3.2 Prosody Check -> " 
                  << (turnResult.complete ? "Turn Finished" : "Mid-sentence pause (Continuing)") 
                  << " (Confidence: " << static_cast<int>(turnResult.probability * 100) 
                  << "%, " << turnResult.inferenceMs << "ms)" << std::endl;
    }

    SpeechSegment segment;
    segment.samples = std::move(speechBuffer_);
    segment.sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    segment.isFinal = isComplete;
    segment.generationId = cancelScope_ ? cancelScope_->getGeneration() : 0;
    segment.timestampMs = speechStartTimestampMs_;

    queueOut_->push(segment);
}

void VADHandler::cleanup() {
    resetState();
}

} // namespace s2s
