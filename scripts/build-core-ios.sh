#!/usr/bin/env bash
# Build mywhisper-core for iOS and stage an .xcframework into ios/Libs.
# Mirrors scripts/build-core.sh (Android): same release profile, same
# plaintext-bundled rusqlite (see core/Cargo.toml IOS PORT note).
#
# Output:
#   ios/Libs/libmywhisper_core.xcframework  (device arm64 + sim slice)
# Input (committed): ios/Libs/mywhisper_core.h — the C header the Swift
# bridge (`ios/MyWhisper/CoreBridge.swift`) calls through. It is the
# header leg of the xcframework and doubles as the Xcode import.
#
# Host fallback: when the iOS targets (or xcodebuild) are absent —
# e.g. Linux CI/agents — runs `cargo test` so the C-ABI layer
# (`core/src/capple.rs`, compiled on all non-Android targets) is still
# verified. Xcode/XCTest runs on a Mac after this script succeeds.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ios/Libs"

mkdir -p "$OUT"

have_ios_targets() {
  rustup target list --installed 2>/dev/null | grep -q "aarch64-apple-ios" \
    && rustup target list --installed 2>/dev/null | grep -q "x86_64-apple-ios-sim"
}

if ! have_ios_targets || ! command -v xcodebuild >/dev/null 2>&1; then
  echo "build-core-ios: iOS toolchain absent (need aarch64-apple-ios + x86_64-apple-ios-sim targets and xcodebuild)."
  echo "build-core-ios: host fallback — running cargo test for the capple C-ABI layer."
  cd "$ROOT/core"
  cargo test --locked
  echo "build-core-ios: host fallback green. On a Mac, install targets:"
  echo "  rustup target add aarch64-apple-ios x86_64-apple-ios-sim"
  echo "  cargo install cargo-lipo"
  echo "then re-run $0."
  exit 0
fi

if ! command -v cargo-lipo >/dev/null 2>&1; then
  echo "build-core-ios: installing cargo-lipo..."
  cargo install --locked cargo-lipo
fi

cd "$ROOT/core"
cargo lipo --release

# cargo-lipo emits per-target staticlibs; the sim leg follows the host
# arch (arm64 on Apple Silicon, x86_64 on Intel Macs).
SIM_TRIPLE="aarch64-apple-ios-sim"
if [ "$(uname -m)" = "x86_64" ]; then
  SIM_TRIPLE="x86_64-apple-ios-sim"
fi

DEVICE_LIB="$ROOT/core/target/aarch64-apple-ios/release/libmywhisper_core.a"
SIM_LIB="$ROOT/core/target/$SIM_TRIPLE/release/libmywhisper_core.a"

xcodebuild -create-xcframework \
  -library "$DEVICE_LIB" -headers "$OUT" \
  -library "$SIM_LIB" -headers "$OUT" \
  -output "$OUT/libmywhisper_core.xcframework"

echo "build-core-ios: staged $OUT/libmywhisper_core.xcframework"
