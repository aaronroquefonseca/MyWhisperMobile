<div align="center">
  <img src="assets/icons/readme-icon.png" alt="MyWhisperMobile icon" width="112" />
  <h1>MyWhisperMobile</h1>
  <p>Voice typing for Android, powered by the MyWhisper engine.</p>
</div>

Dictate into other apps using a floating microphone button or the
MyWhisper input method. Configure speech recognition, text cleanup, and
other settings from the app.

## Install

MyWhisper is also available on my own F-Droid repository, with automatic
updates: [**add it here**](https://f-droid.aaronroque.biz), then install
MyWhisper from F-Droid.

Or download an APK from [GitHub Releases](https://github.com/aaronroquefonseca/MyWhisperMobile/releases):

- **MyWhisper**: the app. On-device models are optional; their runtime
  (~17 MB) downloads with the first model you pick, so the APK stays small
  for everyone.
- **Nightly**: built from every change on `main`
  ([rolling prerelease](https://github.com/aaronroquefonseca/MyWhisperMobile/releases/tag/nightly)).
  On F-Droid, enable beta updates for MyWhisper to get them.

Stable and nightly are the same app, so you can switch between them by
installing another APK over it, keeping your settings. Android only installs an equal
or newer version, so going from a nightly back to stable waits for the next
release.

## Floating button

Focus any text field and the floating button appears over the app. A quick
tap starts dictation; tap ✓ to transcribe and insert the text, or ✕ to
discard it. Or press and hold to talk, release to send, slide up to lock
recording, or slide down to cancel. While you talk, the level meter blooms
out from the center with your voice. It follows the keyboard, snaps to the
screen edge, and can be dragged, faded, locked, or snoozed.

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/pill-ready.png" alt="Floating button resting above the keyboard in Messages" width="220" /><br />
      <sub><b>Ready</b><br />rests above the keyboard</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/pill-recording.png" alt="Floating button after a tap, listening with live audio levels and confirm and cancel controls" width="220" /><br />
      <sub><b>Tap to dictate</b><br />✓ inserts · ✕ cancels</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/pill-hold.png" alt="Floating button held down, listening with live audio levels, a lock target above and a cancel target below" width="220" /><br />
      <sub><b>Hold to talk</b><br />release sends · ↑ lock · ↓ cancel</sub>
    </td>
  </tr>
</table>

Pick from five sizes, XS to XL. When idle, the button can keep its size,
shrink after a delay, shrink to a small dot, or start as a dot. It still
responds to a tap or hold in every shape.

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/pill-small.png" alt="Floating button shrunk to a smaller size while idle in Messages" width="220" /><br />
      <sub><b>Shrink when idle</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/pill-dot.png" alt="Floating button reduced to a small dot at the screen edge while idle" width="220" /><br />
      <sub><b>Or shrink to a dot</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/button-appearance.png" alt="Floating button settings with a size slider from XS to XL and four idle options" width="220" /><br />
      <sub><b>Size and idle settings</b></sub>
    </td>
  </tr>
</table>

## Screenshots

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/setup.png" alt="Setup checklist for microphone, overlay, dictation service, and speech recognition" width="220" /><br />
      <sub><b>Guided setup</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/dictation.png" alt="Dictation tab with floating button and app settings" width="220" /><br />
      <sub><b>Dictation</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/speech-providers.png" alt="Speech provider chain with a self-hosted Whisper server" width="220" /><br />
      <sub><b>Speech providers</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/voice.png" alt="Voice tab with speech recognition and text cleanup" width="220" /><br />
      <sub><b>Voice</b></sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/library.png" alt="Library tab with dictionary, history, activity, and sync" width="220" /><br />
      <sub><b>Library</b></sub>
    </td>
    <td></td>
  </tr>
</table>

## Dictation at a glance

- **Floating button** — keep a microphone available over other apps and
  dictate into a focused text field. Setup guides you through microphone,
  display-over-apps, and dictation-service permissions.
- **MyWhisper keyboard** — an alternative input method with a mic bar.
  Hold and release to stop, or tap to start and tap again to finish.
- **Listening indicator** — an optional floating level meter shows audio
  activity while recording.
- **Microphone priority** — rank wired, built-in, and Bluetooth inputs;
  dictation uses the first one connected.
- **Your setup** — manage speech providers, text cleanup, dictionary,
  history, preferences, and sync from the app.

## Speech recognition

The supported default is server-based speech recognition, including
self-hosted OpenAI-compatible HTTP and sherpa-protocol WebSocket servers,
and cloud providers. On-device speech recognition and text cleanup are
experimental and off by default; they require verified model files. See
[core/README.md](core/README.md) for engine details.

On-device models run on [llama.cpp](https://github.com/ggml-org/llama.cpp)
(cleanup: Gemma GGUF) and [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
(speech: Whisper and NVIDIA Parakeet), built from source into a runtime pack
the app downloads with the first local model (arm64 only). Inference runs on
the GPU (Vulkan, or OpenCL on Adreno) when that is faster on the phone,
otherwise on the CPU; the app measures this per device.

## Layout

- `core/` — Rust engine (`mywhisper-core`), JNI `cdylib`.
  Verbatim desktop ports (`settings`, `audio`, `textnorm`, `store`)
  plus mobile-only `server_stt`, `engine`, JNI in `lib.rs`.
- `android/` — Kotlin app (Compose config + IME service).
- `scripts/build-core.sh` — `cargo ndk` release build for
  `arm64-v8a` + `x86_64` into `jniLibs` (runs automatically via the
  `buildRustCore` Gradle task).
- `android/localai/` — C API + JNI over llama.cpp and whisper.cpp, with
  local patches to the pinned upstream sources; `scripts/build-localai.sh`
  builds the runtime pack (`BENCH=1` adds the `mwbench` on-device CLI).

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

## F-Droid listing

The F-Droid store text, icon and screenshots live in
[`fastlane/metadata/android/en-US`](fastlane/metadata/android/en-US):
edit `title.txt`, `short_description.txt` and `full_description.txt`, and
pick screenshots by adding or removing symlinks in `images/phoneScreenshots`
(shown in file-name order). Merging to `main` publishes the listing; each
release adds its CHANGELOG section as the "What's new" text.

## Releasing

1. In `android/gradle.properties`, bump `mywhisper.versionCode` by 1 and set
   `mywhisper.versionName`; add a matching `## [x.y.z]` section to
   `CHANGELOG.md`.
2. Merge to `main`, then push the tag `vx.y.z`. The release workflow signs
   the APK, creates the GitHub release, and sends it to F-Droid. The
   on-device AI runtime pack is rebuilt only when its inputs change and is
   published as its own `localai-<hash>` prerelease.

The real versionCode is `mywhisper.versionCode × 1000`. Every push to `main`
builds a nightly numbered from the last bump (e.g. 51003), so nightlies sit
between one release and the next.

## Bringing desktop updates over

See `core/README.md`. Rule of thumb: `settings.rs`, `audio.rs`,
`textnorm.rs`, `store.rs` are byte-ports — re-copy the desktop file,
re-apply the marked `ANDROID PORT` patches, run `cargo test`.
