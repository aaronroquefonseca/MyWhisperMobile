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
use crate::textnorm::{dictionary_prompt, GROQ_PROMPT_CHARS, WHISPER_PROMPT_CHARS};

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
// NOTE: `engine.rs::llm_cleanup_one` still uses its pre-#17 inline
// parsing (owned by a sibling thread); these helpers are the ported
// contract source the cleanup chain adopts next. STT wiring below
// already uses `prompt_budget_for`.
// ---------------------------------------------------------------------------

/// Claude Messages request. `max_tokens` 16384 leaves room for the
/// 1024 thinking budget plus the answer (budget must be < max_tokens,
/// minimum 1024). Adaptive models decide themselves; enabled models
/// need the explicit budget; unknown models error so the caller keeps
/// the model default instead of sending a rejected request.
///
/// Adaptive (per 2026 docs): 4-6/4-7/4-8, 5.x, Mythos/Fable families.
/// Enabled-only: 4-5 family + 3.7 Sonnet. Everything else → Err.
/// `Some(false)` sends `disabled`.
pub fn claude_request(
    model: &str,
    thinking: Option<bool>,
    system: &str,
    user: &str,
) -> Result<serde_json::Value, String> {
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
        } else {
            return Err(
                "thinking capabilities unknown for this Claude model; select model default".into(),
            );
        }
    }
    Ok(body)
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
    use serde_json::{json, Value};
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
            let v = claude_request(model, Some(true), "system", "user").unwrap();
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
                claude_request(model, Some(true), "s", "u").unwrap()["thinking"],
                json!({"type":"adaptive"}),
                "model {model}"
            );
            assert_eq!(
                claude_request(model, Some(false), "s", "u").unwrap()["thinking"]["type"],
                "disabled",
                "model {model}"
            );
        }
        // Unknown capabilities must not guess a mode: error so the
        // caller keeps the model default.
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
                claude_request(model, Some(true), "s", "u").is_err(),
                "model {model}"
            );
        }
        assert!(claude_request("future-model", None, "s", "u")
            .unwrap()
            .get("thinking")
            .is_none());
        assert!(claude_request("claude-sonnet-4-5", None, "s", "u")
            .unwrap()
            .get("thinking")
            .is_none());
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
            assert!(chat_answer(
                &json!({"choices":[{"finish_reason":reason,"message":{"content":"partial"}}]})
            )
            .is_err());
        }
        assert!(ollama_answer(
            &json!({"done":false,"done_reason":"stop","message":{"content":"partial"}})
        )
        .is_err());
        // Missing done_reason is also incomplete.
        assert!(ollama_answer(&json!({"done":true,"message":{"content":"x"}})).is_err());
    }

    #[test]
    fn refusals_tool_calls_and_malformed_blocks_are_rejected() {
        assert!(claude_answer(
            &json!({"stop_reason":"end_turn","content":[{"type":"tool_use","id":"1"}]})
        )
        .is_err());
        assert!(claude_answer(
            &json!({"stop_reason":"end_turn","content":[{"type":"server_tool_use"}]})
        )
        .is_err());
        assert!(
            claude_answer(&json!({"stop_reason":"end_turn","content":[{"type":"text"}]})).is_err()
        );
        assert!(claude_answer(
            &json!({"stop_reason":"refusal","content":[{"type":"text","text":"x"}]})
        )
        .is_err());
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
        assert!(chat_answer(
            &json!({"choices":[{"finish_reason":"tool_calls","message":{"content":"x"}}]})
        )
        .is_err());
        // Ollama: tool_calls rejected, thinking ignored, content required.
        assert!(ollama_answer(
            &json!({"done":true,"done_reason":"stop","message":{"content":"x","tool_calls":[]}})
        )
        .is_err());
        assert_eq!(
            ollama_answer(
                &json!({"done":true,"done_reason":"stop","message":{"content":"hi 🎙️","thinking":"secret"}})
            )
            .unwrap(),
            "hi 🎙️"
        );
        assert!(ollama_answer(
            &json!({"done":true,"done_reason":"stop","message":{"content":null}})
        )
        .is_err());
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
}
