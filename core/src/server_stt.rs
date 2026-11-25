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
//! here: Android decodes them in Kotlin `LocalStt`, so it runs the chain
//! in two [`ChainPart`]s around the first enabled `Local` entry.

use crate::audio::wav_bytes_16k;
use crate::settings::{SttChainEntry, SttEntryKind, SttSettings};
use crate::textnorm::{GROQ_PROMPT_CHARS, WHISPER_PROMPT_CHARS, dictionary_prompt};

#[derive(Debug, Clone)]
pub struct Transcript {
    pub text: String,
    pub engine: String,
}

/// STT prompt budget, capability-aware (desktop #17 `GROQ_PROMPT_CHARS`
/// vs `WHISPER_PROMPT_CHARS`): Groq rejects prompts over 896 chars, so
/// Groq entries use the 890-char margin; every other STT provider uses
/// the Whisper-family 900-char budget. `provider` is the cloud provider
/// name (`openai`/`groq`/`mistral`/…); unknown/empty → Whisper budget.
pub fn prompt_budget_for(provider: &str) -> usize {
    if provider.trim().eq_ignore_ascii_case("groq") {
        GROQ_PROMPT_CHARS
    } else {
        WHISPER_PROMPT_CHARS
    }
}

// ---------------------------------------------------------------------------
// Provider contracts (desktop #17 port, STT-owned home).
//
// Canonical completed-answer parsing + capability-aware request
// budgets, verbatim semantics from desktop `provider_contract.rs`:
// never deliver thoughts, refusals, or truncated output. Empty
// completed text is `Ok("")` (runner falls back, never goes silent).
// Truncation, refusals, malformed blocks and tool calls are `Err`
// (runner falls back, never dictates partial output).
//
// `engine.rs::llm_cleanup_one` builds and parses every cleanup call
// through these helpers; STT wiring below uses `prompt_budget_for`.
// ---------------------------------------------------------------------------

/// Claude Messages request. `max_tokens` 16384 leaves room for the
/// 1024 thinking budget plus the answer (budget must be < max_tokens,
/// minimum 1024). Adaptive models decide themselves; enabled models
/// need the explicit budget; unknown models omit `thinking` (model
/// default) instead of sending a request the API may reject.
///
/// Adaptive (per 2026 docs): 4-6/4-7/4-8, 5.x, Mythos/Fable families.
/// Enabled-only: 4-5 family + 3.7 Sonnet. Everything else → omitted.
/// `Some(false)` sends `disabled`.
pub fn claude_request(
    model: &str,
    thinking: Option<bool>,
    system: &str,
    user: &str,
) -> serde_json::Value {
    use serde_json::json;
    let mut body = json!({"model":model,"max_tokens":16384,"system":system,"messages":[{"role":"user","content":user}]});
    if let Some(enabled) = thinking {
        if !enabled {
            body["thinking"] = json!({"type":"disabled"});
        } else if [
            "claude-haiku-5",
            "claude-sonnet-5",
            "claude-opus-5",
            "claude-sonnet-4-6",
            "claude-sonnet-4-7",
            "claude-sonnet-4-8",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-mythos",
            "claude-fable",
        ]
        .iter()
        .any(|prefix| model.starts_with(prefix))
        {
            body["thinking"] = json!({"type":"adaptive"});
        } else if [
            "claude-haiku-4-5",
            "claude-sonnet-4-5",
            "claude-opus-4-5",
            "claude-3-7-sonnet",
        ]
        .iter()
        .any(|prefix| model.starts_with(prefix))
        {
            body["thinking"] = json!({"type":"enabled","budget_tokens":1024});
        }
    }
    body
}

/// Ollama `/api/chat` request (stream:false, pinned sampling mirrors
/// OpenWhispr's local bridge). Tri-state: `None` omits `think` (model
/// default), `Some(v)` forces it.
pub fn ollama_request(
    model: &str,
    think: Option<bool>,
    system: &str,
    user: &str,
) -> serde_json::Value {
    use serde_json::{Value, json};
    let mut body = json!({
        "model": model,
        "stream": false,
        "options": {
            "temperature": 0.0,
            "top_p": 0.9,
            "top_k": 40,
            "repeat_penalty": 1.1,
        },
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    });
    if let Some(think) = think {
        body["think"] = Value::Bool(think);
    }
    body
}

/// Gemini `generateContent` request. Tri-state: `None` omits
/// `thinkingConfig` (server default), `Some(true)` = dynamic budget
/// (`-1`), `Some(false)` = off (`0`).
pub fn gemini_request(think: Option<bool>, system: &str, user: &str) -> serde_json::Value {
    use serde_json::json;
    let mut body = json!({
        "contents": [{
            "parts": [
                {"text": system},
                {"text": user},
            ],
        }],
        "generationConfig": {"temperature": 0.0},
    });
    if let Some(think) = think {
        body["generationConfig"]["thinkingConfig"] =
            json!({"thinkingBudget": if think { -1 } else { 0 }});
    }
    body
}

/// Claude completed answer: `stop_reason` end_turn/stop_sequence only;
/// all `text` blocks joined, `thinking`/`redacted_thinking` skipped,
/// any other block (tool_use, …) rejected.
pub fn claude_answer(value: &serde_json::Value) -> Result<String, String> {
    if !matches!(
        value["stop_reason"].as_str(),
        Some("end_turn" | "stop_sequence")
    ) {
        return Err("Claude reply did not complete normally".into());
    }
    let blocks = value["content"]
        .as_array()
        .ok_or("Claude reply has no content blocks")?;
    let mut text = String::new();
    for block in blocks {
        match block["type"].as_str() {
            Some("text") => text.push_str(
                block["text"]
                    .as_str()
                    .ok_or("Claude text block is malformed")?,
            ),
            Some("thinking" | "redacted_thinking") => {}
            _ => return Err("Claude reply contains an unsupported answer block".into()),
        }
    }
    Ok(text)
}

/// Gemini completed answer: first candidate `finishReason` STOP only;
/// non-thought `text` parts joined, `thought:true` skipped, non-text
/// parts rejected.
pub fn gemini_answer(value: &serde_json::Value) -> Result<String, String> {
    let candidate = value
        .pointer("/candidates/0")
        .ok_or("Gemini reply has no candidate")?;
    if candidate["finishReason"].as_str() != Some("STOP") {
        return Err("Gemini reply did not complete normally".into());
    }
    let parts = candidate
        .pointer("/content/parts")
        .and_then(serde_json::Value::as_array)
        .ok_or("Gemini reply has no answer parts")?;
    let mut text = String::new();
    for part in parts {
        if part["thought"].as_bool() == Some(true) {
            continue;
        }
        if let Some(answer) = part["text"].as_str() {
            text.push_str(answer);
        } else {
            return Err("Gemini reply contains a non-text answer".into());
        }
    }
    Ok(text)
}

/// OpenAI-compatible chat answer: first choice `finish_reason` stop,
/// no `refusal`, no `tool_calls`, string `content` required.
pub fn chat_answer(value: &serde_json::Value) -> Result<String, String> {
    let choice = value
        .pointer("/choices/0")
        .ok_or("chat reply has no choice")?;
    if choice["finish_reason"].as_str() != Some("stop")
        || !choice["message"]["refusal"].is_null()
        || !choice["message"]["tool_calls"].is_null()
    {
        return Err("chat reply did not complete normally".into());
    }
    choice["message"]["content"]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "chat reply has no text content".into())
}

/// Ollama `/api/chat` (stream:false) answer: `done:true` plus
/// `done_reason:"stop"`, `message.content` string required,
/// `message.thinking` ignored, `message.tool_calls` rejected.
pub fn ollama_answer(value: &serde_json::Value) -> Result<String, String> {
    if value["done"].as_bool() != Some(true) || value["done_reason"].as_str() != Some("stop") {
        return Err("Ollama reply did not complete normally".into());
    }
    let message = value
        .pointer("/message")
        .ok_or("Ollama reply has no message")?;
    if !message["tool_calls"].is_null() {
        return Err("Ollama reply requested tools, not text".into());
    }
    message["content"]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "Ollama reply has no text content".into())
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
///
/// Desktop #22 port (M): bounded provider IO — 5 s connect deadline
/// (also bounds DNS; DROP: desktop's cancellable resolver subprocess,
/// no blocking-DNS hook exists here), 180 s total deadline (desktop
/// `INFERENCE_TIMEOUT` parity), 1 MiB response cap (header precheck +
/// byte cap, never buffered unbounded). DROP: async
/// cancel-during-transfer; the witness gate in
/// `transcribe_chain_witnessed` (no fallback upload after cancel) plus
/// the engine post-guard (stale results settle) cover delivery.
const CONNECT_TIMEOUT_SECS: u64 = 5;
pub(crate) const INFERENCE_TIMEOUT_SECS: u64 = 180;
const RESPONSE_CAP: usize = 1024 * 1024;

