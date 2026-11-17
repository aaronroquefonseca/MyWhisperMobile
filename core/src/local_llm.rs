//! On-device LLM cleanup (text polish only — never STT).
//!
//! Runner: llama.cpp (GGUF) from the app's downloadable runtime pack,
//! driven from Kotlin (`LocalLlmManager.kt`, GPU first). This module owns
//! the portable pieces the engine needs: prompt packing, model-asset
//! manifest + SHA-256 verification, sidecar/bridge backend plumbing, and
//! the cleanup-path hook. Kotlin registers itself through
//! [`set_local_runner`] at process start; dictation cleanup reaches it via
//! [`try_local_cleanup`].
//!
//! Fallback: the engine tries `Local` entries through
//! [`try_local_cleanup`]; when no runner or model is ready it
//! returns `Err` and the chain falls through to the next enabled
//! entry (Ollama/cloud). Default chain order is unchanged.

use crate::settings::LlmEntry;
use std::path::Path;

/// Portable config for one on-device cleanup call, derived from an
/// `LlmEntry` of kind `Local`: `model` is the `.gguf` file path (or
/// model id when unresolved), `url` optionally overrides the model
/// dir / sidecar endpoint.
#[derive(Debug, Clone)]
pub struct LocalLlmConfig {
    /// Absolute model path when resolved, else the stored id.
    pub model_path: String,
    /// Model dir, or `http(s)://` sidecar endpoint for tests/bridges.
    pub model_dir: String,
    pub max_tokens: u32,
    pub temperature: f32,
    pub timeout_secs: u64,
}

impl LocalLlmConfig {
    pub fn from_entry(entry: &LlmEntry, data_dir: &Path) -> Self {
        let model = entry.model.trim();
        let resolved = if std::path::Path::new(model).is_absolute() {
            model.to_string()
        } else if model.is_empty() {
            default_task_path(data_dir)
        } else {
            data_dir
                .join("models")
                .join(model)
                .to_string_lossy()
                .into_owned()
        };
        let url = entry.url.trim();
        Self {
            model_path: resolved,
            model_dir: if url.is_empty() {
                data_dir.join("models").to_string_lossy().into_owned()
            } else {
                url.to_string()
            },
            max_tokens: 512,
            temperature: 0.0,
            timeout_secs: 60,
        }
    }

    /// True when a model file exists at `model_path`.
    pub fn model_ready(&self) -> bool {
        !self.model_path.trim().is_empty() && Path::new(&self.model_path).is_file()
    }
}

/// Default on-device model location: `<data>/models/cleanup.gguf`.
pub fn default_task_path(data_dir: &Path) -> String {
    data_dir
        .join("models")
        .join("cleanup.gguf")
        .to_string_lossy()
        .into_owned()
}

/// Pack the cleanup prompt for a single-turn generation call.
/// Kept byte-stable so Kotlin can mirror it exactly.
pub fn build_cleanup_prompt(system: &str, raw: &str) -> String {
    format!("{system}\n\n<transcript>{raw}</transcript>")
}

// -- model manifest (download manager + SHA pinning) --------------------

/// One downloadable model asset. Binaries are NEVER committed;
/// `scripts/fetch-local-llm.sh` downloads + verifies these.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct ModelAsset {
    pub id: String,
    pub url: String,
    pub sha256: String,
    pub size_bytes: u64,
}

/// Parse `scripts/local-llm-models.json` (`{"models": [...]}`).
pub fn parse_manifest(json: &str) -> Result<Vec<ModelAsset>, String> {
    let v: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("manifest json: {e}"))?;
    let arr = v
        .get("models")
        .and_then(|m| m.as_array())
        .ok_or_else(|| "manifest has no models[]".to_string())?;
    arr.iter()
        .map(|m| serde_json::from_value(m.clone()).map_err(|e| format!("manifest entry: {e}")))
        .collect()
}

/// Verify `path` against an expected lowercase hex SHA-256.
/// Pure-std (no extra deps): shells out to nothing, streams the file.
pub fn verify_file_sha256(path: &Path, expected_hex: &str) -> Result<(), String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path).map_err(|e| format!("open: {e}"))?;
    let mut buf = [0u8; 65536];
    let mut h = Sha256::new();
    loop {
        let n = f.read(&mut buf).map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    let got = hex_encode(&h.finish());
    if got.eq_ignore_ascii_case(expected_hex.trim()) {
        Ok(())
    } else {
        Err(format!("sha256 mismatch for {}", path.display()))
    }
}

