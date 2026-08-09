cmake_minimum_required(VERSION 3.18)
project(SpeechToSpeechMobile VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Optimization flags
if(MSVC)
    add_compile_options(/W4 /O2)
else()
    add_compile_options(-Wall -Wextra -O3)
endif()

# Core library
add_subdirectory(core-engine)

# Example CLI Tester
option(BUILD_EXAMPLES "Build example test applications" ON)
if(BUILD_EXAMPLES)
    add_subdirectory(examples/cli-tester)
endif()

# Unit Tests
enable_testing()
add_subdirectory(tests)

