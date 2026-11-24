# Changelog

All notable changes to this project are documented here.

## [Unreleased]

## [1.4.0] - 2026-11-24

### Added
- Activity tab, now the default: a floating button quick toggle, usage stats with a speaking-activity heatmap, and history grouped by day (tap to copy, hold or expand to see the original transcription). It refreshes on its own when a dictation is saved.
- Paged onboarding: welcome, floating button setup, optional notifications, then practice with the real floating button over a real text field. A bobbing arrow points at the button the first time it appears.
- Tablet and landscape layout: a navigation rail replaces the bottom bar on wide windows and in phone landscape, pages keep a readable width, and from 840dp Activity shows overview and history side by side. Content stays clear of camera cutouts.
- Failed dictations, and cleanup that failed on every provider, are reported on the Activity tab; cleanup failures also show a short toast.
- Diagnostics can be copied or shared.

### Changed
- Tabs reorganized around Activity, Providers, Dictionary and Settings. Providers splits speech recognition and text cleanup into swipeable tabs; Settings holds the floating button, sync and app pages, with the setup guide at the top.
- Snippets survive cleanup: triggers are matched ignoring case and accents, kept away from the cleanup model, and restored word for word afterwards.
- Sync sends only what changed since the last complete round instead of everything each time.
- Light and dark themes fade into each other instead of snapping.
- The bubble's processing dots animate one after another, like a typing indicator.
- Error bubbles are short and say what to do: configure a provider first, or all providers failed.
- Pressing the floating button without microphone access explains why instead of doing nothing.
- Granting overlay or accessibility access brings you back to MyWhisper.

### Fixed
- Claude cleanup requests no longer fail: the old request asked for a thinking budget larger than its token limit. Truncated, refused or tool-call replies are no longer pasted as text.
- Line breaks from whisper.cpp servers no longer end up in transcripts.
- Settings synced from a MyWhisper desktop using its new Bloom pill style no longer fail to load.
- Sync respects the sync switch and per-device trust on every round, never sends the text of deleted entries, and restarts reliably. Records dated more than a week ahead are refused.
- First launch, database upgrades and encryption key setup are safe against interruption and concurrent starts; one corrupted setting falls back to its default instead of resetting the rest.
- Changing the app icon works in side-by-side test builds.
- Activity no longer jumps while scrolling.

### Security
- Dictated text is no longer written to the system log (logcat). Bug reports and `adb logcat` captures showed every transcription in full; the log now records only its length.
- Sync connections are limited before authentication (count, time and message size) and only accept well-formed device ids.

## [1.3.0] - 2026-11-18

### Added
- Preferred microphone: rank Wired, Built-in and Bluetooth inputs (in that order by default); dictation uses the first one connected. Bluetooth headsets switch to call mode only when ranked above the built-in mic.
- Floating button: Extra small mode, a toggle under the size slider. The button rests as the smallest round button and grows to full size while recording. Size and Shrink smaller pause while it is on and come back when it is off; the dot options still work.

### Changed
- A shrunk floating button is a circle again, as the settings preview shows.
- Floating button corners are adaptive again: a fixed corner size, so smaller sizes look rounder and larger ones squarer. Advanced has a Button shape option to switch back to the uniform shape.
- Shrink delay now runs from 2 to 10 s, default 5 s.
- Snooze duration moved to Advanced.
- The floating button preview loops through the idle change, sped up, with a dashed outline of the starting shape and a caption for each step.
- Floating button settings no longer jump when toggles change their help text; options that do not apply are greyed out instead of hidden, and notes slide in.
- Microphone behavior settings redesigned: Smart, Hold or Tap mode is a segmented control with a line explaining each, and the quick-tap limit and extra listening delay (moved here from Voice) are sliders.

### Removed
- The MyWhisper keyboard (input method). The floating button covers dictation in every app; if the MyWhisper keyboard was your active keyboard, Android switches back to your previous one after the update.
- The Android 12 and below warning on the floating button page: placeholder text no longer leaks into dictations there.

### Fixed
- Dragging a row in the transcription or cleanup model chain no longer flips it back and forth or loses later moves.
- The dot-shaped floating button is centered on the snooze target.

## [1.2.0] - 2026-11-17

### Added
- Floating button: five sizes with haptic ticks, a thin outline so it stays visible on light backgrounds, and idle modes that shrink it or turn it into a dot after a configurable delay (1–5 s, default 2 s), optionally also over search fields.
- LLM cleanup entries can show and hide their API key, like speech-to-text entries.

### Changed
- One APK for everyone: on-device AI no longer makes the app bigger. Its runtime (llama.cpp + whisper.cpp, built from source, ~17 MB) downloads with the first local model. MyWhisper Lite is discontinued; installing MyWhisper over it keeps your settings.
- On-device speech now runs on whisper.cpp: Whisper Tiny/Base/Small/Large v3 Turbo and NVIDIA Parakeet TDT v3 (25 languages incl. Spanish). Whisper takes your dictionary words as a prompt. Short dictations transcribe several times faster.
- On-device cleanup now runs Gemma GGUF models on llama.cpp (Gemma 3 1B, Gemma 4 E2B/E4B). The system prompt is cached between dictations, output that copies the transcript is drafted from it (prompt-lookup decoding), and the model stays loaded for 10 minutes by default: a warm Gemma 4 E2B cleanup test takes ~0.8 s instead of ~5 s.
- Local models use the GPU (Vulkan, or OpenCL on Adreno) when it is faster on your phone, otherwise the CPU; a GPU that fails or crashes is skipped automatically.
- The recording visualizer reacts clearly to your voice: silence shows dots, and speaking opens the bars from the middle out. Background noise is ignored and quiet voices are boosted.
- The screen stays awake while a floating-button dictation is transcribing and pasting.
- Floating button visibility, hold delay and readiness moved to Advanced, now an expandable section.

