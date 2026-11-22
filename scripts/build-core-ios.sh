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

# Match the Xcode target (IPHONEOS_DEPLOYMENT_TARGET = 17.0). Rust's
# default Apple deployment target is ancient; modern Xcode toolchains
# (16+, and Xcode 26 on CI) cannot link the cdylib against it
# (missing compiler-rt symbols like ___chkstk_darwin). Pinning the same
# floor the app targets keeps device + sim links working.
export IPHONEOS_DEPLOYMENT_TARGET=17.0

# The sim leg follows the host arch (arm64 on Apple Silicon).
# NOTE: for x86_64 the simulator triple is x86_64-apple-ios itself —
# there is no -sim suffix (it never shipped as a separate target).
SIM_TRIPLE="aarch64-apple-ios-sim"
if [ "$(uname -m)" = "x86_64" ]; then
  SIM_TRIPLE="x86_64-apple-ios"
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
# -create-xcframework refuses when the output already exists, and refuses
# a single headers dir shared by both slices ("item couldn't be saved").
# Stage the same header per slice in temp dirs instead.
DEVICE_HEADERS="$(mktemp -d)"
SIM_HEADERS="$(mktemp -d)"
trap 'rm -rf "$DEVICE_HEADERS" "$SIM_HEADERS"' EXIT
cp "$OUT/mywhisper_core.h" "$DEVICE_HEADERS/"
cp "$OUT/mywhisper_core.h" "$SIM_HEADERS/"
rm -rf "$OUT/libmywhisper_core.xcframework"
xcodebuild -create-xcframework \
  -library "$DEVICE_LIB" -headers "$DEVICE_HEADERS" \
  -library "$SIM_LIB" -headers "$SIM_HEADERS" \
  -output "$OUT/libmywhisper_core.xcframework"

echo "build-core-ios: staged $OUT/libmywhisper_core.xcframework"
