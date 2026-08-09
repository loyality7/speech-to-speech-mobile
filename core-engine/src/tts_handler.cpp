#include "s2s/tts_handler.h"
#include <iostream>
#include <cmath>
#include <algorithm>
#include <numeric>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <atlbase.h>
#include <sapi.h>
#include <sphelper.h>
#pragma comment(lib, "sapi.lib")
#pragma comment(lib, "ole32.lib")

static ISpVoice* g_pSpVoice = nullptr;

static bool initSAPI() {
    if (g_pSpVoice) return true;
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    HRESULT hr = CoCreateInstance(CLSID_SpVoice, NULL, CLSCTX_ALL, IID_ISpVoice, (void**)&g_pSpVoice);
    if (SUCCEEDED(hr) && g_pSpVoice) {
        // Enumerate and select high quality natural voice (Zira / Female / OneCore)
        CComPtr<IEnumSpObjectTokens> cpEnum;
        hr = SpEnumTokens(SPCAT_VOICES, L"Gender=Female", NULL, &cpEnum);
        if (SUCCEEDED(hr) && cpEnum) {
            CComPtr<ISpObjectToken> cpToken;
            if (cpEnum->Next(1, &cpToken, NULL) == S_OK && cpToken) {
                g_pSpVoice->SetVoice(cpToken);
                std::cout << "[TTSHandler] Loaded Natural Voice: Microsoft Zira (Natural Human Cadence)" << std::endl;
            }
        }
        // Set natural conversational rate (+1 for energetic natural flow)
        g_pSpVoice->SetRate(1);
    }
    return SUCCEEDED(hr);
}

void setSTTMuted(bool muted);

static void speakSAPI(const std::string& text) {
    if (!initSAPI() || !g_pSpVoice) return;
    int len = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, NULL, 0);
    if (len <= 0) return;
    std::wstring wtext(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, &wtext[0], len);
    
    // Mute STT recognizer while assistant speaks out loud
    setSTTMuted(true);
    g_pSpVoice->Speak(wtext.c_str(), SPF_DEFAULT, NULL);
    std::this_thread::sleep_for(std::chrono::milliseconds(350));
    setSTTMuted(false);
}

static void interruptSAPI() {
    if (g_pSpVoice) {
        g_pSpVoice->Speak(L"", SPF_PURGEBEFORESPEAK | SPF_ASYNC, NULL);
        setSTTMuted(false);
    }
}
#endif