/// Read a bounded UTF-8 body: reject oversized replies from the
/// `Content-Length` header when present, enforce the byte cap while
/// reading, then require UTF-8.
pub(crate) fn capped_text(
    resp: reqwest::blocking::Response,
    description: &str,
) -> Result<String, String> {
    if resp
        .content_length()
        .is_some_and(|len| len > RESPONSE_CAP as u64)
    {
        return Err(format!("{description}: response size limit exceeded"));
    }
    // Chunk loop (desktop #22 parity): `take` bounds the total read to
    // cap+1 and each chunk is checked against the remaining budget, so
    // a missing/lying Content-Length can never buffer unbounded — at
    // most one in-flight chunk sits past the cap.
    use std::io::Read as _;
    let mut limited = resp.take(RESPONSE_CAP as u64 + 1);
    let mut bytes = Vec::new();
    let mut tmp = [0u8; 8192];
    loop {
        let n =
            std::io::Read::read(&mut limited, &mut tmp).map_err(|e| format!("read body: {e}"))?;
        if n == 0 {
            break;
        }
        if n > RESPONSE_CAP.saturating_sub(bytes.len()) {
            return Err(format!("{description}: response size limit exceeded"));
        }
        bytes.extend_from_slice(&tmp[..n]);
    }
    String::from_utf8(bytes).map_err(|_| format!("{description}: response is not UTF-8"))
}

/// OpenAI-style `{text}` (tolerates `data.text`). whisper.cpp's server
/// joins segments with "\n" at timing cuts, not speaker intent; fold
/// all whitespace runs into single spaces (desktop #33).
fn transcription_text(v: &serde_json::Value) -> Option<String> {
    v.get("text")
        .and_then(|t| t.as_str())
        .or_else(|| v.pointer("/data/text").and_then(|t| t.as_str()))
        .map(|s| s.split_whitespace().collect::<Vec<_>>().join(" "))
}

