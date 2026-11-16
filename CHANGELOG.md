# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Changed
- One APK for everyone: on-device AI no longer makes the app bigger. Its runtime (llama.cpp + whisper.cpp, built from source, ~17 MB) downloads with the first local model. MyWhisper Lite is discontinued; installing MyWhisper over it keeps your settings.
- On-device speech now runs on whisper.cpp: Whisper Tiny/Base/Small/Large v3 Turbo and NVIDIA Parakeet TDT v3 (25 languages incl. Spanish). Whisper takes your dictionary words as a prompt. Short dictations transcribe several times faster.
- On-device cleanup now runs Gemma GGUF models on llama.cpp (Gemma 3 1B, Gemma 4 E2B/E4B). The system prompt is cached between dictations, output that copies the transcript is drafted from it (prompt-lookup decoding), and the model stays loaded for 10 minutes by default: a warm Gemma 4 E2B cleanup test takes ~0.8 s instead of ~5 s.
- Local models use the GPU (Vulkan, or OpenCL on Adreno) when it is faster on your phone, otherwise the CPU; a GPU that fails or crashes is skipped automatically.

### Removed
- Moonshine, Whisper Medium/Large v3 and Nemotron streaming models (Whisper Large v3 Turbo and Parakeet replace them), and the MediaPipe / LiteRT-LM / sherpa-onnx runtimes. Model files in those old formats are deleted on update to free the space.

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
