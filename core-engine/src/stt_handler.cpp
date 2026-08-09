#include "s2s/stt_handler.h"
#include <iostream>
#include <sstream>
#include <cmath>
#include <vector>
#include <thread>
#include <atomic>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <sapi.h>
#include <sphelper.h>
#pragma comment(lib, "sapi.lib")
#pragma comment(lib, "ole32.lib")

class WinSpeechRecognizer {
public:
    WinSpeechRecognizer() = default;
    ~WinSpeechRecognizer() { stop(); }

    bool start(std::function<void(const std::string&)> onRecognized) {
        onRecognized_ = onRecognized;
        running_ = true;
        worker_ = std::thread(&WinSpeechRecognizer::runLoop, this);
        return true;
    }

    void stop() {
        running_ = false;
        if (worker_.joinable()) {
            worker_.join();
        }
    }

    void setMuted(bool muted) {
        muted_.store(muted);
    }

    bool isMuted() const {
        return muted_.load();
    }

private:
    void runLoop() {
        CoInitializeEx(NULL, COINIT_MULTITHREADED);
        CComPtr<ISpRecognizer> cpRecognizer;
        HRESULT hr = cpRecognizer.CoCreateInstance(CLSID_SpInprocRecognizer);
        if (FAILED(hr)) {
            hr = cpRecognizer.CoCreateInstance(CLSID_SpSharedRecognizer);
        }

        if (FAILED(hr)) {
            std::cerr << "[STTHandler] Failed to create Windows Speech Recognizer (hr=0x" 
                      << std::hex << hr << ")." << std::endl;
            CoUninitialize();
            return;
        }

        // Set default microphone input for inproc recognizer
        CComPtr<ISpObjectToken> cpAudioInToken;
        if (SUCCEEDED(SpGetDefaultTokenFromCategoryId(SPCAT_AUDIOIN, &cpAudioInToken)) && cpAudioInToken) {
            cpRecognizer->SetInput(cpAudioInToken, TRUE);
        }

        CComPtr<ISpRecoContext> cpRecoContext;
        hr = cpRecognizer->CreateRecoContext(&cpRecoContext);
        if (FAILED(hr)) {
            std::cerr << "[STTHandler] Failed to create RecoContext." << std::endl;
            CoUninitialize();
            return;
        }

        CComPtr<ISpRecoGrammar> cpGrammar;
        hr = cpRecoContext->CreateGrammar(1, &cpGrammar);
        if (SUCCEEDED(hr)) {
            // 1. Add high-confidence conversational phrases
            SPSTATEHANDLE hInitialState;
            if (SUCCEEDED(cpGrammar->GetRule(L"ConversationalPhrases", 1, SPRAF_TopLevel | SPRAF_Active, TRUE, &hInitialState))) {
                const wchar_t* commonPhrases[] = {
                    L"Hello", L"Hi", L"Hey", L"Hello assistant", L"Hey assistant",
                    L"What is", L"What are", L"Who is", L"Where is", L"How are you",
                    L"Can you help me", L"Tell me a joke", L"Tell me a story",
                    L"What can you do", L"Good morning", L"Good evening", L"Thank you",
                    L"What is the capital of France", L"Tell me about yourself"
                };
                for (const auto* phrase : commonPhrases) {
                    cpGrammar->AddWordTransition(hInitialState, NULL, phrase, L" ", SPWT_LEXICAL, 1.0f, NULL);
                }
                cpGrammar->Commit(0);
                cpGrammar->SetRuleState(L"ConversationalPhrases", NULL, SPRS_ACTIVE);
            }

            // 2. Enable general dictation for open-ended natural conversation
            hr = cpGrammar->LoadDictation(NULL, SPLO_STATIC);
            if (SUCCEEDED(hr)) {
                cpGrammar->SetDictationState(SPRS_ACTIVE);
            }
        }

        cpRecoContext->SetInterest(SPFEI(SPEI_RECOGNITION) | SPFEI(SPEI_HYPOTHESIS), 
                                   SPFEI(SPEI_RECOGNITION) | SPFEI(SPEI_HYPOTHESIS));
        cpRecoContext->SetNotifyWin32Event();

        std::cout << "\n>>> [Microphone Status: 🟢 LISTENING - Speak into your mic now!] <<<\n" << std::endl;

        while (running_) {
            HRESULT hrWait = cpRecoContext->WaitForNotifyEvent(200);
            if (hrWait == S_OK) {
                CSpEvent event;
                while (event.GetFrom(cpRecoContext) == S_OK) {
                    if (event.eEventId == SPEI_HYPOTHESIS && !muted_.load()) {
                        ISpRecoResult* pResult = event.RecoResult();
                        if (pResult) {
                            WCHAR* pText = nullptr;
                            if (SUCCEEDED(pResult->GetText(SP_GETWHOLEPHRASE, SP_GETWHOLEPHRASE, TRUE, &pText, nullptr)) && pText) {
                                int len = WideCharToMultiByte(CP_UTF8, 0, pText, -1, NULL, 0, NULL, NULL);
                                if (len > 0) {
                                    std::string partial(len, '\0');
                                    WideCharToMultiByte(CP_UTF8, 0, pText, -1, &partial[0], len, NULL, NULL);
                                    if (!partial.empty() && partial.back() == '\0') partial.pop_back();
                                    std::cout << "\r[🎙️ Listening...] " << partial << "      " << std::flush;
                                }
                                CoTaskMemFree(pText);
                            }
                        }
                    } else if (event.eEventId == SPEI_RECOGNITION) {
                        ISpRecoResult* pResult = event.RecoResult();
                        if (pResult) {
                            WCHAR* pText = nullptr;
                            if (SUCCEEDED(pResult->GetText(SP_GETWHOLEPHRASE, SP_GETWHOLEPHRASE, TRUE, &pText, nullptr)) && pText) {
                                if (!muted_.load()) {
                                    int len = WideCharToMultiByte(CP_UTF8, 0, pText, -1, NULL, 0, NULL, NULL);
                                    if (len > 0) {
                                        std::string recognized(len, '\0');
                                        WideCharToMultiByte(CP_UTF8, 0, pText, -1, &recognized[0], len, NULL, NULL);
                                        if (!recognized.empty() && recognized.back() == '\0') recognized.pop_back();
                                        
                                        if (onRecognized_ && !recognized.empty()) {
                                            std::cout << "\r\n[👤 You Said]: \"" << recognized << "\"" << std::endl;
                                            onRecognized_(recognized);
                                        }
                                    }
                                }
                                CoTaskMemFree(pText);
                            }
                        }
                    }
                }
            }
        }

        if (cpGrammar) {
            cpGrammar->SetDictationState(SPRS_INACTIVE);
        }
        CoUninitialize();
    }

