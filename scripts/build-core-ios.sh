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
# Requires a Mac with the iOS targets installed:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim   (Apple Silicon)
#   rustup target add aarch64-apple-ios x86_64-apple-ios-sim    (Intel)
#
# Host fallback: when the iOS targets (or xcodebuild) are absent —
# e.g. Linux CI/agents — runs `cargo test` so the C-ABI layer
# (`core/src/capple.rs`, compiled on all non-Android targets) is still
# verified, then exits non-zero: the xcframework was requested but not
# built. Xcode/XCTest runs on a Mac after this script exits 0.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/ios/Libs"

# The sim leg follows the host arch (arm64 on Apple Silicon,
# x86_64 on Intel Macs).
SIM_TRIPLE="aarch64-apple-ios-sim"
if [ "$(uname -m)" = "x86_64" ]; then
  SIM_TRIPLE="x86_64-apple-ios-sim"
fi
DEVICE_TRIPLE="aarch64-apple-ios"

have_ios_toolchain() {
  rustup target list --installed 2>/dev/null | grep -q "$DEVICE_TRIPLE" \
    && rustup target list --installed 2>/dev/null | grep -q "$SIM_TRIPLE" \
    && command -v xcodebuild >/dev/null 2>&1
}

if ! have_ios_toolchain; then
  echo "build-core-ios: iOS toolchain absent (need targets $DEVICE_TRIPLE + $SIM_TRIPLE and xcodebuild)."
  echo "build-core-ios: host fallback — running cargo test for the capple C-ABI layer."
  cd "$ROOT/core"
  cargo test --locked
  echo "build-core-ios: ERROR: cargo test passed but no xcframework was produced (iOS toolchain absent)." >&2
  echo "build-core-ios: on a Mac, run: rustup target add $DEVICE_TRIPLE $SIM_TRIPLE; then re-run $0." >&2
  exit 1
fi

cd "$ROOT/core"
cargo build --locked --release --target "$DEVICE_TRIPLE"
cargo build --locked --release --target "$SIM_TRIPLE"

DEVICE_LIB="$ROOT/core/target/$DEVICE_TRIPLE/release/libmywhisper_core.a"
SIM_LIB="$ROOT/core/target/$SIM_TRIPLE/release/libmywhisper_core.a"

mkdir -p "$OUT"
# -create-xcframework refuses when the output already exists.
rm -rf "$OUT/libmywhisper_core.xcframework"
xcodebuild -create-xcframework \
  -library "$DEVICE_LIB" -headers "$OUT" \
  -library "$SIM_LIB" -headers "$OUT" \
  -output "$OUT/libmywhisper_core.xcframework"

echo "build-core-ios: staged $OUT/libmywhisper_core.xcframework"
