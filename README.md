<div align="center">
  <img src="assets/icons/readme-icon.png" alt="MyWhisperMobile icon" width="112" />
  <h1>MyWhisperMobile</h1>
  <p>Voice typing for Android, powered by the MyWhisper engine.</p>
</div>

Dictate into other apps using a floating microphone button or the
MyWhisper input method. Configure speech recognition, text cleanup, and
other settings from the app.

## Dictation at a glance

- **Floating button** — keep a microphone available over other apps and
  dictate into a focused text field. Setup guides you through microphone,
  display-over-apps, and dictation-service permissions.
- **MyWhisper keyboard** — an alternative input method with a mic bar.
  Hold and release to stop, or tap to start and tap again to finish.
- **Listening indicator** — an optional floating level meter shows audio
  activity while recording.
- **Your setup** — manage speech providers, text cleanup, dictionary,
  history, preferences, and sync from the app.

## Speech recognition

The supported default is server-based speech recognition, including
self-hosted OpenAI-compatible HTTP and sherpa-protocol WebSocket servers,
and cloud providers. On-device speech recognition and text cleanup are
experimental and off by default; they require verified model files. See
[core/README.md](core/README.md) for engine details.

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

Then open the app and follow setup to configure speech recognition and
enable the floating button. To use the keyboard instead, enable and switch
to the MyWhisper input method and grant microphone access.

## Bringing desktop updates over

See `core/README.md`. Rule of thumb: `settings.rs`, `audio.rs`,
`textnorm.rs`, `store.rs` are byte-ports — re-copy the desktop file,
re-apply the marked `ANDROID PORT` patches, run `cargo test`.
