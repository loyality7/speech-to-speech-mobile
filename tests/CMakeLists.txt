cmake_minimum_required(VERSION 3.18)

add_executable(s2s_tests
    main_test_runner.cpp
    test_chat.cpp
    test_sentence_chunker.cpp
    test_vad.cpp
    test_silero_and_smart_turn.cpp
    test_whisper.cpp
    test_llama_engine.cpp
    test_tool_registry.cpp
    test_cancel_scope.cpp
    test_safe_queue.cpp
    test_realtime_api.cpp
)

target_link_libraries(s2s_tests
    PRIVATE
        s2s_core
)

target_include_directories(s2s_tests
    PRIVATE
        ${CMAKE_SOURCE_DIR}/core-engine/include
)

add_test(NAME S2SEngineUnitTests COMMAND s2s_tests)