    std::atomic<bool> running_{false};
    std::atomic<bool> muted_{false};
    std::thread worker_;
    std::function<void(const std::string&)> onRecognized_;
};

static std::unique_ptr<WinSpeechRecognizer> g_pWinRecognizer;

void setSTTMuted(bool muted) {
    if (g_pWinRecognizer) {
        g_pWinRecognizer->setMuted(muted);
    }
}
#endif

namespace s2s {

STTHandler::STTHandler(
    std::shared_ptr<SafeQueue<SpeechSegment>> queueIn,
    std::shared_ptr<SafeQueue<STTTranscript>> queueOut,
    std::shared_ptr<CancelScope> cancelScope,
    const EngineConfig& config
)
    : BaseHandler("STTHandler", queueIn, queueOut, cancelScope)
    , config_(config)
{
#ifdef _WIN32
    g_pWinRecognizer = std::make_unique<WinSpeechRecognizer>();
    g_pWinRecognizer->start([this](const std::string& text) {
        if (text.empty()) return;
        
        // Discard recognized text if engine is currently speaking through speakers
        if (cancelScope_ && cancelScope_->isSpeaking()) {
            return;
        }

        // Noise click filter: ignore spurious sub-word clicks (e.g. "The", "In", "A", "Uh", "Um", "But in")
        std::string lowerText = text;
        for (char& c : lowerText) c = static_cast<char>(std::tolower(c));
        
        if (lowerText.length() < 4 || 
            lowerText == "the" || lowerText == "in" || lowerText == "a" || 
            lowerText == "uh" || lowerText == "um" || lowerText == "but in" ||
            lowerText == "and" || lowerText == "of") {
            // Discard noise click
            return;
        }

        // Immediately mute STT while LLM generates and speaks response
        if (g_pWinRecognizer) {
            g_pWinRecognizer->setMuted(true);
        }

        GenerationId gen = cancelScope_ ? cancelScope_->getGeneration() : 0;
        
        STTTranscript transcript;
        transcript.text = text;
        transcript.isFinal = true;
        transcript.generationId = gen;

        std::cout << "\n[STT Voice Recognized] >>> \"" << text << "\" (Gen: " << gen << ") <<<" << std::endl;
        queueOut_->push(transcript);
    });
#endif
}

STTHandler::~STTHandler() {
#ifdef _WIN32
    if (g_pWinRecognizer) {
        g_pWinRecognizer->stop();
        g_pWinRecognizer.reset();
    }
#endif
    stop();
}

bool STTHandler::initialize() {
    std::cout << "[STTHandler] Initialized Real-Time Speech-to-Text Engine" << std::endl;
    return true;
}

std::string STTHandler::transcribeSegment(const std::vector<float>& samples) {
    if (samples.empty()) return "";
    return "";
}

void STTHandler::process(SpeechSegment segment) {
    if (cancelScope_ && cancelScope_->isStale(segment.generationId)) {
        return;
    }

    int sampleRate = config_.audio.sampleRate > 0 ? config_.audio.sampleRate : 16000;
    std::cout << "[STTHandler] Transcribing speech segment (" 
              << segment.samples.size() << " samples, "
              << (segment.samples.size() * 1000 / sampleRate) << "ms)..." << std::endl;

    std::string text = transcribeSegment(segment.samples);

    if (cancelScope_ && cancelScope_->isStale(segment.generationId)) {
        return;
    }

    if (!text.empty()) {
        STTTranscript transcript;
        transcript.text = text;
        transcript.isFinal = segment.isFinal;
        transcript.generationId = segment.generationId;

        std::cout << "[STTHandler] >>> Transcript: \"" << text << "\" (Gen: " << segment.generationId << ") <<<" << std::endl;
        queueOut_->push(transcript);
    }
}

void STTHandler::cleanup() {
    std::cout << "[STTHandler] Cleanup completed." << std::endl;
}

} // namespace s2s
