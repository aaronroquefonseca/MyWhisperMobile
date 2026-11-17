#!/usr/bin/env bash
# Build the on-device AI runtime pack (llama.cpp + whisper.cpp, arm64-v8a)
# from pinned upstream sources. Output: $OUT/lib/*.so (+ bench tools when
# BENCH=1). The pack is downloaded by the app on demand; it never ships in
# the APK.
#
# Env: WORK (source/build cache, default ~/.cache/mw-localai),
#      OUT (default $ROOT/build/localai), BENCH=1 to also build CLI tools.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
WORK="${WORK:-$HOME/.cache/mw-localai}"
OUT="${OUT:-$ROOT/build/localai}"
TC="$NDK/build/cmake/android.toolchain.cmake"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
SYSROOT_LIB="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
API=28

LLAMA_TAG=v1.0.0
# whisper.cpp master: first revision with Parakeet + a ggml compatible with llama.cpp v1.0.0.
WHISPER_REV=aba02defd6bb6ff5de63721c21e3456645c153e2
OPENCL_HEADERS_TAG=v2025.07.22
OPENCL_LOADER_TAG=v2025.07.22
VULKAN_HEADERS_TAG=v1.4.309
SPIRV_HEADERS_TAG=vulkan-sdk-1.4.309.0

mkdir -p "$WORK" "$OUT/lib"
cd "$WORK"

fetch() { # repo tag dir
  if [[ ! -d "$3/.git" ]]; then
    git -c advice.detachedHead=false clone -q --depth 1 -b "$2" "https://github.com/$1" "$3"
  fi
}
fetch ggml-org/llama.cpp "$LLAMA_TAG" llama.cpp
if [[ "$(git -C whisper.cpp rev-parse HEAD 2>/dev/null)" != "$WHISPER_REV" ]]; then
  rm -rf whisper.cpp && git init -q whisper.cpp
  git -C whisper.cpp fetch -q --depth 1 https://github.com/ggml-org/whisper.cpp "$WHISPER_REV"
  git -C whisper.cpp -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
fetch KhronosGroup/OpenCL-Headers "$OPENCL_HEADERS_TAG" OpenCL-Headers
fetch KhronosGroup/OpenCL-ICD-Loader "$OPENCL_LOADER_TAG" OpenCL-ICD-Loader
fetch KhronosGroup/Vulkan-Headers "$VULKAN_HEADERS_TAG" Vulkan-Headers
fetch KhronosGroup/SPIRV-Headers "$SPIRV_HEADERS_TAG" SPIRV-Headers

# Local fixes on top of the pinned sources (android/localai/patches/<repo>/).
for repo in llama.cpp whisper.cpp; do
  for p in "$ROOT/android/localai/patches/$repo"/*.patch; do
    [[ -f "$p" ]] || continue
    git -C "$repo" apply --reverse --check "$p" 2>/dev/null || git -C "$repo" apply "$p"
  done
done

# OpenCL: link-time stub only. At runtime the vendor libOpenCL.so is
# resolved (manifest <uses-native-library>); the stub is never packed.
if [[ ! -f OpenCL-ICD-Loader/build/libOpenCL.so ]]; then
  cmake -S OpenCL-ICD-Loader -B OpenCL-ICD-Loader/build -G Ninja -Wno-dev \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$TC" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API -DANDROID_STL=c++_shared \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="$WORK/OpenCL-Headers" -DBUILD_TESTING=OFF >/dev/null
  ninja -C OpenCL-ICD-Loader/build >/dev/null
fi
if [[ ! -d spirv-install ]]; then
  cmake -S SPIRV-Headers -B SPIRV-Headers/build -DCMAKE_INSTALL_PREFIX="$WORK/spirv-install" \
    -DSPIRV_HEADERS_ENABLE_TESTS=OFF >/dev/null
  cmake --install SPIRV-Headers/build >/dev/null
fi

COMMON=(
  -G Ninja -Wno-dev -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$TC"
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_shared
  -DCMAKE_FIND_ROOT_PATH="$WORK/spirv-install"
  -DSPIRV-Headers_DIR="$WORK/spirv-install/share/cmake/SPIRV-Headers"
  # ggml-vulkan includes spirv.hpp but only finds it via a Vulkan SDK path.
  -DCMAKE_CXX_FLAGS="-isystem $WORK/spirv-install/include"
  -DBUILD_SHARED_LIBS=ON -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=ON
  -DGGML_CPU_ALL_VARIANTS=ON -DGGML_CPU_KLEIDIAI=ON -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF
  -DGGML_VULKAN=ON -DVulkan_INCLUDE_DIR="$WORK/Vulkan-Headers/include"
  -DVulkan_LIBRARY="$SYSROOT_LIB/$API/libvulkan.so" -DVulkan_GLSLC_EXECUTABLE="$(command -v glslc)"
  -DGGML_OPENCL=ON -DOpenCL_INCLUDE_DIR="$WORK/OpenCL-Headers"
  -DOpenCL_LIBRARY="$WORK/OpenCL-ICD-Loader/build/libOpenCL.so"
  -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON
)

BUILD="$WORK/build-android"
cmake -S "$ROOT/android/localai" -B "$BUILD" "${COMMON[@]}" \
  -DLLAMA_DIR="$WORK/llama.cpp" -DWHISPER_DIR="$WORK/whisper.cpp" -DWHISPER_VERSION="${WHISPER_REV:0:12}" \
  -DMW_BUILD_BENCH="$([[ "${BENCH:-0}" == 1 ]] && echo ON || echo OFF)" \
  -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_COMMON=OFF >/dev/null
targets=(mwlocalai ggml-vulkan ggml-opencl)
[[ "${BENCH:-0}" == 1 ]] && targets+=(mwbench)
cpu_variants=$(ninja -C "$BUILD" -t targets all | sed -n 's/^\(ggml-cpu-android[^:]*\):.*/\1/p' | sort -u)
ninja -C "$BUILD" "${targets[@]}" $cpu_variants

rm -rf "$OUT" && mkdir -p "$OUT/lib"
find "$BUILD" -name '*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$OUT/lib/" \;
[[ "${BENCH:-0}" == 1 ]] && cp "$(find "$BUILD" -name mwbench -type f | head -1)" "$OUT/lib/"
cp "$SYSROOT_LIB/libc++_shared.so" "$OUT/lib/"
"$STRIP" --strip-unneeded "$OUT"/lib/*
ls -la "$OUT/lib"
