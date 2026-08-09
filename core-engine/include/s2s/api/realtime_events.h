#pragma once

#include <string>
#include <vector>
#include <cstdint>

namespace s2s {
namespace api {

/**
 * @brief Standard OpenAI Realtime API Client-to-Server Event Types.
 * Synchronized with speech_to_speech/api/openai_realtime/.
 */
namespace ClientEvents {
    inline const char* SESSION_UPDATE = "session.update";
    inline const char* INPUT_AUDIO_BUFFER_APPEND = "input_audio_buffer.append";
    inline const char* INPUT_AUDIO_BUFFER_COMMIT = "input_audio_buffer.commit";
    inline const char* INPUT_AUDIO_BUFFER_CLEAR = "input_audio_buffer.clear";
    inline const char* CONVERSATION_ITEM_CREATE = "conversation.item.create";
    inline const char* CONVERSATION_ITEM_TRUNCATE = "conversation.item.truncate";
    inline const char* CONVERSATION_ITEM_DELETE = "conversation.item.delete";
    inline const char* RESPONSE_CREATE = "response.create";
    inline const char* RESPONSE_CANCEL = "response.cancel";
}

/**
 * @brief Standard OpenAI Realtime API Server-to-Client Event Types.
 */
namespace ServerEvents {
    inline const char* ERROR = "error";
    inline const char* SESSION_CREATED = "session.created";
    inline const char* SESSION_UPDATED = "session.updated";
    inline const char* INPUT_AUDIO_BUFFER_COMMITTED = "input_audio_buffer.committed";
    inline const char* INPUT_AUDIO_BUFFER_CLEARED = "input_audio_buffer.cleared";
    inline const char* INPUT_AUDIO_BUFFER_SPEECH_STARTED = "input_audio_buffer.speech_started";
    inline const char* INPUT_AUDIO_BUFFER_SPEECH_STOPPED = "input_audio_buffer.speech_stopped";
    inline const char* CONVERSATION_ITEM_CREATED = "conversation.item.created";
    inline const char* CONVERSATION_ITEM_INPUT_AUDIO_TRANSCRIPTION_COMPLETED = "conversation.item.input_audio_transcription.completed";
    inline const char* RESPONSE_CREATED = "response.created";
    inline const char* RESPONSE_TEXT_DELTA = "response.text.delta";
    inline const char* RESPONSE_AUDIO_DELTA = "response.audio.delta";
    inline const char* RESPONSE_AUDIO_TRANSCRIPT_DELTA = "response.audio_transcript.delta";
    inline const char* RESPONSE_DONE = "response.done";
}

} // namespace api
} // namespace s2s
