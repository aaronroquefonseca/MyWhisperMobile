# MyWhisperMobile

MyWhisper for Android. Same engine ideas as desktop MyWhisper, same
settings schema — different shell: no pill, the app is an **input
method** with a mic button.

- **Config app**: status/IME setup, Speech to Text, Text Cleanup,
  Dictionary, History, Preferences. Mirrors the desktop Settings
  screens (server-relevant subset).
- **IME** (`MyWhisperIME`): a mic bar, not a keyboard. The button is
  **Smart by default** — hold and release stops, a quick tap toggles
  (second tap finishes) — using the desktop `smart_tap_ms` setting.
  Results commit through the `InputConnection` (the Android equivalent
  of desktop auto-paste).

## Scope (v1)

**Server functionality only**: the STT chain supports Self-hosted
(OpenAI-compatible HTTP, sherpa-protocol WS) and Cloud entries.
`Local` entries are skipped — on-device inference lands later (see
`core/README.md`). Sync UI is not included yet; the store schema
already carries the sync columns.

## Layout

- `core/` — Rust engine (`mywhisper-core`), JNI `cdylib`.
  Verbatim desktop ports (`settings`, `audio`, `textnorm`, `store`)
  plus mobile-only `server_stt`, `engine`, JNI in `lib.rs`.
- `android/` — Kotlin app (Compose config + IME service).
- `scripts/build-core.sh` — `cargo ndk` release build for
  `arm64-v8a` + `x86_64` into `jniLibs` (runs automatically via the
  `buildRustCore` Gradle task).

## Build

Prerequisites: Android SDK (platform 36, NDK 28.x, CMake 3.22),
Rust targets `aarch64-linux-android` + `x86_64-linux-android`,
`cargo-ndk`, JDK 17.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then: open the app → enable the input method → switch to it →
grant mic → configure a server under Speech.

## Bringing desktop updates over

See `core/README.md`. Rule of thumb: `settings.rs`, `audio.rs`,
`textnorm.rs`, `store.rs` are byte-ports — re-copy the desktop file,
re-apply the marked `ANDROID PORT` patches, run `cargo test`.
