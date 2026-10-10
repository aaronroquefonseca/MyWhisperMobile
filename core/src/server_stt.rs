//! Server-only STT chain client (mobile v1: no on-device inference yet).
//!
//! Wire protocols mirror desktop `transcribe.rs` so desktop fixes port
//! by diff:
//! - Self-hosted `http(s)` → OpenAI-compatible `POST {base}/v1/audio/transcriptions`
//!   (multipart `file` WAV 16 kHz mono + `model` + optional `language`/`prompt`).
//! - Self-hosted `ws(s)://` + streaming → sherpa-online protocol: binary f32LE
//!   chunks, text `"Done"`, JSON partials until `is_final`.
//! - Self-hosted `ws(s)://` without streaming → sherpa-offline protocol:
//!   `[rate u32 LE][frames u32 LE][f32 LE…]`, single JSON reply.
//! - Cloud OpenAI/Groq/Mistral → same OpenAI-compatible endpoint on the
//!   vendor base URL. Deepgram → `POST {base}/v1/listen` (audio/wav).
//!   Gemini → not implemented yet (explicit error).
//!
//! Chain semantics mirror desktop `plans_for`: entries in order, first
//! success wins, per-entry errors collected. `Local` entries are skipped
//! on mobile until on-device inference lands.

use crate::audio::wav_bytes_16k;
use crate::settings::{SttChainEntry, SttEntryKind, SttSettings};
use crate::textnorm::dictionary_prompt;

#[derive(Debug, Clone)]
pub struct Transcript {
    pub text: String,
    pub engine: String,
}

fn openai_base_for(provider: &str, entry_url: &str) -> Option<String> {
    if !entry_url.trim().is_empty() {
        return Some(entry_url.trim().trim_end_matches('/').to_string());
    }
    match provider.to_ascii_lowercase().as_str() {
        "openai" => Some("https://api.openai.com/v1".to_string()),
        "groq" => Some("https://api.groq.com/openai/v1".to_string()),
        "mistral" => Some("https://api.mistral.ai/v1".to_string()),
        _ => None,
    }
}

fn is_ws(url: &str) -> bool {
    let u = url.trim().to_ascii_lowercase();
    u.starts_with("ws://") || u.starts_with("wss://")
}

fn http_transcribe_url(base: &str) -> String {
    if base.contains("/audio/transcriptions") {
        base.to_string()
    } else {
        format!("{}/v1/audio/transcriptions", base.trim_end_matches('/'))
    }
}

/// POST multipart WAV, parse OpenAI-style `{text}` (tolerates `data.text`).
fn post_openai_compat(
    url: &str,
    api_key: &str,
    model: &str,
    language: &str,
    prompt: Option<&str>,
    wav: Vec<u8>,
) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let file = reqwest::blocking::multipart::Part::bytes(wav)
        .file_name("audio.wav")
        .mime_str("audio/wav")
        .map_err(|e| format!("multipart: {e}"))?;
    let mut form = reqwest::blocking::multipart::Form::new()
        .text("model", model.to_string())
        .part("file", file);
    if !language.trim().is_empty() && language != "auto" {
        form = form.text("language", language.to_string());
    }
    if let Some(p) = prompt {
        if !p.is_empty() {
            form = form.text("prompt", p.to_string());
        }
    }
    let mut req = client.post(url).multipart(form);
    if !api_key.trim().is_empty() {
        req = req.bearer_auth(api_key.trim());
    }
    let resp = req.send().map_err(|e| format!("request: {e}"))?;
    let status = resp.status();
    let body = resp.text().map_err(|e| format!("read body: {e}"))?;
    if !status.is_success() {
        return Err(format!(
            "server {status}: {}",
            body.chars().take(300).collect::<String>()
        ));
    }
    let v: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| format!("parse json: {e}"))?;
    v.get("text")
        .and_then(|t| t.as_str())
        .or_else(|| v.pointer("/data/text").and_then(|t| t.as_str()))
        .map(|s| s.to_string())
        .ok_or_else(|| {
            format!(
                "no text in reply: {}",
                body.chars().take(200).collect::<String>()
            )
        })
}

