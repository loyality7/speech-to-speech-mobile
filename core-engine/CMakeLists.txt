cmake_minimum_required(VERSION 3.18)

add_library(s2s_core STATIC
    src/cancel_scope.cpp
    src/vad_handler.cpp
    src/stt_handler.cpp
    src/chat_history.cpp
    src/llm_handler.cpp
    src/sentence_chunker.cpp
    src/tts_handler.cpp
    src/tool_registry.cpp
    src/s2s_engine.cpp
)

target_include_directories(s2s_core PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
)

# Threading & Socket networking support
find_package(Threads REQUIRED)
target_link_libraries(s2s_core PUBLIC Threads::Threads)

if(WIN32)
    target_link_libraries(s2s_core PUBLIC ws2_32)
endif()
