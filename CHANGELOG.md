# Changelog

All notable changes to this project are documented here.

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
