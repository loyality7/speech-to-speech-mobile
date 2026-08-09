cmake_minimum_required(VERSION 3.18)
project(s2s_mobile_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../core-engine/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../core-engine s2s_core_build)

add_library(s2s_jni SHARED
    src/main/cpp/jni_bridge.cpp
)

target_link_libraries(s2s_jni
    PRIVATE
        s2s_core
        log
        OpenSLES
)
