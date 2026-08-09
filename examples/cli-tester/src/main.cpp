#ifndef NOMINMAX
#define NOMINMAX
#endif
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"
#include "s2s/s2s_engine.h"
#include <iostream>
#include <thread>
#include <chrono>
#include <vector>
#include <cmath>
#include <string>
#include <atomic>

static s2s::SpeechToSpeechEngine* g_pEngine = nullptr;
static std::atomic<bool> g_isSpeaking{false};

// Miniaudio Microphone Capture Callback
void audioCaptureCallback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount) {
    (void)pOutput;
    (void)pDevice;
    if (pInput && g_pEngine) {
        // Echo suppression: do not feed mic if assistant is currently speaking through laptop speakers
        if (g_isSpeaking.load()) {
            return;
        }
        const float* pcm = static_cast<const float*>(pInput);
        g_pEngine->feedAudioInput(pcm, frameCount);
    }
}

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;

    std::cout << "===============================================================\n";
    std::cout << "      🎙️  SPEECH-TO-SPEECH REAL-TIME CONVERSATION ENGINE  🎙️     \n";
    std::cout << "          Local Model: Ollama minicpm-v4.6:latest             \n";
    std::cout << "===============================================================\n";

    s2s::EngineConfig config;
    config.llm.modelName = "minicpm-v4.6:latest";
    config.llm.systemPrompt = "You are a direct, friendly AI voice assistant. Give spoken replies directly with no preamble and no thinking tags. Keep answers short and conversational (1 to 2 spoken sentences).";
    config.vad.threshold = 0.45f;
    config.vad.minSilenceMs = 400;

    s2s::SpeechToSpeechEngine engine(config);
    g_pEngine = &engine;

    // Register State Change Callback
    engine.setStateCallback([](s2s::EngineState state) {
        switch (state) {
            case s2s::EngineState::IDLE: 
                g_isSpeaking.store(false);
                std::cout << "\n[🎙️ Ready / Listening - Speak into your mic anytime...]\n" << std::endl;
                break;
            case s2s::EngineState::LISTENING: 
                g_isSpeaking.store(false);
                break;
            case s2s::EngineState::PROCESSING_SPEECH: 
                g_isSpeaking.store(false);
                std::cout << "\n[⏳ Processing voice transcription...]" << std::endl;
                break;
            case s2s::EngineState::GENERATING_RESPONSE: 
                g_isSpeaking.store(false);
                std::cout << "\n[🤖 Assistant Thinking & Streaming Response...]" << std::endl;
                break;
            case s2s::EngineState::SPEAKING: 
                g_isSpeaking.store(true);
                break;
            case s2s::EngineState::INTERRUPTED: 
                g_isSpeaking.store(false);
                std::cout << "\n[Status: 🚨 Interrupted / Barge-in!]" << std::endl; 
                break;
        }
    });

    if (!engine.initialize()) {
        std::cerr << "Failed to initialize speech engine!" << std::endl;
        return 1;
    }

    if (!engine.start()) {
        std::cerr << "Failed to start speech pipeline!" << std::endl;
        return 1;
    }

    // Initialize Miniaudio Live Microphone Capture
    ma_device_config deviceConfig = ma_device_config_init(ma_device_type_capture);
    deviceConfig.capture.format = ma_format_f32;
    deviceConfig.capture.channels = 1;
    deviceConfig.sampleRate = 16000;
    deviceConfig.dataCallback = audioCaptureCallback;
    deviceConfig.pUserData = NULL;

    ma_device audioDevice;
    bool micStarted = false;
    if (ma_device_init(NULL, &deviceConfig, &audioDevice) == MA_SUCCESS) {
        if (ma_device_start(&audioDevice) == MA_SUCCESS) {
            micStarted = true;
            std::cout << ">>> [Microphone] Live 16kHz audio capture active! <<<\n";
        }
    }

    if (!micStarted) {
        std::cout << ">>> [Note] Microphone device not detected or busy. You can type prompts below! <<<\n";
    }

#ifdef _WIN32
void setSTTMuted(bool muted);
#endif

    std::cout << "\n---------------------------------------------------------------------" << std::endl;
    std::cout << " 🎙️ SPEECH-TO-SPEECH INTERACTION READY (Ollama minicpm-v4.6)" << std::endl;
    std::cout << "  👉 Option 1: Press ENTER (or type 'talk') and speak into your mic!" << std::endl;
    std::cout << "  👉 Option 2: Type any question directly and press ENTER" << std::endl;
    std::cout << "  👉 Commands: '/bargein' (stop speech), '/reset' (clear memory), '/quit'" << std::endl;
    std::cout << "---------------------------------------------------------------------\n" << std::endl;

    // Interactive CLI loop
    std::string userInput;
    while (true) {
        std::cout << "\n[You (Type or press ENTER to talk)] > " << std::flush;
        if (!std::getline(std::cin, userInput)) {
            break;
        }

        if (userInput == "/quit" || userInput == "exit" || userInput == "q") {
            break;
        } else if (userInput == "/bargein" || userInput == "/stop") {
            std::cout << ">>> Triggering Barge-In Interruption! <<<" << std::endl;
            engine.interrupt();
        } else if (userInput == "/reset") {
            std::cout << ">>> Resetting Conversation History! <<<" << std::endl;
            engine.resetConversation();
        } else if (userInput.empty() || userInput == "talk" || userInput == "t" || userInput == "mic") {
            std::cout << "\n[🎙️ MICROPHONE ACTIVATED: Speak your question now into your mic...] " << std::flush;
#ifdef _WIN32
            setSTTMuted(false);
#endif
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        } else {
            std::cout << "\n[Assistant] > " << std::flush;
            engine.feedTextPrompt(userInput);
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }

    std::cout << "\nStopping engine..." << std::endl;
    if (micStarted) {
        ma_device_stop(&audioDevice);
        ma_device_uninit(&audioDevice);
    }
    engine.stop();
    g_pEngine = nullptr;

    std::cout << "Goodbye!" << std::endl;
    return 0;
}
