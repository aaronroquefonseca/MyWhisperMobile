# On-device AI runtime pack: llama.cpp + whisper.cpp (Whisper, Parakeet)
# on ONE shared ggml, with CPU/Vulkan/OpenCL as dlopen'd backends.
# Configured by scripts/build-localai.sh (pinned sources, NDK toolchain).
cmake_minimum_required(VERSION 3.22)
project(mwlocalai C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "" CACHE PATH "llama.cpp source dir")
set(WHISPER_DIR "" CACHE PATH "whisper.cpp source dir")
option(MW_BUILD_BENCH "build the mwbench CLI" OFF)

add_subdirectory(${LLAMA_DIR} llama)

# whisper.cpp sources compiled against llama.cpp's ggml (single copy in-process).
add_library(mwwhisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    ${WHISPER_DIR}/src/parakeet.cpp)
target_include_directories(mwwhisper PUBLIC ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
target_compile_definitions(mwwhisper PRIVATE
    WHISPER_VERSION="${WHISPER_VERSION}" PARAKEET_VERSION="${WHISPER_VERSION}")
set_target_properties(mwwhisper PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(mwwhisper PUBLIC ggml)

add_library(mwlocalai SHARED mwlocalai.cpp jni.cpp)
target_link_libraries(mwlocalai PRIVATE llama mwwhisper ggml log)
# Only the C API + JNI entry points are exported.
target_link_options(mwlocalai PRIVATE -Wl,--exclude-libs,ALL)

if (MW_BUILD_BENCH)
    add_executable(mwbench mwbench.cpp)
    target_link_libraries(mwbench PRIVATE mwlocalai)
endif()
