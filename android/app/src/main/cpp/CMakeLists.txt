# Offline speech-to-text for Codenvi: whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp) plus a tiny JNI bridge.
# whisper.cpp's source is downloaded at configure time (pinned to a release tag), so it is not copied into this repository.
cmake_minimum_required(VERSION 3.22.1)
project(codenvi_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always optimised: an unoptimised build of the model code is far too slow to use, even in a debug app.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)      # cross compiling: never tune for the build machine
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # threads are managed by ggml itself
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product and half-precision instructions exist on every phone since about 2018 and make the model several times faster.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(codenvi_whisper SHARED whisper_jni.cpp)
target_link_libraries(codenvi_whisper PRIVATE whisper log)
target_compile_options(codenvi_whisper PRIVATE -O3)
