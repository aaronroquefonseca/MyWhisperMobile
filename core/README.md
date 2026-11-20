# mywhisper-core

Portable MyWhisper engine for Android, exposed over JNI (`lib.rs`).

## Port map (desktop → mobile)

| File | Status | Notes |
|---|---|---|
| `settings.rs` | verbatim port | + `ANDROID PORT` patches: pill enums from `viz_types`, not GTK `viz`; `preferences.mic_mode` (mobile-only mic-button behavior) |
| `audio.rs` | verbatim port | Recorder/resample/VAD/WAV all portable |
| `textnorm.rs` | verbatim port | snippet matching (accent/case-folded) + expansion, whisper prompt budgets |
| `store.rs` | verbatim port | + `ANDROID PORT` patch: `db_key()` reads `MYWHISPER_DB_KEY` (set by JNI from the Keystore key); keyring branch kept for host builds/tests |
| `device.rs` | verbatim port | node identity (`device.key`), same keys as desktop |
| `net.rs` | verbatim port | Noise_XX sync transport; same protocol version as desktop |
| `viz_types.rs` | mobile-only | serde clones of desktop `viz.rs` pref enums (no pill on Android) |
| `server_stt.rs` | mobile-only | server STT chain client; wire protocols mirror desktop `transcribe.rs` |
| `sync_ctl.rs` | mobile-only | sync lifecycle (listener + mDNS + loops) over `net.rs` |
| `engine.rs` | mobile-only | utterance pipeline mirroring desktop `deliver`; cleanup is a faithful `finalize_transcript`/`cleanup_once` port (snippets protected by placeholders through cleanup, same default prompt, same endpoints, same failover; `Local` entries route through `local_llm` with fallback) |
| `local_llm.rs` | mobile-only | on-device LLM cleanup (MediaPipe `.task` chosen provider): prompt packing, SHA-256 manifest verify, sidecar/Fn backends; cleanup path only, never STT |
| `lib.rs` | mobile-only | JNI bridge |

Desktop source: `github:aaronroquefonseca/MyWhisper`, `src/`.

## Updating from desktop

1. `cp <desktop>/src/{settings,audio,textnorm,store}.rs core/src/`
2. Re-apply the `ANDROID PORT` patches (search the marker):
   - `settings.rs`: serde/viz_types import + `Viz*` type paths.
   - `store.rs`: `db_key` cfg split (`db_key_desktop` for hosts).
3. `cargo test` (desktop store/audio tests run here too).

Desktop-only modules with no mobile equivalent: `app*`, `overlay`
(pill), `viz` (GTK), `hotkey` (evdev), `mic` (parec/cpal →
`AudioCapture.kt`), `clipboard` (wl-copy → `InputConnection`),
`service` (systemd → processes own the core), `net` (sync — port
later via `NsdManager`), `stt`/`transcribe` server supervision
(replaced by `server_stt`), `media`/`sound`, `paths`, `update`,
`speculative`, `device`, `i18n`.

## Crypto key

Desktop keeps the SQLCipher key in the Secret Service and `state.db`
is encrypted. Android keeps a key in Keystore-backed
`EncryptedSharedPreferences` (`KeystoreKey.kt`) and passes it
per-process to `nativeOpen`, which sets `MYWHISPER_DB_KEY` before
`Store::open`. Android builds plain SQLite (see `Cargo.toml`), which
ignores `PRAGMA key`: on Android `state.db` is **not encrypted** today,
and only the app sandbox and Android's file-based encryption protect
it. The key is reserved for optional SQLCipher
([#111](https://github.com/aaronroquefonseca/MyWhisperMobile/issues/111)).

## TODO (later)

- On-device STT (sherpa-onnx/whisper JNI) → stop skipping `Local` chain entries.
- Streaming partials (sherpa WS online pump while held).
- Optional SQLCipher on Android (needs vendored OpenSSL; currently
  plaintext `bundled`), tracked in #111.
- Gemini cloud STT.
- Foreground service for always-on sync (currently runs while the app
  or IME process lives, plus manual/auto rounds).
