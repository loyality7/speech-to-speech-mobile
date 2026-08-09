#include "s2s/vad_handler.h"
#include <iostream>
#include <cmath>
#include <numeric>
#include <algorithm>

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
    threshold_ = config_.vad.threshold > 0.0f ? config_.vad.threshold : 0.5f;
    negThreshold_ = threshold_ - 0.15f; // Hysteresis threshold
    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    minSilenceSamples_ = (config_.vad.minSilenceMs * sampleRate) / 1000;
    speechPadSamples_ = (config_.vad.speechPadMs * sampleRate) / 1000;
    minSpeechSamples_ = (config_.vad.minSpeechMs * sampleRate) / 1000;
}

VADHandler::~VADHandler() {
    stop();
}

bool VADHandler::initialize() {
    resetState();
    std::cout << "[VADHandler] Initialized VAD State Machine (Threshold: " << threshold_ 
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

// Evaluates speech probability using high-precision RMS signal energy with logarithmic dynamic range
float VADHandler::evaluateFrame(const std::vector<float>& frame) {
    if (frame.empty()) return 0.0f;

    double sumSquares = 0.0;
    for (float sample : frame) {
        sumSquares += static_cast<double>(sample) * static_cast<double>(sample);
    }
    float rms = static_cast<float>(std::sqrt(sumSquares / frame.size()));

    // Normalized sigmoid speech probability curve
    // Adjusted for real mobile and laptop mics:
    // Typical voice RMS is > 0.040, ambient fan/room noise is < 0.018
    float k = 150.0f; // Smooth steepness
    float x0 = 0.035f; // Midpoint threshold (rejects room ambient noise)
    float prob = 1.0f / (1.0f + std::exp(-k * (rms - x0)));
    return prob;
}

void VADHandler::process(AudioChunk chunk) {
    if (chunk.samples.empty()) return;

    // Ignore microphone input when assistant is speaking through speakers
    if (cancelScope_ && cancelScope_->isSpeaking()) {
        speechBuffer_.clear();
        triggered_ = false;
        return;
    }

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

            // Only trigger Barge-In cancellation if assistant is actively speaking!
            if (cancelScope_ && cancelScope_->isSpeaking()) {
                cancelScope_->cancel();
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

        if (speechProb < negThreshold_) {
            // Silence frame detected (below hysteresis threshold)
            continuousSilenceSamples_ += frameSamples;
            state_ = VADState::SILENCE_COUNTING;

            // Check if silence exceeded the minimum silence duration (300ms)
            if (continuousSilenceSamples_ >= minSilenceSamples_) {
                // Speech turn completed!
                if (currentSpeechSamples_ >= minSpeechSamples_) {
                    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
                    std::cout << "[VADHandler] Utterance finished (" << currentSpeechSamples_ 
                              << " samples, " << (currentSpeechSamples_ * 1000 / sampleRate) 
                              << "ms). Emitting segment." << std::endl;
                    finalizeUtterance(true);
                } else {
                    std::cout << "[VADHandler] Utterance too short (" << currentSpeechSamples_ 
                              << " samples < " << minSpeechSamples_ << "). Dropped as noise." << std::endl;
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