// Minimal SHA-256 (FIPS 180-4) to avoid new deps on Android.
struct Sha256 {
    h: [u32; 8],
    len_bytes: u64,
    buf: Vec<u8>,
}

impl Sha256 {
    fn new() -> Self {
        Self {
            h: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
                0x5be0cd19,
            ],
            len_bytes: 0,
            buf: Vec::new(),
        }
    }
    fn update(&mut self, data: &[u8]) {
        self.len_bytes += data.len() as u64;
        self.buf.extend_from_slice(data);
        while self.buf.len() >= 64 {
            let block: Vec<u8> = self.buf.drain(..64).collect();
            Self::compress(&mut self.h, &block);
        }
    }
    fn finish(mut self) -> [u8; 32] {
        let bit_len = self.len_bytes * 8;
        self.buf.push(0x80);
        while self.buf.len() % 64 != 56 {
            self.buf.push(0);
        }
        self.buf.extend_from_slice(&bit_len.to_be_bytes());
        while self.buf.len() >= 64 {
            let block: Vec<u8> = self.buf.drain(..64).collect();
            Self::compress(&mut self.h, &block);
        }
        let mut out = [0u8; 32];
        for (i, w) in self.h.iter().enumerate() {
            out[i * 4..i * 4 + 4].copy_from_slice(&w.to_be_bytes());
        }
        out
    }
    #[rustfmt::skip]
    fn compress(h: &mut [u32; 8], b: &[u8]) {
        const K: [u32; 64] = [
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
        ];
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([b[i*4],b[i*4+1],b[i*4+2],b[i*4+3]]);
        }
        for i in 16..64 {
            let s0 = w[i-15].rotate_right(7) ^ w[i-15].rotate_right(18) ^ (w[i-15] >> 3);
            let s1 = w[i-2].rotate_right(17) ^ w[i-2].rotate_right(19) ^ (w[i-2] >> 10);
            w[i] = w[i-16].wrapping_add(s0).wrapping_add(w[i-7]).wrapping_add(s1);
        }
        let (mut a,mut b,mut c,mut d,mut e,mut f,mut g,mut hh) =
            (h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh=g; g=f; f=e; e=d.wrapping_add(t1); d=c; c=b; b=a; a=t1.wrapping_add(t2);
        }
        h[0]=h[0].wrapping_add(a); h[1]=h[1].wrapping_add(b); h[2]=h[2].wrapping_add(c);
        h[3]=h[3].wrapping_add(d); h[4]=h[4].wrapping_add(e); h[5]=h[5].wrapping_add(f);
        h[6]=h[6].wrapping_add(g); h[7]=h[7].wrapping_add(hh);
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 15) as usize] as char);
    }
    s
}

// -- backends ------------------------------------------------------------

/// Generation backend. Production Android injects the MediaPipe
/// runner from Kotlin (via sidecar/JNI bridge); hosts and tests use
/// [`FnBackend`] or [`SidecarBackend`].
pub trait LocalBackend {
    fn generate(&self, prompt: &str) -> Result<String, String>;
}

/// Always-unavailable backend (model not downloaded yet).
pub struct UnavailableBackend(pub String);
impl LocalBackend for UnavailableBackend {
    fn generate(&self, _prompt: &str) -> Result<String, String> {
        Err(self.0.clone())
    }
}

/// Closure backend (tests, host harness).
pub struct FnBackend<F: Fn(&str) -> Result<String, String>>(pub F);
impl<F: Fn(&str) -> Result<String, String>> LocalBackend for FnBackend<F> {
    fn generate(&self, prompt: &str) -> Result<String, String> {
        (self.0)(prompt)
    }
}

/// HTTP sidecar backend (MediaPipe bridge / llama server exposing
/// `POST {base}/generate {"prompt": ...}` -> `{"text": ...}`).
/// Bounded fetch: 5 s connect, `timeout_secs` total, 1 MiB cap.
pub struct SidecarBackend {
    pub base_url: String,
    pub timeout_secs: u64,
}

impl LocalBackend for SidecarBackend {
    fn generate(&self, prompt: &str) -> Result<String, String> {
        let client = crate::tls::blocking_client()?
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(self.timeout_secs.max(1)))
            .build()
            .map_err(|e| format!("local sidecar client: {e}"))?;
        let url = format!("{}/generate", self.base_url.trim_end_matches('/'));
        let body = serde_json::json!({"prompt": prompt});
        let res = client
            .post(&url)
            .header("Content-Type", "application/json")
            .body(serde_json::to_string(&body).unwrap_or_default())
            .send()
            .map_err(|e| format!("local sidecar post: {e}"))?;
        if !res.status().is_success() {
            return Err(format!("local sidecar http {}", res.status()));
        }
        let text = crate::server_stt::capped_text(res, "local llm")?;
        let v: serde_json::Value =
            serde_json::from_str(&text).map_err(|e| format!("local sidecar json: {e}"))?;
        v.get("text")
            .and_then(|t| t.as_str())
            .map(|t| t.to_string())
            .ok_or_else(|| "local sidecar reply has no text".to_string())
    }
}

