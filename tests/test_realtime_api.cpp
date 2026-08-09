#include "s2s/api/realtime_events.h"
#include "s2s/api/realtime_session.h"
#include <iostream>
#include <cassert>

namespace s2s {
namespace test {

bool testRealtimeApiEvents() {
    std::cout << "[TEST] Running testRealtimeApiEvents..." << std::endl;

    assert(std::string(api::ClientEvents::SESSION_UPDATE) == "session.update");
    assert(std::string(api::ClientEvents::INPUT_AUDIO_BUFFER_APPEND) == "input_audio_buffer.append");
    assert(std::string(api::ServerEvents::SESSION_CREATED) == "session.created");
    assert(std::string(api::ServerEvents::RESPONSE_TEXT_DELTA) == "response.text.delta");
    assert(std::string(api::ServerEvents::RESPONSE_AUDIO_DELTA) == "response.audio.delta");

    std::cout << "  -> testRealtimeApiEvents PASSED!" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
