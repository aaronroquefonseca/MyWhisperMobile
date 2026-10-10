# mywhisper-core

Portable MyWhisper engine for Android, exposed over JNI (`lib.rs`).

## Port map (desktop → mobile)

| File | Status | Notes |
|---|---|---|
| `settings.rs` | verbatim port | + `ANDROID PORT` import patch: pill enums come from `viz_types`, not GTK `viz` |
| `audio.rs` | verbatim port | Recorder/resample/VAD/WAV all portable |
| `textnorm.rs` | verbatim port | snippet expansion + whisper prompt budgets |
| `store.rs` | verbatim port | + `ANDROID PORT` patch: `db_key()` reads `MYWHISPER_DB_KEY` (set by JNI from the Keystore key); keyring branch kept for host builds/tests |
| `viz_types.rs` | mobile-only | serde clones of desktop `viz.rs` pref enums (no pill on Android) |
| `server_stt.rs` | mobile-only | server STT chain client; wire protocols mirror desktop `transcribe.rs` |
| `engine.rs` | mobile-only | utterance pipeline mirroring desktop `deliver` (minus local servers, speculation, streaming partials) |
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

Desktop keeps the SQLCipher key in the Secret Service. Android keeps
it in Keystore-backed `EncryptedSharedPreferences` (`KeystoreKey.kt`)
and passes it per-process to `nativeOpen`, which sets
`MYWHISPER_DB_KEY` before `Store::open`. Same ciphertext format —
a `state.db` moved between devices opens with the same key.

## TODO (later)

- On-device STT (sherpa-onnx/whisper JNI) → stop skipping `Local` chain entries.
- Streaming partials (sherpa WS online pump while held).
- Sync (`net.rs` port over `NsdManager` + same Noise protocol).
- Gemini cloud STT + Claude/Google cleanup endpoints.