/// Run one cleanup turn through `backend`. Empty generations are an
/// error so the chain falls through to the next entry.
pub fn cleanup_with(
    backend: &dyn LocalBackend,
    system: &str,
    raw: &str,
    max_chars: usize,
) -> Result<String, String> {
    let prompt = build_cleanup_prompt(system, raw);
    trim_generation(&backend.generate(&prompt)?, max_chars)
}

/// Trim + cap one generation; empty is an error so the chain falls
/// through to the next entry.
fn trim_generation(out: &str, max_chars: usize) -> Result<String, String> {
    let trimmed = out.trim();
    if trimmed.is_empty() {
        return Err("local llm returned empty".to_string());
    }
    if trimmed.len() > max_chars {
        let mut end = max_chars;
        while !trimmed.is_char_boundary(end) {
            end -= 1;
        }
        Ok(trimmed[..end].trim().to_string())
    } else {
        Ok(trimmed.to_string())
    }
}

/// In-process runner the platform registers (Android: Kotlin
/// `LocalLlmCleanupRunner` over JNI). Takes `(system, transcript)`
/// split, because LiteRT-LM models them separately.
pub type LocalRunner = std::sync::Arc<dyn Fn(&str, &str) -> Result<String, String> + Send + Sync>;

static RUNNER: std::sync::RwLock<Option<LocalRunner>> = std::sync::RwLock::new(None);

/// Register (or clear with `None`) the in-process runner.
pub fn set_local_runner(runner: Option<LocalRunner>) {
    *RUNNER.write().unwrap_or_else(|e| e.into_inner()) = runner;
}

