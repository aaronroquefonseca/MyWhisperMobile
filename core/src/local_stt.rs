//! On-device STT pack plumbing (meter branch).
//!
//! The actual inference lives on the Kotlin side via the sherpa-onnx
//! AAR (`LocalStt.kt`, `OfflineRecognizer`). This module owns the
//! shared contract: pack layout under `filesDir`, the pinned SHA256
//! manifest, and the `try_transcribe` entry the engine calls with
//! `filesDir` plumbed through.
//!
//! Rust has no ONNX runtime linked, so `try_transcribe` verifies the
//! pack and returns `Err` when inference must happen elsewhere
//! (Kotlin) or the pack is absent — the server chain fallback stays
//! intact. The meter harness + `LocalStt.kt` do the real decode.

use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

/// Model pack dir relative to `filesDir`.
pub const PACK_REL_DIR: &str = "models/sherpa-tiny.en";

/// Expected pack files (int8 whisper tiny.en).
pub const PACK_FILES: &[&str] = &[
    "tiny.en-encoder.int8.onnx",
    "tiny.en-decoder.int8.onnx",
    "tiny.en-tokens.txt",
];

/// Pinned SHA256 manifest (mirrors `core/models/sherpa-tiny.en.manifest.json`,
/// filled by `scripts/pin-model-manifest.sh`). verify fails closed on mismatch.
pub const PACK_MANIFEST: &[(&str, &str)] = &[
    (
        "tiny.en-encoder.int8.onnx",
        "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6",
    ),
    (
        "tiny.en-decoder.int8.onnx",
        "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527",
    ),
    (
        "tiny.en-tokens.txt",
        "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930",
    ),
];

/// Pack dir for a given `filesDir`.
pub fn pack_dir(files_dir: &Path) -> PathBuf {
    files_dir.join(PACK_REL_DIR)
}

/// Verify pack presence (+ SHA256 when pinned). `Ok(())` = usable.
pub fn verify_pack(files_dir: &Path) -> Result<(), String> {
    let dir = pack_dir(files_dir);
    if !dir.is_dir() {
        return Err(format!("local pack missing: {}", dir.display()));
    }
    for (name, pinned) in PACK_MANIFEST {
        let p = dir.join(name);
        let bytes = std::fs::read(&p).map_err(|e| format!("local pack read {name}: {e}"))?;
        if bytes.is_empty() {
            return Err(format!("local pack empty: {name}"));
        }
        if !pinned.is_empty() {
            let digest = format!("{:x}", Sha256::digest(&bytes));
            if digest != *pinned {
                return Err(format!("local pack sha mismatch: {name}"));
            }
        }
    }
    Ok(())
}

/// Engine entry: `filesDir` plumbed through. Returns `Err` when the
/// pack is absent/invalid (caller falls back to the server chain) —
/// Rust never runs inference here; Kotlin `LocalStt` does.
pub fn try_transcribe(files_dir: &Path, _pcm16: &[i16]) -> Result<String, String> {
    verify_pack(files_dir)?;
    Err("local inference lives in LocalStt.kt (sherpa-onnx AAR); rust try_transcribe verifies pack only".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            format!("{:x}", Sha256::digest(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn missing_pack_fails_closed() {
        let dir = std::env::temp_dir().join(format!("mw-local-missing-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let err = verify_pack(&dir).unwrap_err();
        assert!(err.contains("missing"), "{err}");
        assert!(try_transcribe(&dir, &[0i16; 160]).is_err());
    }

    #[test]
    fn pinned_pack_rejects_wrong_content() {
        let dir = std::env::temp_dir().join(format!("mw-local-present-{}", std::process::id()));
        let pack = dir.join(PACK_REL_DIR);
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&pack).unwrap();
        for n in PACK_FILES {
            std::fs::write(pack.join(n), b"fake-bytes").unwrap();
        }
        // Pins are live: fake content fails closed on sha mismatch.
        let err = verify_pack(&dir).unwrap_err();
        assert!(err.contains("sha mismatch"), "{err}");
        assert!(try_transcribe(&dir, &[0i16; 160]).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