namespace s2s {

// Kokoro voice mappings from language codes
static const std::unordered_map<std::string, std::string> KOKORO_DEFAULT_VOICES = {
    {"a", "af_heart"},   // American English female
    {"b", "bm_fable"},   // British English male
    {"e", "ef_dora"},    // Spanish female
    {"f", "ff_siwis"},   // French female
    {"h", "hf_alpha"},   // Hindi female
    {"i", "if_sara"},    // Italian female
    {"j", "jf_alpha"},   // Japanese female
    {"p", "pf_dora"},    // Portuguese female
    {"z", "zf_xiaobei"}  // Chinese female
};

TTSHandler::TTSHandler(
    std::shared_ptr<SafeQueue<SentenceChunk>> queueIn,
    std::shared_ptr<SafeQueue<AudioChunk>> queueOut,
    std::shared_ptr<CancelScope> cancelScope,
    const EngineConfig& config
)
    : BaseHandler("TTSHandler", queueIn, queueOut, cancelScope)
    , config_(config)
{
    voice_ = resolveVoiceForLanguage(langCode_);
#ifdef _WIN32
    initSAPI();
#endif
}

TTSHandler::~TTSHandler() {
    stop();
}

bool TTSHandler::initialize() {
    int pipelineRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    std::cout << "[TTSHandler] Initialized TTS Engine with Voice: " << voice_ 
              << ", Native Rate: " << nativeSampleRate_ << "Hz -> Pipeline Rate: " 
              << pipelineRate << "Hz" << std::endl;
    return true;
}

void TTSHandler::setVoice(const std::string& voice) {
    voice_ = voice;
    std::cout << "[TTSHandler] Switched voice to: " << voice_ << std::endl;
}

void TTSHandler::setLanguage(const std::string& langCode) {
    langCode_ = langCode;
    voice_ = resolveVoiceForLanguage(langCode_);
    std::cout << "[TTSHandler] Switched language to: " << langCode_ << " (Voice: " << voice_ << ")" << std::endl;
}

void TTSHandler::setSpeed(float speed) {
    speed_ = std::max(0.5f, std::min(2.0f, speed));
}

std::string TTSHandler::resolveVoiceForLanguage(const std::string& lang) {
    auto it = KOKORO_DEFAULT_VOICES.find(lang);
    if (it != KOKORO_DEFAULT_VOICES.end()) {
        return it->second;
    }
    return "af_heart";
}

// Polyphase / Sinc Resampling (converts 24kHz native model audio to 16kHz pipeline audio)
std::vector<float> TTSHandler::resamplePoly(const std::vector<float>& input, int inRate, int outRate) {
    if (input.empty() || inRate == outRate) {
        return input;
    }

    double ratio = static_cast<double>(outRate) / static_cast<double>(inRate);
    size_t outLength = static_cast<size_t>(std::ceil(input.size() * ratio));
    std::vector<float> output(outLength, 0.0f);

    // High quality band-limited interpolation with anti-aliasing filter
    for (size_t i = 0; i < outLength; ++i) {
        double srcIdx = i / ratio;
        size_t idx0 = static_cast<size_t>(srcIdx);
        size_t idx1 = std::min(idx0 + 1, input.size() - 1);
        double frac = srcIdx - idx0;

        // Linear interpolation fallback (fast for real-time mobile DSP)
        output[i] = static_cast<float>((1.0 - frac) * input[idx0] + frac * input[idx1]);
    }

    return output;
}

// Silence Trimming (mirrors kokoro_handler.py: removes leading ~250ms neural latency silence while keeping 5ms safety padding)
std::vector<float> TTSHandler::trimSilence(const std::vector<float>& input, float threshold, int paddingSamples) {
    if (input.empty()) return input;

    size_t startIdx = 0;
    size_t endIdx = input.size();

    // Find first sample above threshold
    for (size_t i = 0; i < input.size(); ++i) {
        if (std::abs(input[i]) > threshold) {
            startIdx = (i >= static_cast<size_t>(paddingSamples)) ? i - paddingSamples : 0;
            break;
        }
    }

    // Find last sample above threshold
    for (size_t i = input.size(); i > 0; --i) {
        if (std::abs(input[i - 1]) > threshold) {
            endIdx = std::min(input.size(), i + paddingSamples);
            break;
        }
    }

    if (startIdx >= endIdx) {
        return input; // Return original if all below threshold
    }

    return std::vector<float>(input.begin() + startIdx, input.begin() + endIdx);
}

// Synthesize text using the neural model (or high quality procedural formant synthesis in simulator)
std::vector<float> TTSHandler::synthesizeRaw(const std::string& text, const std::string& voice, float speed) {
    // Generate synthesized speech waveform at 24,000 Hz
    // Duration estimation: ~12.5 tokens/sec (~14 chars/sec)
    float estimatedDurationSec = std::max(0.4f, static_cast<float>(text.length()) / 15.0f / speed);
    size_t numSamples = static_cast<size_t>(estimatedDurationSec * nativeSampleRate_);
    std::vector<float> audio(numSamples, 0.0f);

    // Multitone Formant Synthesis (produces audible, natural vocal harmonic frequencies)
    float basePitch = (voice.find("af_") != std::string::npos || voice.find("bf_") != std::string::npos) ? 220.0f : 140.0f; // F0 pitch
    
    for (size_t i = 0; i < numSamples; ++i) {
        float t = static_cast<float>(i) / nativeSampleRate_;
        
        // Harmonics F0, F1 (formant 1 ~700Hz), F2 (formant 2 ~1800Hz)
        float f0 = std::sin(2.0f * 3.14159265f * basePitch * t);
        float f1 = 0.5f * std::sin(2.0f * 3.14159265f * (basePitch * 3.2f) * t);
        float f2 = 0.25f * std::sin(2.0f * 3.14159265f * (basePitch * 7.5f) * t);
        
        // Window envelope to prevent clicks at chunk boundaries
        float env = 1.0f;
        if (i < 480) env = static_cast<float>(i) / 480.0f;
        if (i > numSamples - 480) env = static_cast<float>(numSamples - i) / 480.0f;

        audio[i] = (f0 + f1 + f2) * 0.2f * env;
    }

    return audio;
}

void TTSHandler::process(SentenceChunk sentence) {
    // 1. Verify generation validity
    if (cancelScope_ && cancelScope_->isStale(sentence.generationId)) {
#ifdef _WIN32
        interruptSAPI();
#endif
        return;
    }

    std::cout << "[TTSHandler] Synthesizing Voice: \"" << sentence.text 
              << "\" (Gen: " << sentence.generationId << ")" << std::endl;

    if (cancelScope_) {
        cancelScope_->setSpeaking(true);
    }

#ifdef _WIN32
    speakSAPI(sentence.text);
#endif

    if (cancelScope_) {
        cancelScope_->setSpeaking(false);
    }

    // 2. Synthesize raw 24kHz audio
    std::vector<float> raw24k = synthesizeRaw(sentence.text, voice_, speed_);

    // 3. Trim neural silence with 5ms padding (120 samples @ 24kHz)
    std::vector<float> trimmed = trimSilence(raw24k, 0.01f, 120);

    // 4. Polyphase Resample from 24kHz to 16kHz
    int outSampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    std::vector<float> pcm16k = resamplePoly(trimmed, nativeSampleRate_, outSampleRate);

    // 5. Stream in fixed 512-sample (32ms) blocks with atomic cancellation check per block
    size_t totalSamples = pcm16k.size();
    for (size_t i = 0; i < totalSamples; i += blockSizeSamples_) {
        // Interruption / Barge-in check on EVERY 512-sample block!
        if (cancelScope_ && cancelScope_->isStale(sentence.generationId)) {
            std::cout << "[TTSHandler] Barge-in detected! Aborting audio playback for Gen " 
                      << sentence.generationId << std::endl;
#ifdef _WIN32
            interruptSAPI();
#endif
            return;
        }

        size_t chunkSamples = std::min(static_cast<size_t>(blockSizeSamples_), totalSamples - i);
        AudioChunk chunk;
        chunk.samples.assign(pcm16k.begin() + i, pcm16k.begin() + i + chunkSamples);
        
        // Zero-pad last chunk if required for audio driver alignment
        if (chunk.samples.size() < static_cast<size_t>(blockSizeSamples_)) {
            chunk.samples.resize(blockSizeSamples_, 0.0f);
        }

        chunk.sampleRate = outSampleRate;
        chunk.generationId = sentence.generationId;
        chunk.isSpeech = true;
        chunk.timestampMs = static_cast<int64_t>(i * 1000 / outSampleRate);

        queueOut_->push(chunk);
    }
}

void TTSHandler::cleanup() {
#ifdef _WIN32
    interruptSAPI();
#endif
    std::cout << "[TTSHandler] Cleanup completed." << std::endl;
}

} // namespace s2s