fn local_runner() -> Option<LocalRunner> {
    RUNNER.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Cleanup-path entry point used by the engine (`LlmKind::Local`
/// arm only). Resolution order:
/// 1. registered in-process runner → it picks the selected verified
///    pack and generates (fails with `Err` when none is installed);
/// 2. `http(s)` `model_dir` → sidecar POST (host tests only);
/// 3. otherwise → `Err`, so the chain falls through to the next
///    entry. Never panics, never touches the STT path.
///
/// The runner wins over the URL: Local rows saved through the Android
/// entry dialog keep its prefilled `http://127.0.0.1:11434`, which would
/// otherwise send every on-device cleanup to a dead sidecar.
pub fn try_local_cleanup(
    entry: &LlmEntry,
    system: &str,
    raw: &str,
    data_dir: &Path,
) -> Result<String, String> {
    if let Some(run) = local_runner() {
        return trim_generation(&run(system, raw)?, 4096);
    }
    let cfg = LocalLlmConfig::from_entry(entry, data_dir);
    if cfg.model_dir.starts_with("http://") || cfg.model_dir.starts_with("https://") {
        let backend = SidecarBackend {
            base_url: cfg.model_dir.clone(),
            timeout_secs: cfg.timeout_secs,
        };
        return cleanup_with(&backend, system, raw, 4096);
    }
    Err("local llm not ready: no on-device runner registered".to_string())
}

/// Status snapshot for the Settings UI / JNI.
pub fn status_json(entry: &LlmEntry, data_dir: &Path) -> String {
    let cfg = LocalLlmConfig::from_entry(entry, data_dir);
    let size: Option<u64> = std::fs::metadata(&cfg.model_path).ok().map(|m| m.len());
    serde_json::json!({
        "provider": "mediapipe",
        "modelPath": cfg.model_path,
        "ready": cfg.model_ready(),
        "sizeBytes": size,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_packs_system_and_transcript_tags() {
        let p = build_cleanup_prompt("SYS", "hola mundo");
        assert!(p.starts_with("SYS\n\n"));
        assert!(p.contains("<transcript>hola mundo</transcript>"));
    }

    #[test]
    fn config_resolves_relative_model_under_data_dir() {
        let e = LlmEntry {
            id: "lm-local".into(),
            name: "Local".into(),
            kind: crate::settings::LlmKind::Local,
            enabled: true,
            url: String::new(),
            model: "smollm2.task".into(),
            provider: crate::settings::CloudLlm::OpenAI,
            api_key: String::new(),
            keep_alive: String::new(),
            num_ctx: None,
            thinking: None,
        };
        let cfg = LocalLlmConfig::from_entry(&e, Path::new("/data"));
        assert_eq!(cfg.model_path, "/data/models/smollm2.task");
        assert!(!cfg.model_ready());
    }

    #[test]
    fn cleanup_with_trims_and_rejects_empty() {
        let ok = FnBackend(|_p| Ok("  Hola mundo.  ".to_string()));
        assert_eq!(cleanup_with(&ok, "S", "r", 4096).unwrap(), "Hola mundo.");
        let empty = FnBackend(|_p| Ok("   ".to_string()));
        assert!(cleanup_with(&empty, "S", "r", 4096).is_err());
    }

    #[test]
    fn cleanup_with_caps_output() {
        let big = FnBackend(|_p| Ok("x".repeat(5000)));
        let out = cleanup_with(&big, "S", "r", 100).unwrap();
        assert!(out.len() <= 100);
    }

    #[test]
    fn cleanup_with_cap_floors_to_char_boundary() {
        // "é" = 2 bytes; cap 5 lands mid-char ("aaaaé" = 6 bytes).
        let accented = FnBackend(|_p| Ok("aaaaébbbb".to_string()));
        let out = cleanup_with(&accented, "S", "r", 5).unwrap();
        assert_eq!(out, "aaaa");
        // Emoji = 4 bytes; cap inside first emoji must not panic.
        let emoji = FnBackend(|_p| Ok("a😀bcdef".to_string()));
        let out = cleanup_with(&emoji, "S", "r", 2).unwrap();
        assert_eq!(out, "a");
        assert!(out.len() <= 2);
    }

    #[test]
    fn missing_model_falls_back_with_err() {
        let e = LlmEntry {
            id: "lm-local".into(),
            name: "Local".into(),
            kind: crate::settings::LlmKind::Local,
            enabled: true,
            url: String::new(),
            model: "missing.task".into(),
            provider: crate::settings::CloudLlm::OpenAI,
            api_key: String::new(),
            keep_alive: String::new(),
            num_ctx: None,
            thinking: None,
        };
        // Engine tests register a process-global runner under this lock.
        let _guard = crate::server_stt::status_test_lock();
        let err = try_local_cleanup(&e, "S", "r", Path::new("/nonexistent")).unwrap_err();
        assert!(err.contains("not ready"), "got: {err}");
    }

    #[test]
    fn manifest_parses_and_rejects_bad_json() {
        let json = r#"{"models":[{"id":"smollm2-360m","url":"https://example/x.task","sha256":"abc","size_bytes":1}]}"#;
        let m = parse_manifest(json).unwrap();
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].id, "smollm2-360m");
        assert!(parse_manifest("{}").is_err());
    }

    #[test]
    fn sha256_verifies_known_vector() {
        // "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        let dir = std::env::temp_dir().join(format!("llm-sha-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let f = dir.join("v.bin");
        std::fs::write(&f, b"abc").unwrap();
        assert!(
            verify_file_sha256(
                &f,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            )
            .is_ok()
        );
        assert!(verify_file_sha256(&f, "00".repeat(32).as_str()).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn sidecar_backend_posts_prompt_and_reads_text() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (mut sock, _) = listener.accept().unwrap();
            let mut buf = Vec::new();
            let mut tmp = [0u8; 4096];
            loop {
                let n = sock.read(&mut tmp).unwrap();
                buf.extend_from_slice(&tmp[..n]);
                if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    break;
                }
            }
            let head_end = buf.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
            let headers = String::from_utf8_lossy(&buf[..head_end]);
            let len: usize = headers
                .lines()
                .find_map(|l| {
                    let (k, v) = l.split_once(':')?;
                    if k.trim().eq_ignore_ascii_case("content-length") {
                        v.trim().parse().ok()
                    } else {
                        None
                    }
                })
                .unwrap_or(0);
            while buf.len() - head_end < len {
                let n = sock.read(&mut tmp).unwrap();
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
            }
            let body = r#"{"text":"  polished.  "}"#;
            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            sock.write_all(resp.as_bytes()).unwrap();
        });
        let b = SidecarBackend {
            base_url: format!("http://127.0.0.1:{port}"),
            timeout_secs: 10,
        };
        let out = cleanup_with(&b, "SYS", "raw", 4096).unwrap();
        assert_eq!(out, "polished.");
        handle.join().unwrap();
    }
}
