#!/usr/bin/env bash
# Build mywhisper-core for Android and stage the .so files into jniLibs.
# Wired as the `buildRustCore` task (runs before every build).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
export PATH="$SDK/cmake/3.22.1/bin:$PATH"

cd "$ROOT/core"
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o "$ROOT/android/app/src/main/jniLibs" \
  build --release