fn post_deepgram(
    base: &str,
    api_key: &str,
    model: &str,
    language: &str,
    wav: Vec<u8>,
) -> Result<String, String> {
    let base = if base.trim().is_empty() {
        "https://api.deepgram.com".to_string()
    } else {
        base.trim().trim_end_matches('/').to_string()
    };
    let model = if model.trim().is_empty() {
        "nova-2"
    } else {
        model.trim()
    };
    let mut url = format!("{base}/v1/listen?model={model}");
    if !language.trim().is_empty() && language != "auto" {
        url.push_str(&format!("&language={}", language.trim()));
    }
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let resp = client
        .post(&url)
        .header("Authorization", format!("Token {}", api_key.trim()))
        .header("Content-Type", "audio/wav")
        .body(wav)
        .send()
        .map_err(|e| format!("request: {e}"))?;
    let status = resp.status();
    let body = resp.text().map_err(|e| format!("read body: {e}"))?;
    if !status.is_success() {
        return Err(format!(
            "server {status}: {}",
            body.chars().take(300).collect::<String>()
        ));
    }
    let v: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| format!("parse json: {e}"))?;
    v.pointer("/results/channels/0/alternatives/0/transcript")
        .and_then(|t| t.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| {
            format!(
                "no transcript in reply: {}",
                body.chars().take(200).collect::<String>()
            )
        })
}

fn reply_text(msg: &str) -> Option<(String, bool)> {
    let v: serde_json::Value = serde_json::from_str(msg).ok()?;
    let text = v
        .get("text")
        .and_then(|t| t.as_str())
        .unwrap_or("")
        .to_string();
    let done = v.get("is_final").and_then(|b| b.as_bool()).unwrap_or(false);
    Some((text, done))
}

/// Sherpa-online (streaming) protocol: binary f32LE chunks, `"Done"`,
/// JSON partials with `text`/`is_final` until final.
fn ws_online(url: &str, pcm_f32: &[f32]) -> Result<String, String> {
    let (mut sock, _) = tungstenite::connect(url).map_err(|e| format!("ws connect: {e}"))?;
    for chunk in pcm_f32.chunks(8000) {
        let mut raw = Vec::with_capacity(chunk.len() * 4);
        for s in chunk {
            raw.extend_from_slice(&s.to_le_bytes());
        }
        sock.send(tungstenite::Message::Binary(raw.into()))
            .map_err(|e| format!("ws send: {e}"))?;
    }
    sock.send(tungstenite::Message::Text("Done".into()))
        .map_err(|e| format!("ws done: {e}"))?;
    let mut last = String::new();
    loop {
        let msg = sock.read().map_err(|e| format!("ws read: {e}"))?;
        match msg {
            tungstenite::Message::Text(t) => {
                if let Some((text, done)) = reply_text(&t) {
                    if !text.is_empty() {
                        last = text;
                    }
                    if done {
                        break;
                    }
                }
            }
            tungstenite::Message::Close(_) => break,
            _ => {}
        }
    }
    let _ = sock.close(None);
    Ok(last)
}

/// Sherpa-offline protocol: `[rate u32 LE][frames u32 LE][f32 LE…]`,
/// single JSON reply with `text`.
fn ws_offline(url: &str, pcm_f32: &[f32]) -> Result<String, String> {
    let (mut sock, _) = tungstenite::connect(url).map_err(|e| format!("ws connect: {e}"))?;
    let mut raw = Vec::with_capacity(8 + pcm_f32.len() * 4);
    raw.extend_from_slice(&16000u32.to_le_bytes());
    raw.extend_from_slice(&(pcm_f32.len() as u32).to_le_bytes());
    for s in pcm_f32 {
        raw.extend_from_slice(&s.to_le_bytes());
    }
    sock.send(tungstenite::Message::Binary(raw.into()))
        .map_err(|e| format!("ws send: {e}"))?;
    let mut last = String::new();
    loop {
        let msg = sock.read().map_err(|e| format!("ws read: {e}"))?;
        match msg {
            tungstenite::Message::Text(t) => {
                if let Some((text, done)) = reply_text(&t) {
                    if !text.is_empty() {
                        last = text;
                    }
                    if done {
                        break;
                    }
                    if !last.is_empty() {
                        break;
                    }
                } else if !t.trim().is_empty() {
                    last = t.to_string();
                    break;
                }
            }
            tungstenite::Message::Close(_) => break,
            _ => {}
        }
    }
    let _ = sock.close(None);
    Ok(last)
}