fn post_openai_compat(
    url: &str,
    api_key: &str,
    model: &str,
    language: &str,
    prompt: Option<&str>,
    wav: Vec<u8>,
) -> Result<String, String> {
    let client = crate::tls::blocking_client()?
        .connect_timeout(std::time::Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(std::time::Duration::from_secs(INFERENCE_TIMEOUT_SECS))
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
    if let Some(p) = prompt
        && !p.is_empty()
    {
        form = form.text("prompt", p.to_string());
    }
    let mut req = client.post(url).multipart(form);
    if !api_key.trim().is_empty() {
        req = req.bearer_auth(api_key.trim());
    }
    let resp = req.send().map_err(|e| format!("request: {e:?}"))?;
    let status = resp.status();
    let body = capped_text(resp, "transcription")?;
    if !status.is_success() {
        return Err(format!(
            "server {status}: {}",
            body.chars().take(300).collect::<String>()
        ));
    }
    let v: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| format!("parse json: {e}"))?;
    transcription_text(&v).ok_or_else(|| {
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
    let client = crate::tls::blocking_client()?
        .connect_timeout(std::time::Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(std::time::Duration::from_secs(INFERENCE_TIMEOUT_SECS))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let resp = client
        .post(&url)
        .header("Authorization", format!("Token {}", api_key.trim()))
        .header("Content-Type", "audio/wav")
        .body(wav)
        .send()
        .map_err(|e| format!("request: {e:?}"))?;
    let status = resp.status();
    let body = capped_text(resp, "deepgram transcription")?;
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
    // Capability-aware STT prompt budget: Groq cloud entries use the
    // 890-char margin, everything else the Whisper-family 900-char
    // budget. `dictionary_prompt` itself is UTF-8-safe (backs up to a
    // char boundary, then cuts at the last comma).
    let budget = match entry.kind {
        SttEntryKind::Cloud => prompt_budget_for(&entry.provider),
        _ => WHISPER_PROMPT_CHARS,
    };
    let prompt = dictionary_prompt(words, budget);
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

/// Which enabled entries one chain call walks, split at the first
/// enabled `Local` entry so the platform can decode on-device in its
/// Settings position (Self-hosted → Local → Cloud never reaches Cloud
/// before Local).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChainPart {
    /// Every enabled entry (iOS: no on-device decode).
    All,
    /// Entries above the first enabled `Local` (all of them when there is none).
    BeforeLocal,
    /// Entries below the first enabled `Local` (none when there is none).
    AfterLocal,
}

impl ChainPart {
    fn includes(self, index: usize, local_at: Option<usize>) -> bool {
        match (self, local_at) {
            (ChainPart::All, _) | (ChainPart::BeforeLocal, None) => true,
            (ChainPart::AfterLocal, None) => false,
            (ChainPart::BeforeLocal, Some(at)) => index < at,
            (ChainPart::AfterLocal, Some(at)) => index > at,
        }
    }
}

/// Enabled entries one [`ChainPart`] walks, in Settings order.
pub fn part_entries(stt: &SttSettings, part: ChainPart) -> impl Iterator<Item = &SttChainEntry> {
    let local_at = stt
        .entries
        .iter()
        .position(|e| e.enabled && e.kind == SttEntryKind::Local);
    stt.entries
        .iter()
        .enumerate()
        .filter(move |(i, e)| e.enabled && part.includes(*i, local_at))
        .map(|(_, e)| e)
}

/// Top-down chain with failover (mirrors desktop `plans_for`).
/// Returns the first success; errors when every enabled entry failed
/// (or no usable entry exists).
pub fn transcribe_chain(
    stt: &SttSettings,
    samples_16k_i16: &[i16],
    words: &[String],
) -> Result<Transcript, String> {
    transcribe_chain_witnessed(
        stt,
        ChainPart::All,
        samples_16k_i16,
        words,
        server_generation(),
    )
}

/// Witnessed chain (desktop #22 session-ownership port): `witness` is
/// the control generation sampled at utterance admission — the engine
/// holds it across the blocking transfer. It is rechecked before
/// EVERY entry, including the first: a cancel/reset/switch that lands
/// after admission aborts before any further upload, so cancelled
/// recordings NEVER reach a fallback entry. Stale delivery without a
/// witness (direct callers) still relies on the engine post-guard.
pub fn transcribe_chain_witnessed(
    stt: &SttSettings,
    part: ChainPart,
    samples_16k_i16: &[i16],
    words: &[String],
    witness: u64,
) -> Result<Transcript, String> {
    let pcm_f32: Vec<f32> = samples_16k_i16
        .iter()
        .map(|s| *s as f32 / 32768.0)
        .collect();
    let wav = wav_bytes_16k(samples_16k_i16);
    let mut errors: Vec<String> = Vec::new();
    let mut usable = 0;
    for entry in part_entries(stt, part) {
        if entry.kind == SttEntryKind::Local {
            errors.push(format!(
                "{}: on-device inference not supported yet",
                entry.name
            ));
            continue;
        }
        // Session gate: a control transition since admission revokes
        // this chain — abort before the next upload, never fall back.
        if server_generation() != witness {
            return Err(
                "cancelled (chain superseded mid-utterance; fallback uploads aborted)".to_string(),
            );
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

// ---------------------------------------------------------------------------
// Server status + provider-switch reconcile (desktop #11 port).
//
// Desktop runs local whisper/sherpa child processes and reconciles
// them from a service watcher; mobile is server-only (remote
// request/response per utterance, no owned processes), so the port
// tracks the *chain* instead of processes:
//
// - `server_status` reports the single primary remote entry (never one
//   row per engine: the desktop bug was whisper + sherpa-offline +
//   sherpa-online all "running" on one custom port; the mobile analog
//   is one enabled chain entry looking live while fallbacks do too).
// - `ReconcileAuthority { witness, snapshot_fp }` is built per
//   validated tick and carried into every apply branch (never
//   re-sampled): a queued tick whose witness predates a control
//   transition, or whose snapshot no longer matches current settings,
//   defers instead of killing/launching on stale intent.
// - Stale ticks defer without touching markers, suppression, or the
//   active chain, so the next poll retries against truth. Only success
//   advances the `(primary, auto)` pair.
// - Explicit Stop (`record_stop`, wired to engine cancel) bumps the
//   control generation (revoking queued witnesses, including on an
//   empty chain) and suppresses auto-restart of that fingerprint until
//   a deliberate settings change expires it. Expiry refusal preserves
//   bookkeeping (`last_auto` untouched).
// - A primary move bumps the generation, killing the previous chain:
//   stale in-flight work observes the newer generation and settles
//   instead of relaunching.
// ---------------------------------------------------------------------------

use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

/// Control generation: bumped on every applied switch/stop so queued
/// work from an older configuration aborts instead of resurrecting
/// it.
static SERVER_GENERATION: AtomicU64 = AtomicU64::new(0);

/// Currently reconciled chain fingerprint (empty = cold/none).
static ACTIVE_FP: Mutex<String> = Mutex::new(String::new());

/// Admission: one holding across each check+mutate section below, so a
/// concurrent reconcile/expire cannot slip between the decision and
/// the mutation (desktop `SERVER_START_LOCK` analog; mobile sections
/// are memory-only, no blocking operations under it).
static RECONCILE_LOCK: Mutex<()> = Mutex::new(());

/// Explicit-Stop authority: post-bump generation + stopped fingerprint
/// (desktop `LAST_STOP_GEN` + `STOPPED_FP` analog). A queued autostart
/// observes the newer Stop and settles cold until a deliberate
/// settings change expires it.
static LAST_STOP_GEN: AtomicU64 = AtomicU64::new(0);
static STOPPED_FP: Mutex<String> = Mutex::new(String::new());

/// Generation at which autostart-disabled was last observed (desktop
/// `LAST_DISABLE_OBSERVED_GEN` analog). Orders a Stop against the
/// toggle an expiry would serve: only a Stop strictly newer than the
/// last observed disable revokes it. Must be captured alongside the
/// observation, never sampled when a delayed update runs.
static LAST_DISABLE_OBSERVED_GEN: AtomicU64 = AtomicU64::new(0);

/// Admitted utterances (recording or transcribing). Reconciliation
/// defers while non-zero: never kill/replace the chain mid-utterance;
/// the pending switch applies on the next idle tick.
static ACTIVE_UTTERANCES: AtomicUsize = AtomicUsize::new(0);

/// Current control generation (witness source for ticks).
pub fn server_generation() -> u64 {
    SERVER_GENERATION.load(Ordering::Acquire)
}

fn bump_generation() {
    SERVER_GENERATION.fetch_add(1, Ordering::AcqRel);
}

/// Lock-free cancel signal: revokes every in-flight witness (STT
/// chain, cleanup, history commit) without touching Stop suppression.
pub fn revoke_in_flight() {
    bump_generation();
}

/// True while an utterance is recording or transcribing.
pub fn inference_active() -> bool {
    ACTIVE_UTTERANCES.load(Ordering::Acquire) > 0
}

pub(crate) fn active_inc() {
    ACTIVE_UTTERANCES.fetch_add(1, Ordering::AcqRel);
}

pub(crate) fn active_dec() {
    ACTIVE_UTTERANCES.fetch_sub(1, Ordering::AcqRel);
}

fn active_fp() -> String {
    ACTIVE_FP.lock().map(|g| g.clone()).unwrap_or_default()
}

fn stopped_fp() -> String {
    STOPPED_FP.lock().map(|g| g.clone()).unwrap_or_default()
}

/// First enabled remote entry: the chain primary (Local skipped,
/// failover order = list order).
fn primary_remote(stt: &SttSettings) -> Option<&SttChainEntry> {
    stt.entries
        .iter()
        .find(|e| e.enabled && e.kind != SttEntryKind::Local)
}

fn entry_identity(e: &SttChainEntry) -> String {
    let kind = match e.kind {
        SttEntryKind::Local => "local",
        SttEntryKind::SelfHosted => "self-hosted",
        SttEntryKind::Cloud => "cloud",
    };
    format!(
        "{kind}|{}|{}|{}",
        e.provider.trim().to_ascii_lowercase(),
        e.url.trim().trim_end_matches('/'),
        e.model.trim(),
    )
}

/// Primary chain identity without the autostart flag: lets the tick
/// distinguish "config moved" (replace/stop) from "only the autostart
/// toggle moved" (warm on enable, keep on disable). Language affects
/// every request, so it travels with the identity; unrelated edits
/// (keep-warm, dictionary, unused keys) never bounce the chain.
pub fn stt_primary_id(stt: &SttSettings) -> String {
    let local_on = stt
        .entries
        .iter()
        .any(|e| e.enabled && e.kind == SttEntryKind::Local);
    let primary = primary_remote(stt)
        .map(entry_identity)
        .unwrap_or_else(|| "none".to_string());
    format!(
        "local_on={local_on}|primary={primary}|lang={}",
        stt.language.trim()
    )
}

/// Fingerprint of the running-relevant configuration: primary identity
/// + autostart. The tick reconciles only when this moves.
pub fn stt_fingerprint(stt: &SttSettings) -> String {
    format!("{}|auto={}", stt_primary_id(stt), stt.auto_start_server)
}

/// Validated tick observation carried into apply calls so queued work
/// can never authorize stale settings with a fresh generation, nor
/// kill or clear on behalf of a superseded snapshot.
#[derive(Clone, Debug)]
pub struct ReconcileAuthority {
    /// Control generation validated at observation time (never
    /// re-sampled downstream: a newer transition revokes this).
    pub witness: u64,
    /// Fingerprint of the observed settings snapshot.
    pub snapshot_fp: String,
}

/// Tick result. Deferred/failed ticks leave the `(primary, auto)`
/// markers untouched so the next poll retries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TickOutcome {
    Unchanged,
    Applied,
    Deferred,
}

/// Record an observed autostart disable. `gen_at_observe` must be the
/// generation sampled with the snapshot being recorded.
fn note_disable_observed(gen_at_observe: u64) {
    LAST_DISABLE_OBSERVED_GEN.store(gen_at_observe, Ordering::Release);
}

/// Explicit user Stop: bump the generation (revoking queued witnesses,
/// even on an empty chain) and suppress auto-restart of `fp` until a
/// deliberate settings change expires it. Empty fingerprints bump but
/// record nothing (fingerprint-gated, like desktop).
pub fn record_stop(fp: &str) {
    bump_generation();
    if fp.is_empty() {
        return;
    }
    LAST_STOP_GEN.store(server_generation(), Ordering::Release);
    *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = fp.to_string();
}

/// Expire Stop suppression for a deliberate transition (autostart
/// re-enable / applied primary move). Validated end to end: the
/// witness must still match, the snapshot must still be current, and
/// no Stop strictly newer than the last observed disable may target
/// the current configuration. Anything stale returns false without
/// touching suppression, so a newer Stop survives. Returns true when
/// the suppression was expired (or nothing suppressed it).
pub fn expire_stop_suppression_at(current: &SttSettings, auth: &ReconcileAuthority) -> bool {
    let _admission = match RECONCILE_LOCK.lock() {
        Ok(g) => g,
        Err(_) => return false,
    };
    if server_generation() != auth.witness {
        return false;
    }
    let fp_now = stt_fingerprint(current);
    if fp_now.is_empty() || fp_now != auth.snapshot_fp {
        return false;
    }
    if LAST_STOP_GEN.load(Ordering::Acquire) > LAST_DISABLE_OBSERVED_GEN.load(Ordering::Acquire)
        && stopped_fp() == fp_now
    {
        return false;
    }
    *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    true
}

/// Single-listener status: at most one row — the primary remote entry
/// with its liveness (active chain + not Stop-suppressed). Fallbacks
/// are never reported as running. Empty when no remote is enabled.
pub fn server_status(stt: &SttSettings) -> Vec<(String, bool)> {
    let Some(entry) = primary_remote(stt) else {
        return Vec::new();
    };
    let fp = stt_fingerprint(stt);
    let live = !fp.is_empty() && active_fp() == fp && stopped_fp() != fp;
    let label = if entry.name.trim().is_empty() {
        entry_identity(entry)
    } else {
        entry.name.trim().to_string()
    };
    vec![(label, live)]
}

/// Reconcile the active chain with the settings: make the running
/// chain match, killing the previous one on a switch. Returns
/// `Ok(true)` when deferred (active utterance, stale authority, or
/// superseded snapshot — no mutation), `Ok(false)` when applied
/// (including settling cold under Stop suppression).
///
/// `authority` carries the tick's validated witness (`Some`) into
/// every decision, verified under admission before mutating. `None`
/// derives fresh (synchronous deliberate callers with no window).
pub fn reconcile_servers(
    stt: &SttSettings,
    authority: Option<ReconcileAuthority>,
) -> Result<bool, String> {
    // Attested identity when carried, locally derived otherwise; every
    // decision below keys off this one value.
    let fp = match &authority {
        Some(auth) => auth.snapshot_fp.clone(),
        None => stt_fingerprint(stt),
    };
    let _admission = RECONCILE_LOCK.lock().map_err(|e| e.to_string())?;
    if inference_active() {
        return Ok(true);
    }
    // Carried witness revalidated under admission: queued work whose
    // witness predates a control transition defers instead of acting
    // on stale intent.
    if let Some(ref auth) = authority
        && server_generation() != auth.witness
    {
        return Ok(true);
    }
    // Acting on a superseded snapshot is never correct here,
    // regardless of caller: killing the chain for settings that are no
    // longer current can only drop the right one. Deliberate callers
    // pass current settings, so this always matches for them.
    if stt_fingerprint(stt) != fp {
        return Ok(true);
    }
    // A Stop targeting this fingerprint suppresses restart: settle
    // cold (applied, no launch, no resurrection).
    if !fp.is_empty() && stopped_fp() == fp {
        return Ok(false);
    }
    if primary_remote(stt).is_none() {
        // Leaving remote operation (or nothing usable): kill the
        // previous chain so it does not stay warm, and expire any
        // older Stop (this deliberate config takes effect; freshness
        // proven above, still holding admission).
        if !active_fp().is_empty() {
            bump_generation();
            *ACTIVE_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
        }
        if !fp.is_empty() {
            *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
        }
        return Ok(false);
    }
    if !stt.auto_start_server {
        // Lazy: moves route to `reconcile_lazy_switch` (stop-old, stay
        // cold); steady autostart-path ticks are no-ops.
        return Ok(false);
    }
    if active_fp() == fp {
        return Ok(false);
    }
    // Switch: bump first (kills the previous chain; stale in-flight
    // work observes the newer generation and settles), then publish.
    // The newer deliberate config expires any older Stop.
    bump_generation();
    *ACTIVE_FP.lock().unwrap_or_else(|p| p.into_inner()) = fp.clone();
    if !fp.is_empty() {
        *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    }
    Ok(false)
}

/// Lazy-switch helper (autostart off + fingerprint moved): kill the
/// old chain and stay cold until the next utterance. Same deferral
/// contract as `reconcile_servers`. Applying the observed move expires
/// any older Stop (still holding admission, so no Stop slips between
/// the kill and the expiry).
pub fn reconcile_lazy_switch(
    stt: &SttSettings,
    authority: Option<ReconcileAuthority>,
) -> Result<bool, String> {
    let fp = match &authority {
        Some(auth) => auth.snapshot_fp.clone(),
        None => stt_fingerprint(stt),
    };
    let _admission = RECONCILE_LOCK.lock().map_err(|e| e.to_string())?;
    if inference_active() {
        return Ok(true);
    }
    if let Some(ref auth) = authority
        && (server_generation() != auth.witness || stt_fingerprint(stt) != auth.snapshot_fp)
    {
        return Ok(true);
    }
    if stt_fingerprint(stt) != fp {
        return Ok(true);
    }
    if !active_fp().is_empty() {
        bump_generation();
        *ACTIVE_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    }
    *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    Ok(false)
}

/// One watcher poll over already-loaded settings: compare against the
/// last applied `(primary, auto)` pair and reconcile once on change.
/// Deferred/failed ticks leave the pair untouched so the next poll
/// retries. Disabled-state observations refresh the disable marker
/// before the skip check (even unchanged ones), so a refusal below
/// never strands later toggles.
///
/// `authority` must be the witness + fingerprint validated at
/// observation time (`Some`, watcher path), or `None` for deliberate
/// synchronous callers (fresh derivation, no window).
pub fn stt_watcher_tick(
    current: &SttSettings,
    last_primary: &mut String,
    last_auto: &mut bool,
    authority: Option<ReconcileAuthority>,
) -> TickOutcome {
    let curr_primary = stt_primary_id(current);
    let curr_auto = current.auto_start_server;
    let witness = match &authority {
        Some(auth) => auth.witness,
        None => server_generation(),
    };
    // Refresh the disable marker on every validated disabled tick so
    // genuine later toggles still warm normally after a refusal.
    if !curr_auto {
        note_disable_observed(witness);
    }
    let auth = match authority {
        Some(a) => a,
        None => ReconcileAuthority {
            witness,
            snapshot_fp: stt_fingerprint(current),
        },
    };
    if curr_primary == *last_primary && curr_auto == *last_auto {
        return TickOutcome::Unchanged;
    }
    if inference_active() {
        return TickOutcome::Deferred;
    }
    if curr_primary != *last_primary {
        // Configuration moved. Transient failures retry on the next
        // tick; only success advances the pair.
        let applied = if curr_auto {
            match reconcile_servers(current, Some(auth)) {
                Ok(deferred) => !deferred,
                Err(_) => false,
            }
        } else {
            match reconcile_lazy_switch(current, Some(auth)) {
                Ok(deferred) => !deferred,
                Err(_) => false,
            }
        };
        if applied {
            *last_primary = curr_primary;
            *last_auto = curr_auto;
            TickOutcome::Applied
        } else {
            TickOutcome::Deferred
        }
    } else {
        // Only the autostart toggle moved.
        if curr_auto {
            // Re-enabling is itself deliberate: expire any Stop
            // suppression of this fingerprint before warming, unless a
            // newer Stop revoked this observation — then defer without
            // touching bookkeeping (the toggle was never validly
            // applied; later observations retry).
            if !expire_stop_suppression_at(current, &auth) {
                return TickOutcome::Deferred;
            }
            match reconcile_servers(current, Some(auth)) {
                Ok(deferred) => {
                    if !deferred {
                        *last_auto = curr_auto;
                        TickOutcome::Applied
                    } else {
                        TickOutcome::Deferred
                    }
                }
                Err(_) => TickOutcome::Deferred,
            }
        } else {
            // Disable keeps the running chain (no bounce). Marker was
            // already refreshed above for every disabled observation.
            *last_auto = curr_auto;
            TickOutcome::Applied
        }
    }
}

#[cfg(test)]
pub(crate) fn reset_status_state() {
    SERVER_GENERATION.store(0, Ordering::Release);
    LAST_STOP_GEN.store(0, Ordering::Release);
    LAST_DISABLE_OBSERVED_GEN.store(0, Ordering::Release);
    ACTIVE_UTTERANCES.store(0, Ordering::Release);
    *ACTIVE_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
    *STOPPED_FP.lock().unwrap_or_else(|p| p.into_inner()) = String::new();
}

#[cfg(test)]
static STATUS_TEST_LOCK: Mutex<()> = Mutex::new(());

#[cfg(test)]
pub(crate) fn status_test_lock() -> std::sync::MutexGuard<'static, ()> {
    STATUS_TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner())
}

/// Test-only barrier parked between the poll's control-generation
/// witness and its settings reload (desktop `TICK_SNAPSHOT_PAUSE`
/// analog). Lets a test land a Stop/switch strictly inside the
/// snapshot window and prove the torn tick discards instead of
/// reconciling a stale pairing. Production builds contain no hook.
#[cfg(test)]
static POLL_SNAPSHOT_PAUSE: Mutex<
    Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>,
> = Mutex::new(None);

/// Install the poll snapshot-window barrier (single-shot).
#[cfg(test)]
pub(crate) fn set_poll_snapshot_pause(
    tx: std::sync::mpsc::Sender<()>,
    rx: std::sync::mpsc::Receiver<()>,
) {
    *POLL_SNAPSHOT_PAUSE.lock().unwrap() = Some((tx, rx));
}

/// Clear a parked (never reached) barrier so later tests never block.
#[cfg(test)]
pub(crate) fn clear_poll_snapshot_pause() {
    POLL_SNAPSHOT_PAUSE.lock().unwrap().take();
}

#[cfg(test)]
pub(crate) fn poll_snapshot_pause_point() {
    if let Some((entered, proceed)) = POLL_SNAPSHOT_PAUSE.lock().ok().and_then(|mut g| g.take()) {
        let _ = entered.send(());
        let _ = proceed.recv_timeout(std::time::Duration::from_secs(10));
    }
}

/// Test-only barrier parked after a successful chain transcribe but
/// before the staleness guard (desktop `RECONCILE_ENSURE_PAUSE`
/// analog). Lets a test land a switch/Stop inside the transcribe
/// window and prove the stale result settles instead of delivering.
/// Production builds contain no hook.
#[cfg(test)]
static FINISH_GUARD_PAUSE: Mutex<
    Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>,
> = Mutex::new(None);

/// Install the finish-guard barrier (single-shot).
#[cfg(test)]
pub(crate) fn set_finish_guard_pause(
    tx: std::sync::mpsc::Sender<()>,
    rx: std::sync::mpsc::Receiver<()>,
) {
    *FINISH_GUARD_PAUSE.lock().unwrap() = Some((tx, rx));
}

/// Clear a parked (never reached) barrier so later tests never block.
#[cfg(test)]
pub(crate) fn clear_finish_guard_pause() {
    FINISH_GUARD_PAUSE.lock().unwrap().take();
}

#[cfg(test)]
pub(crate) fn finish_guard_pause_point() {
    if let Some((entered, proceed)) = FINISH_GUARD_PAUSE.lock().ok().and_then(|mut g| g.take()) {
        let _ = entered.send(());
        let _ = proceed.recv_timeout(std::time::Duration::from_secs(10));
    }
}

/// Test-only barrier parked after `post_process` (LLM cleanup) but
/// before the pre-history witness recheck. Lets a test land a
/// Stop/switch strictly inside the blocking-cleanup window and prove
/// the cancelled utterance settles instead of storing a phantom row.
/// Production builds contain no hook.
#[cfg(test)]
static FINISH_CLEANUP_PAUSE: Mutex<
    Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>,
> = Mutex::new(None);

/// Install the finish-cleanup barrier (single-shot).
#[cfg(test)]
pub(crate) fn set_finish_cleanup_pause(
    tx: std::sync::mpsc::Sender<()>,
    rx: std::sync::mpsc::Receiver<()>,
) {
    *FINISH_CLEANUP_PAUSE.lock().unwrap() = Some((tx, rx));
}

/// Clear a parked (never reached) barrier so later tests never block.
#[cfg(test)]
pub(crate) fn clear_finish_cleanup_pause() {
    FINISH_CLEANUP_PAUSE.lock().unwrap().take();
}

#[cfg(test)]
pub(crate) fn finish_cleanup_pause_point() {
    if let Some((entered, proceed)) = FINISH_CLEANUP_PAUSE.lock().ok().and_then(|mut g| g.take()) {
        let _ = entered.send(());
        let _ = proceed.recv_timeout(std::time::Duration::from_secs(10));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn stt_prompt_budget_is_capability_aware() {
        // Groq rejects prompts over 896 chars → 890 margin; everyone
        // else uses the Whisper-family 900 budget (desktop #17).
        assert_eq!(prompt_budget_for("groq"), GROQ_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("Groq"), GROQ_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("  GROQ  "), GROQ_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("openai"), WHISPER_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("mistral"), WHISPER_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("deepgram"), WHISPER_PROMPT_CHARS);
        assert_eq!(prompt_budget_for(""), WHISPER_PROMPT_CHARS);
        assert_eq!(prompt_budget_for("unknown"), WHISPER_PROMPT_CHARS);
    }

    #[test]
    fn claude_budget_and_adaptive_modes_match_model_capabilities() {
        for model in [
            "claude-haiku-4-5",
            "claude-sonnet-4-5-20250929",
            "claude-opus-4-5",
            "claude-3-7-sonnet-20250219",
        ] {
            let v = claude_request(model, Some(true), "system", "user");
            assert_eq!(v["thinking"]["type"], "enabled");
            assert!(v["thinking"]["budget_tokens"].as_u64().unwrap() >= 1024);
            assert!(v["thinking"]["budget_tokens"].as_u64() < v["max_tokens"].as_u64());
        }
        for model in [
            "claude-haiku-5-5",
            "claude-sonnet-5-20260101",
            "claude-opus-5",
            "claude-sonnet-4-6",
            "claude-sonnet-4-7",
            "claude-sonnet-4-8",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-mythos-preview",
            "claude-mythos-5",
            "claude-fable-5",
        ] {
            assert_eq!(
                claude_request(model, Some(true), "s", "u")["thinking"],
                json!({"type":"adaptive"}),
                "model {model}"
            );
            assert_eq!(
                claude_request(model, Some(false), "s", "u")["thinking"]["type"],
                "disabled",
                "model {model}"
            );
        }
        // Unknown capabilities must not guess a mode: omit `thinking`
        // so the model default applies.
        for model in [
            "future-model",
            "claude-sonnet-4-4",
            "claude-opus-4-4",
            "claude-sonnet-4",
            "claude-opus-4",
            "gpt-5",
            "",
        ] {
            assert!(
                claude_request(model, Some(true), "s", "u")
                    .get("thinking")
                    .is_none(),
                "model {model}"
            );
        }
        assert!(
            claude_request("future-model", None, "s", "u")
                .get("thinking")
                .is_none()
        );
        assert!(
            claude_request("claude-sonnet-4-5", None, "s", "u")
                .get("thinking")
                .is_none()
        );
    }

    #[test]
    fn ollama_and_gemini_tristate_omit_or_force() {
        for think in [None, Some(true), Some(false)] {
            let v = ollama_request("qwen3", think, "sys", "user");
            match think {
                None => assert!(v.get("think").is_none()),
                Some(b) => assert_eq!(v["think"], json!(b)),
            }
            assert_eq!(v["stream"], json!(false));
            let g = gemini_request(think, "sys", "user");
            match think {
                None => assert!(g["generationConfig"].get("thinkingConfig").is_none()),
                Some(true) => assert_eq!(
                    g["generationConfig"]["thinkingConfig"],
                    json!({"thinkingBudget": -1})
                ),
                Some(false) => assert_eq!(
                    g["generationConfig"]["thinkingConfig"],
                    json!({"thinkingBudget": 0})
                ),
            }
        }
    }

    #[test]
    fn thoughts_never_become_answers_and_partial_replies_are_rejected() {
        let mut claude = json!({"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"Hello "},{"type":"text","text":"世界"}]});
        assert_eq!(claude_answer(&claude).unwrap(), "Hello 世界");
        claude["stop_reason"] = json!("max_tokens");
        assert!(claude_answer(&claude).is_err());
        let mut gemini = json!({"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"secret"},{"text":"Answer"}]}}]});
        assert_eq!(gemini_answer(&gemini).unwrap(), "Answer");
        gemini["candidates"][0]["finishReason"] = json!("MAX_TOKENS");
        assert!(gemini_answer(&gemini).is_err());
        for reason in ["length", "content_filter", "tool_calls", ""] {
            assert!(
                chat_answer(
                    &json!({"choices":[{"finish_reason":reason,"message":{"content":"partial"}}]})
                )
                .is_err()
            );
        }
        assert!(
            ollama_answer(
                &json!({"done":false,"done_reason":"stop","message":{"content":"partial"}})
            )
            .is_err()
        );
        // Missing done_reason is also incomplete.
        assert!(ollama_answer(&json!({"done":true,"message":{"content":"x"}})).is_err());
    }

    #[test]
    fn refusals_tool_calls_and_malformed_blocks_are_rejected() {
        assert!(
            claude_answer(
                &json!({"stop_reason":"end_turn","content":[{"type":"tool_use","id":"1"}]})
            )
            .is_err()
        );
        assert!(
            claude_answer(
                &json!({"stop_reason":"end_turn","content":[{"type":"server_tool_use"}]})
            )
            .is_err()
        );
        assert!(
            claude_answer(&json!({"stop_reason":"end_turn","content":[{"type":"text"}]})).is_err()
        );
        assert!(
            claude_answer(&json!({"stop_reason":"refusal","content":[{"type":"text","text":"x"}]}))
                .is_err()
        );
        assert_eq!(
            claude_answer(
                &json!({"stop_reason":"end_turn","content":[{"type":"redacted_thinking","data":"x"},{"type":"text","text":"ok"}]})
            )
            .unwrap(),
            "ok"
        );
        assert!(
            gemini_answer(
                &json!({"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"f"}}]}}]})
            )
            .is_err()
        );
        assert!(gemini_answer(&json!({"candidates":[]})).is_err());
        // Chat: refusal string, null content, or tool_calls rejected.
        assert!(chat_answer(
            &json!({"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":null}}]})
        )
        .is_err());
        assert!(chat_answer(
            &json!({"choices":[{"finish_reason":"stop","message":{"content":"x","refusal":"no"}}]})
        )
        .is_err());
        assert!(chat_answer(
            &json!({"choices":[{"finish_reason":"stop","message":{"content":"x","tool_calls":[]}}]})
        )
        .is_err());
        assert!(
            chat_answer(
                &json!({"choices":[{"finish_reason":"tool_calls","message":{"content":"x"}}]})
            )
            .is_err()
        );
        // Ollama: tool_calls rejected, thinking ignored, content required.
        assert!(
            ollama_answer(
                &json!({"done":true,"done_reason":"stop","message":{"content":"x","tool_calls":[]}})
            )
            .is_err()
        );
        assert_eq!(
            ollama_answer(
                &json!({"done":true,"done_reason":"stop","message":{"content":"hi 🎙️","thinking":"secret"}})
            )
            .unwrap(),
            "hi 🎙️"
        );
        assert!(
            ollama_answer(&json!({"done":true,"done_reason":"stop","message":{"content":null}}))
                .is_err()
        );
        assert!(ollama_answer(&json!({"done":true,"done_reason":"stop"})).is_err());
    }

    #[test]
    fn long_unicode_answers_concatenate_without_truncation() {
        let chunk = "世界🎙️café ";
        let long: String = chunk.repeat(2000);
        let mut mid = long.len() / 2;
        while !long.is_char_boundary(mid) {
            mid -= 1;
        }
        let claude = json!({"stop_reason":"end_turn","content":[{"type":"text","text":&long[..mid]},{"type":"text","text":&long[mid..]}]});
        assert_eq!(claude_answer(&claude).unwrap(), long);
        let mut gmid = 100;
        while !long.is_char_boundary(gmid) {
            gmid -= 1;
        }
        let gemini = json!({"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":&long[..gmid]},{"thought":true,"text":"secret"},{"text":&long[gmid..]}]}}]});
        assert_eq!(gemini_answer(&gemini).unwrap(), long);
        let expected = chunk.repeat(2000);
        assert_eq!(
            chat_answer(
                &json!({"choices":[{"finish_reason":"stop","message":{"content":expected}}]})
            )
            .unwrap(),
            expected
        );
        let ollama = json!({"done":true,"done_reason":"stop","message":{"content":expected}});
        // Re-build to avoid move issues in the assertions above.
        assert_eq!(ollama_answer(&ollama).unwrap(), chunk.repeat(2000));
    }

    #[test]
    fn completed_policy_empty_answers_are_successes() {
        assert_eq!(
            claude_answer(&json!({"stop_reason":"end_turn","content":[{"type":"text","text":""}]}))
                .unwrap(),
            ""
        );
        assert_eq!(
            gemini_answer(
                &json!({"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":""}]}}]})
            )
            .unwrap(),
            ""
        );
        assert_eq!(
            chat_answer(&json!({"choices":[{"finish_reason":"stop","message":{"content":""}}]}))
                .unwrap(),
            ""
        );
        assert_eq!(
            ollama_answer(&json!({"done":true,"done_reason":"stop","message":{"content":""}}))
                .unwrap(),
            ""
        );
    }

    // -- desktop #11 port: status + provider-switch reconcile ---------

    fn remote_entry(id: &str, name: &str, provider: &str, url: &str, model: &str) -> SttChainEntry {
        SttChainEntry {
            id: id.to_string(),
            name: name.to_string(),
            kind: SttEntryKind::Cloud,
            enabled: true,
            provider: provider.to_string(),
            url: url.to_string(),
            model: model.to_string(),
            api_key: String::new(),
            streaming: true,
        }
    }

    fn local_entry() -> SttChainEntry {
        SttChainEntry {
            id: "stt-local".to_string(),
            name: "Local".to_string(),
            kind: SttEntryKind::Local,
            enabled: true,
            provider: String::new(),
            url: String::new(),
            model: String::new(),
            api_key: String::new(),
            streaming: true,
        }
    }

    fn stt_auto(entries: Vec<SttChainEntry>, auto: bool) -> SttSettings {
        SttSettings {
            entries,
            auto_start_server: auto,
            ..SttSettings::default()
        }
    }

    fn tick_applied(current: &SttSettings, last: &mut (String, bool)) -> TickOutcome {
        stt_watcher_tick(current, &mut last.0, &mut last.1, None)
    }

    #[test]
    fn status_reports_single_primary_not_every_entry() {
        let _guard = status_test_lock();
        reset_status_state();
        // Three enabled remotes + Local: status must be one row (the
        // primary), never one per engine — the desktop #11 shape where
        // whisper + sherpa-offline + sherpa-online all showed running.
        let stt = stt_auto(
            vec![
                local_entry(),
                remote_entry("a", "OpenAI", "openai", "", "whisper-1"),
                remote_entry("b", "Groq", "groq", "", "whisper-large-v3"),
                remote_entry("c", "Self", "", "http://srv:8000", "whisper-1"),
            ],
            true,
        );
        let status = server_status(&stt);
        assert_eq!(status.len(), 1);
        assert_eq!(status[0].0, "OpenAI");
        assert!(!status[0].1, "cold before reconcile");
        // Empty chain: no rows at all.
        let empty = stt_auto(vec![], true);
        assert!(server_status(&empty).is_empty());
        let locals = stt_auto(vec![local_entry()], true);
        assert!(server_status(&locals).is_empty());
        // Applied reconcile lights the single primary.
        let mut last = (stt_primary_id(&stt), false);
        assert_eq!(tick_applied(&stt, &mut last), TickOutcome::Applied);
        let status = server_status(&stt);
        assert_eq!(status.len(), 1);
        assert_eq!(status[0], ("OpenAI".to_string(), true));
    }

    #[test]
    fn switch_kills_previous_chain_and_noop_does_not_bump() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let v2 = stt_auto(
            vec![remote_entry("b", "Groq", "groq", "", "whisper-large-v3")],
            true,
        );
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        let gen1 = server_generation();
        assert!(gen1 > 0);
        assert!(server_status(&v1)[0].1);
        // Steady re-apply: no bump, chain untouched.
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        assert_eq!(server_generation(), gen1);
        // Switch: bump kills the previous chain, new one live.
        assert_eq!(reconcile_servers(&v2, None), Ok(false));
        assert!(server_generation() > gen1);
        assert_eq!(server_status(&v2)[0], ("Groq".to_string(), true));
        assert!(!server_status(&v1)[0].1, "old chain no longer live");
    }

    #[test]
    fn stale_authority_defers_without_mutation() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let v2 = stt_auto(
            vec![remote_entry("b", "Groq", "groq", "", "whisper-large-v3")],
            true,
        );
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        let gen1 = server_generation();
        // Park a validated observation of v1, then deliberately move.
        let parked = ReconcileAuthority {
            witness: gen1,
            snapshot_fp: stt_fingerprint(&v1),
        };
        assert_eq!(reconcile_servers(&v2, None), Ok(false));
        let gen2 = server_generation();
        assert!(gen2 > gen1);
        // Stale witness defers: active chain, generation untouched.
        assert_eq!(reconcile_servers(&v1, Some(parked.clone())), Ok(true));
        assert_eq!(server_generation(), gen2);
        assert!(server_status(&v2)[0].1);
        // Stale snapshot (fresh witness, superseded identity) defers too:
        // the attested fingerprint no longer matches the passed settings.
        let stale_snap = ReconcileAuthority {
            witness: gen2,
            snapshot_fp: parked.snapshot_fp.clone(),
        };
        assert_eq!(reconcile_servers(&v2, Some(stale_snap)), Ok(true));
        assert_eq!(server_generation(), gen2);
        assert!(server_status(&v2)[0].1);
        // Lazy branch carries identity the same way.
        assert_eq!(reconcile_lazy_switch(&v1, Some(parked)), Ok(true));
        assert_eq!(server_generation(), gen2);
    }

    #[test]
    fn stop_suppresses_restart_until_deliberate_change() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let v2 = stt_auto(
            vec![remote_entry("b", "Groq", "groq", "", "whisper-large-v3")],
            true,
        );
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        let fp1 = stt_fingerprint(&v1);
        // Explicit Stop: restart of the same fingerprint settles cold.
        record_stop(&fp1);
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        assert!(!server_status(&v1)[0].1);
        // A deliberate switch expires the suppression and restarts...
        assert_eq!(reconcile_servers(&v2, None), Ok(false));
        assert!(server_status(&v2)[0].1);
        // ...including returning to the previously stopped config.
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        assert!(server_status(&v1)[0].1);
    }

    #[test]
    fn stale_expiry_cannot_clear_newer_stop() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        // Park the observation, then land a newer Stop inside the window.
        let parked = ReconcileAuthority {
            witness: server_generation(),
            snapshot_fp: stt_fingerprint(&v1),
        };
        record_stop(&stt_fingerprint(&v1));
        assert!(!expire_stop_suppression_at(&v1, &parked));
        // Suppression intact: still cold, autostart still settles.
        assert!(!server_status(&v1)[0].1);
        assert_eq!(reconcile_servers(&v1, None), Ok(false));
        assert!(!server_status(&v1)[0].1);
    }

    #[test]
    fn expiry_refusal_preserves_bookkeeping_and_genuine_toggle_restarts() {
        let _guard = status_test_lock();
        reset_status_state();
        let dis = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            false,
        );
        let en = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        // Disable observes (marker refreshed), then a Stop lands newer.
        let mut last = (stt_primary_id(&dis), true);
        assert_eq!(tick_applied(&dis, &mut last), TickOutcome::Applied);
        assert!(!last.1);
        let parked = ReconcileAuthority {
            witness: server_generation(),
            snapshot_fp: stt_fingerprint(&en),
        };
        record_stop(&stt_fingerprint(&dis));
        // Parked re-enable observes the newer Stop: defer, last_auto
        // preserved false (never marked as successfully applied).
        assert_eq!(
            stt_watcher_tick(&en, &mut last.0, &mut last.1, Some(parked)),
            TickOutcome::Deferred
        );
        assert!(!last.1);
        // A fresh disable observation refreshes past the Stop (even on
        // an unchanged tick — the marker refresh runs before the skip
        // check)...
        assert_eq!(tick_applied(&dis, &mut last), TickOutcome::Unchanged);
        // ...so the genuine toggle warms normally.
        assert_eq!(tick_applied(&en, &mut last), TickOutcome::Applied);
        assert!(last.1);
        assert!(server_status(&en)[0].1);
    }

    #[test]
    fn tick_defers_while_active_and_retries_next_poll() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let v2 = stt_auto(
            vec![remote_entry("b", "Groq", "groq", "", "whisper-large-v3")],
            true,
        );
        let mut last = (String::new(), false);
        assert_eq!(tick_applied(&v1, &mut last), TickOutcome::Applied);
        let gen1 = server_generation();
        // Active utterance: move defers, markers/chain/generation intact.
        active_inc();
        assert_eq!(tick_applied(&v2, &mut last), TickOutcome::Deferred);
        active_dec();
        assert_eq!(last.0, stt_primary_id(&v1));
        assert_eq!(server_generation(), gen1);
        assert!(server_status(&v1)[0].1);
        // Idle retry applies the pending switch.
        assert_eq!(tick_applied(&v2, &mut last), TickOutcome::Applied);
        assert_eq!(last.0, stt_primary_id(&v2));
        assert!(server_status(&v2)[0].1);
    }

    #[test]
    fn unrelated_edits_do_not_bounce_the_chain() {
        let _guard = status_test_lock();
        reset_status_state();
        let mut v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let mut last = (String::new(), false);
        assert_eq!(tick_applied(&v1, &mut last), TickOutcome::Applied);
        let gen1 = server_generation();
        // keep-warm, language-model keys and entry renames that leave
        // the primary identity alone are steady-state ticks.
        v1.keep_warm = "5m".to_string();
        v1.entries[0].name = "Renamed".to_string();
        v1.entries[0].api_key = "sk-new".to_string();
        assert_eq!(tick_applied(&v1, &mut last), TickOutcome::Unchanged);
        assert_eq!(server_generation(), gen1);
        // Language travels with the identity (it shapes requests).
        v1.language = "es".to_string();
        assert_eq!(tick_applied(&v1, &mut last), TickOutcome::Applied);
        assert!(server_generation() > gen1);
    }

    #[test]
    fn lazy_switch_kills_old_chain_and_stays_cold() {
        let _guard = status_test_lock();
        reset_status_state();
        let warm = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let mut last = (String::new(), false);
        assert_eq!(tick_applied(&warm, &mut last), TickOutcome::Applied);
        let gen1 = server_generation();
        // Autostart off + primary moved: old chain killed, new cold.
        let lazy = stt_auto(
            vec![remote_entry("b", "Groq", "groq", "", "whisper-large-v3")],
            false,
        );
        assert_eq!(tick_applied(&lazy, &mut last), TickOutcome::Applied);
        assert!(server_generation() > gen1);
        assert_eq!(last, (stt_primary_id(&lazy), false));
        let status = server_status(&lazy);
        assert_eq!(status.len(), 1);
        assert_eq!(status[0].0, "Groq");
        assert!(!status[0].1, "lazy leaves the new chain cold");
        // Steady lazy ticks are no-ops.
        assert_eq!(tick_applied(&lazy, &mut last), TickOutcome::Unchanged);
    }

    #[test]
    fn leaving_remote_kills_chain_and_settles() {
        let _guard = status_test_lock();
        reset_status_state();
        let v1 = stt_auto(
            vec![remote_entry("a", "OpenAI", "openai", "", "whisper-1")],
            true,
        );
        let mut last = (String::new(), false);
        assert_eq!(tick_applied(&v1, &mut last), TickOutcome::Applied);
        let gen1 = server_generation();
        // All remotes disabled: previous chain killed, status empty.
        let mut gone = v1.clone();
        gone.entries[0].enabled = false;
        assert_eq!(tick_applied(&gone, &mut last), TickOutcome::Applied);
        assert!(server_generation() > gen1);
        assert!(server_status(&gone).is_empty());
    }

    // -- desktop #22 port: bounded IO + session-gated failover ---------

    fn self_hosted_entry(name: &str, url: &str) -> SttChainEntry {
        SttChainEntry {
            id: format!("stt-{name}"),
            name: name.to_string(),
            kind: SttEntryKind::SelfHosted,
            enabled: true,
            provider: String::new(),
            url: url.to_string(),
            model: "test".to_string(),
            api_key: String::new(),
            streaming: false,
        }
    }

    /// Single-shot stub: consumes one request (headers + body per
    /// `Content-Length`), counts the hit, replies `body`. Deadline-bound
    /// accept so a client that never connects cannot wedge the join.
    fn stub_server(
        body: &'static str,
    ) -> (
        String,
        std::sync::Arc<std::sync::atomic::AtomicUsize>,
        std::thread::JoinHandle<()>,
    ) {
        use std::io::{Read, Write};
        use std::sync::atomic::Ordering;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let hits = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let counted = hits.clone();
        let handle = std::thread::spawn(move || {
            listener.set_nonblocking(true).unwrap();
            // No connection ever arrives on the stale-witness path, so
            // this only needs to outlive the client's immediate abort.
            let stop = std::time::Instant::now() + std::time::Duration::from_secs(2);
            let stream = loop {
                match listener.accept() {
                    Ok((s, _)) => break Some(s),
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= stop {
                            break None;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(2));
                    }
                    Err(_) => break None,
                }
            };
            let mut sock = match stream {
                Some(s) => s,
                None => return,
            };
            counted.fetch_add(1, Ordering::SeqCst);
            sock.set_read_timeout(Some(std::time::Duration::from_secs(10)))
                .unwrap();
            let mut buf = Vec::new();
            let mut tmp = [0u8; 4096];
            loop {
                let n = sock.read(&mut tmp).unwrap_or(0);
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
                if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    break;
                }
            }
            let head_end = buf
                .windows(4)
                .position(|w| w == b"\r\n\r\n")
                .map(|p| p + 4)
                .unwrap_or(buf.len());
            let headers = String::from_utf8_lossy(&buf[..head_end.min(buf.len())]);
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
            while buf.len().saturating_sub(head_end) < len {
                let n = sock.read(&mut tmp).unwrap_or(0);
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
            }
            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = sock.write_all(resp.as_bytes());
        });
        (url, hits, handle)
    }

    #[test]
    fn stale_witness_aborts_before_any_fallback_upload() {
        use std::sync::atomic::Ordering;
        let _guard = status_test_lock();
        reset_status_state();
        let (url_a, hits_a, srv_a) = stub_server(r#"{"text":"a"}"#);
        let (url_b, hits_b, srv_b) = stub_server(r#"{"text":"b"}"#);
        let stt = stt_auto(
            vec![
                self_hosted_entry("A", &url_a),
                self_hosted_entry("B", &url_b),
            ],
            false,
        );
        // Admission witness, then a cancel lands before the chain runs.
        let witness = server_generation();
        record_stop(&stt_fingerprint(&stt));
        let err =
            transcribe_chain_witnessed(&stt, ChainPart::All, &vec![1000i16; 1600], &[], witness)
                .unwrap_err();
        assert!(
            err.contains("superseded") || err.contains("cancelled"),
            "stale chain must abort, got: {err}"
        );
        srv_a.join().unwrap();
        srv_b.join().unwrap();
        // Neither the admitted entry nor the fallback was ever uploaded to.
        assert_eq!(hits_a.load(Ordering::SeqCst), 0);
        assert_eq!(hits_b.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn chain_parts_split_at_first_enabled_local() {
        use std::sync::atomic::Ordering;
        let _guard = status_test_lock();
        reset_status_state();
        // Closed port: the entry above Local fails fast (connection refused).
        let closed = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            format!("http://{}", l.local_addr().unwrap())
        };
        let (url_b, hits_b, srv_b) = stub_server(r#"{"text":"b"}"#);
        let mut disabled_local = local_entry();
        disabled_local.enabled = false;
        // Settings order: (disabled Local) → A → Local → B.
        let stt = stt_auto(
            vec![
                disabled_local,
                self_hosted_entry("A", &closed),
                local_entry(),
                self_hosted_entry("B", &url_b),
            ],
            false,
        );
        let pcm = vec![1000i16; 1600];
        let err = transcribe_chain_witnessed(
            &stt,
            ChainPart::BeforeLocal,
            &pcm,
            &[],
            server_generation(),
        )
        .unwrap_err();
        assert!(
            err.starts_with("A: "),
            "only A runs above Local, got: {err}"
        );
        assert_eq!(
            hits_b.load(Ordering::SeqCst),
            0,
            "B must wait for the Local attempt"
        );
        let t =
            transcribe_chain_witnessed(&stt, ChainPart::AfterLocal, &pcm, &[], server_generation())
                .unwrap();
        assert_eq!(t.text, "b");
        srv_b.join().unwrap();
        assert_eq!(hits_b.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn fresh_witness_uses_first_success_without_touching_fallback() {
        use std::sync::atomic::Ordering;
        let _guard = status_test_lock();
        reset_status_state();
        let (url_a, hits_a, srv_a) = stub_server(r#"{"text":"hello"}"#);
        let (url_b, hits_b, srv_b) = stub_server(r#"{"text":"fallback"}"#);
        let stt = stt_auto(
            vec![
                self_hosted_entry("A", &url_a),
                self_hosted_entry("B", &url_b),
            ],
            false,
        );
        let t = transcribe_chain_witnessed(
            &stt,
            ChainPart::All,
            &vec![1000i16; 1600],
            &[],
            server_generation(),
        )
        .unwrap();
        assert_eq!(t.text, "hello");
        srv_a.join().unwrap();
        srv_b.join().unwrap();
        assert_eq!(hits_a.load(Ordering::SeqCst), 1);
        assert_eq!(hits_b.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn oversized_response_is_rejected_from_headers_without_reading_body() {
        let _guard = status_test_lock();
        reset_status_state();
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut sock, _) = listener.accept().unwrap();
            sock.set_read_timeout(Some(std::time::Duration::from_secs(10)))
                .unwrap();
            let mut buf = Vec::new();
            let mut tmp = [0u8; 4096];
            loop {
                let n = sock.read(&mut tmp).unwrap_or(0);
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
                if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    break;
                }
            }
            // Drain the whole request body first: closing while the
            // client is still uploading can RST its send (flake), and
            // the point under test is the reply, not the upload.
            let head_end = buf
                .windows(4)
                .position(|w| w == b"\r\n\r\n")
                .map(|p| p + 4)
                .unwrap_or(buf.len());
            let headers = String::from_utf8_lossy(&buf[..head_end.min(buf.len())]);
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
            while buf.len().saturating_sub(head_end) < len {
                let n = sock.read(&mut tmp).unwrap_or(0);
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
            }
            // Claim 2 MiB; the client must reject from the header alone.
            let _ = sock.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2097152\r\nConnection: close\r\n\r\n",
            );
        });
        let began = std::time::Instant::now();
        let err = post_openai_compat(&url, "", "whisper-1", "en", None, vec![0u8; 44]).unwrap_err();
        assert!(
            err.contains("response size limit exceeded"),
            "oversized reply must be capped, got: {err}"
        );
        assert!(
            began.elapsed() < std::time::Duration::from_secs(30),
            "cap rejection took {:?}",
            began.elapsed()
        );
        server.join().unwrap();
    }

    #[test]
    fn transcription_text_folds_segment_newlines() {
        assert_eq!(
            transcription_text(&json!({"text":" open for this\n project.\n You can\n"})),
            Some("open for this project. You can".into())
        );
        assert_eq!(
            transcription_text(&json!({"data":{"text":" hi "}})),
            Some("hi".into())
        );
        assert_eq!(transcription_text(&json!({})), None);
    }

    #[test]
    fn chunked_oversize_body_is_capped_without_content_length() {
        // No Content-Length (chunked framing): the per-chunk cap must
        // abort past 1 MiB instead of buffering the whole body. The
        // server ignores write errors — the client drops the socket on
        // abort, which is the legitimate outcome, not a stall.
        let _guard = status_test_lock();
        reset_status_state();
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut sock, _) = listener.accept().unwrap();
            sock.set_read_timeout(Some(std::time::Duration::from_secs(10)))
                .unwrap();
            let mut buf = Vec::new();
            let mut tmp = [0u8; 4096];
            loop {
                let n = sock.read(&mut tmp).unwrap_or(0);
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&tmp[..n]);
                if buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    break;
                }
            }
            let _ = sock.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
            );
            let chunk = vec![b'x'; 16384];
            for _ in 0..80 {
                if sock
                    .write_all(format!("{:X}\r\n", chunk.len()).as_bytes())
                    .is_err()
                {
                    break;
                }
                if sock.write_all(&chunk).is_err() || sock.write_all(b"\r\n").is_err() {
                    break;
                }
            }
            let _ = sock.write_all(b"0\r\n\r\n");
        });
        let began = std::time::Instant::now();
        let err = post_openai_compat(&url, "", "whisper-1", "en", None, vec![0u8; 44]).unwrap_err();
        assert!(
            err.contains("response size limit exceeded"),
            "chunked oversize must be capped, got: {err}"
        );
        assert!(
            began.elapsed() < std::time::Duration::from_secs(30),
            "cap rejection took {:?}",
            began.elapsed()
        );
        server.join().unwrap();
    }
}