### Removed
- Moonshine, Whisper Medium/Large v3 and Nemotron streaming models (Whisper Large v3 Turbo and Parakeet replace them), and the MediaPipe / LiteRT-LM / sherpa-onnx runtimes. Model files in those old formats are deleted on update to free the space.

### Fixed
- LLM cleanup now also runs on on-device transcripts, and the on-device cleanup model runs during dictation instead of only in its self-test. A Local speech-to-text entry below a working server is used as a fallback instead of always winning.
- Local models no longer re-verify in a loop that left them stuck in "Checking".
- No more white pixels outside the floating button's border at its corners.

## [1.1.0] - 2026-11-15

### Added
- Appearance → App icon: choose a launcher icon palette (Navy / cyan, Cobalt / white, Cream / ink, Charcoal / white) or Default, which follows the installed channel. Your choice persists across updates.

### Changed
- Nightly builds default to the Cobalt / white icon instead of purple.
- F-Droid repo updates are published as soon as a release or nightly is built, instead of on a schedule.

## [1.0.0] - 2026-11-15

First stable release. Signed APKs are published to GitHub Releases and to the
F-Droid repository at https://f-droid.aaronroque.biz.

### Added
- Floating button: grip-free drag with hold delay, refined recording gestures (tap, hold, slide to lock or cancel), snooze, and transparency control.
- Dictation, Voice, and Library sections reorganize the app.
- On-device models: provider pills, Whisper large/turbo, Nemotron streaming, LiteRT-LM runner with Gemma 4, per-model keep-warm and thinking budgets.
- Background model downloads via WorkManager with progress notification, resume over HTTP Range, and explicit cancel.
- On-device diagnostics card with step logs and in-app log tail.
- F-Droid listing (description, icon, screenshots) maintained in `fastlane/metadata/android`.
- Nightly builds from every push to `main`: "MyWhisper Nightly" with a purple icon, published as a rolling GitHub prerelease and as F-Droid Beta updates.
- MyWhisper Lite (server-only) APK attached to releases; all variants share one package so you can switch without losing settings.

### Changed
- Release APKs are signed and published to the F-Droid repo.
- versionCode is now the release number × 1000, leaving room for nightlies in between.
- Flattened launcher icon colors.
- Bubble commits text via clipboard + paste and restores the user's clipboard afterwards.

### Fixed
- Model verification no longer freezes the UI.
- Bubble transparency applied at window composition; hidden when the dictation service is off.
- Outbound HTTPS uses bundled roots; provider Test probes require 2xx and send trimmed bearer tokens.
- Faster, explicit sync startup after reboot.

## [0.2.0-beta.1] - 2026-10-15

First beta. Server-first voice typing (shared Rust core + Android IME)
with on-device STT/LLM held experimental and default-off.

### Added
- Onboarding: guided setup checklist, mic rationale, overlay CTA.
- Notification modes: per-mode copy, expandable actions, prefs-synced policy.
- Unified foreground row: single notification for sync + bubble; retired legacy rows/channels.
- Bubble redesigns: tap-safe grip-move bubble with edge snap and overlay recovery; calm processing ring, explicit stop, 48dp stop target, spinner-above-mic.
- IME UX: explicit cancel button, first-hold slide hint, state-driven mic visuals, cancel-never-transcribes, fixed-height record row, bigger mic glyph.
- Settings jobs: generation-aware cancellable jobs, async reset with retry.
- Sync reliability: single-owner start, boot retry chain, update restart, resolve-skip, fresh-first dial, address learning, instant push on mutations.
- Storage/sync auth: transactional authz, tombstones, epochs; strict dial gate; horizon at remote entry; plaintext sidecar wipe, non-hex DB key rejection.
- Provider contracts: capability-aware budgets, UTF-8 prompt boundaries, unified STT add flow with LLM kind-picker.
- Session ownership: witness-gated failover, bounded provider I/O, shutdown invalidation.
- CI + release: unit-only CI (Rust + Android jobs); tag-triggered (`v*`) changelogged APK release workflow; release buildType with R8 + shrinkResources.
- Local STT (experimental, default-off): on-device decode with VAD chunking, server fallback intact.
- Local LLM cleanup (experimental, default-off): on-device cleanup with cloud fallback.

### Changed
- FGS lifecycle: stop on foreground denial instead of running un-foregrounded; Dictation listener detach on STOP/destroy, re-attach on START.
- Notification REFRESH never promotes a stopped service.
- Codebase formatted: rustfmt import ordering plus `clippy -D warnings` micro-fixes.

### Fixed
- SQLite BUSY retries for multi-process App+IME writes.
- Bubble degenerate snap guard; IME cancel-vs-inflight race; blank transcripts skipped from history.
- Sync-auth excursion scrub before reset tombstones; capped streaming bodies with witness recheck.

### Deferred
- Release APK is unsigned (`:app:assembleRelease`); signing via secrets not wired yet.
- On-device STT/LLM remain experimental and default-off; server path is the supported default.
- Local-decode gating follow-ups owned by a separate STT fixup thread.

## [Unreleased]

### Added
- Release pipeline groundwork: versionCode/versionName scheme via `gradle.properties`.
- Release buildType with R8 (`minifyEnabled`) + `shrinkResources` check.
- Documented `signingConfig` via env vars (unsigned build by default).

### Changed
- None yet.

### Fixed
- None yet.