fn transcribe_entry(
    entry: &SttChainEntry,
    stt: &SttSettings,
    wav: &[u8],
    pcm_f32: &[f32],
    words: &[String],
) -> Result<Transcript, String> {
    let prompt = dictionary_prompt(words, crate::textnorm::WHISPER_PROMPT_CHARS);
    match entry.kind {
        SttEntryKind::Local => {
            Err("on-device inference not supported yet (mobile v1 is server-only)".to_string())
        }
        SttEntryKind::SelfHosted => {
            if entry.url.trim().is_empty() {
                return Err("self-hosted entry has no URL".to_string());
            }
            if is_ws(&entry.url) {
                let text = if entry.streaming {
                    ws_online(entry.url.trim(), pcm_f32)?
                } else {
                    ws_offline(entry.url.trim(), pcm_f32)?
                };
                Ok(Transcript {
                    text,
                    engine: format!("self-hosted {}", entry.url.trim()),
                })
            } else {
                let url = http_transcribe_url(entry.url.trim());
                let model = if entry.model.trim().is_empty() {
                    "whisper-1"
                } else {
                    entry.model.trim()
                };
                let text = post_openai_compat(
                    &url,
                    &entry.api_key,
                    model,
                    &stt.language,
                    prompt.as_deref(),
                    wav.to_vec(),
                )?;
                Ok(Transcript {
                    text,
                    engine: format!("self-hosted {url}"),
                })
            }
        }
        SttEntryKind::Cloud => {
            let provider = entry.provider.trim();
            match provider.to_ascii_lowercase().as_str() {
                "gemini" => Err("Gemini cloud STT not implemented on mobile yet".to_string()),
                "deepgram" => {
                    let text = post_deepgram(
                        &entry.url,
                        &entry.api_key,
                        &entry.model,
                        &stt.language,
                        wav.to_vec(),
                    )?;
                    Ok(Transcript {
                        text,
                        engine: "deepgram".to_string(),
                    })
                }
                "openai" | "groq" | "mistral" => {
                    let base = openai_base_for(provider, &entry.url)
                        .ok_or_else(|| format!("unknown cloud provider '{provider}' and no URL"))?;
                    let url = http_transcribe_url(&base);
                    let model = if entry.model.trim().is_empty() {
                        "whisper-1"
                    } else {
                        entry.model.trim()
                    };
                    let text = post_openai_compat(
                        &url,
                        &entry.api_key,
                        model,
                        &stt.language,
                        prompt.as_deref(),
                        wav.to_vec(),
                    )?;
                    Ok(Transcript {
                        text,
                        engine: format!("{provider} {model}"),
                    })
                }
                other => Err(format!("unknown cloud provider '{other}'")),
            }
        }
    }
}

/// Top-down chain with failover (mirrors desktop `plans_for`).
/// Returns the first success; errors when every enabled entry failed
/// (or no usable entry exists).
pub fn transcribe_chain(
    stt: &SttSettings,
    samples_16k_i16: &[i16],
    words: &[String],
) -> Result<Transcript, String> {
    let pcm_f32: Vec<f32> = samples_16k_i16
        .iter()
        .map(|s| *s as f32 / 32768.0)
        .collect();
    let wav = wav_bytes_16k(samples_16k_i16);
    let mut errors: Vec<String> = Vec::new();
    let mut usable = 0;
    for entry in stt.entries.iter().filter(|e| e.enabled) {
        if entry.kind == SttEntryKind::Local {
            errors.push(format!(
                "{}: on-device inference not supported yet",
                entry.name
            ));
            continue;
        }
        usable += 1;
        match transcribe_entry(entry, stt, &wav, &pcm_f32, words) {
            Ok(t) => return Ok(t),
            Err(e) => errors.push(format!("{}: {e}", entry.name)),
        }
    }
    if usable == 0 {
        return Err("no server STT configured: add a Self-hosted or Cloud entry (Local/on-device is not supported yet)".to_string());
    }
    Err(errors.join("; "))
}
