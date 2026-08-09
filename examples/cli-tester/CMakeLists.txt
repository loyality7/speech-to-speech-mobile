cmake_minimum_required(VERSION 3.18)

add_executable(s2s_cli_tester
    src/main.cpp
)

target_link_libraries(s2s_cli_tester PRIVATE s2s_core)

target_include_directories(s2s_cli_tester PRIVATE
    ${CMAKE_SOURCE_DIR}/core-engine/include
    ${CMAKE_SOURCE_DIR}/third_party/miniaudio
)
