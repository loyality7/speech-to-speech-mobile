#pragma once

#include "s2s/s2s_engine.h"
#include "s2s/api/realtime_events.h"
#include <string>
#include <functional>
#include <memory>

namespace s2s {
namespace api {

using EventSender = std::function<void(const std::string& jsonEvent)>;

/**
 * @brief OpenAI Realtime API Session Bridge for on-device S2S engine.
 * Receives JSON event strings (from WebSocket or React Native/Flutter bridge)
 * and dispatches typed responses.
 */
class RealtimeSession {
public:
    explicit RealtimeSession(std::shared_ptr<SpeechToSpeechEngine> engine, EventSender sender = nullptr)
        : engine_(std::move(engine))
        , sender_(std::move(sender))
    {
        setupCallbacks();
    }

    void setEventSender(EventSender sender) {
        sender_ = std::move(sender);
    }

    // Handles incoming JSON string message from client
    void handleIncomingEvent(const std::string& jsonMessage) {
        if (!engine_) return;

        // Parse event type (lightweight zero-dependency JSON tag search)
        if (jsonMessage.find(ClientEvents::RESPONSE_CANCEL) != std::string::npos) {
            engine_->interrupt();
        } else if (jsonMessage.find(ClientEvents::INPUT_AUDIO_BUFFER_CLEAR) != std::string::npos) {
            engine_->resetConversation();
        }
    }

    void sendSessionCreated() {
        if (sender_) {
            sender_("{\"type\":\"session.created\",\"session\":{\"id\":\"sess_mobile_001\",\"model\":\"minicpm-v4.6\"}}");
        }
    }

private:
    std::shared_ptr<SpeechToSpeechEngine> engine_;
    EventSender sender_;

    void setupCallbacks() {
        if (!engine_) return;

        engine_->setStateCallback([this](EngineState state) {
            if (!sender_) return;
            if (state == EngineState::LISTENING) {
                sender_("{\"type\":\"input_audio_buffer.speech_started\"}");
            } else if (state == EngineState::PROCESSING_SPEECH) {
                sender_("{\"type\":\"input_audio_buffer.speech_stopped\"}");
            }
        });

        engine_->setTranscriptCallback([this](const std::string& text, bool isUser, bool isFinal) {
            if (!sender_) return;
            if (isUser && isFinal) {
                std::string msg = "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"" + text + "\"}";
                sender_(msg);
            }
        });
    }
};

} // namespace api
} // namespace s2s
