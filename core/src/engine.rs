//! Mobile utterance engine: Recorder → resample → VAD → server STT
//! chain → snippet expansion → optional LLM cleanup → history.
//!
//! Mirrors the desktop `Down/Up → settle → VAD → end_utterance → deliver`
//! flow (`bin/mywhisper.rs`, `transcribe.rs::deliver`), minus local
//! server supervision and speculative passes (streaming partials are a
//! TODO; v1 transcribes the whole utterance on finish, which suits
//! request/response servers and short dictation bursts).

use crate::audio::{self, Recorder, MIN_SAMPLES_16K};
use crate::server_stt::{transcribe_chain, Transcript};
use crate::settings::{AppSettings, CloudLlm, HistoryEntry, LlmKind};
use crate::store::Store;
use crate::textnorm::expand_snippets;
use std::path::PathBuf;
use std::time::Instant;

fn random_node_id() -> String {
    let mut raw = [0u8; 8];
    let _ = getrandom::fill(&mut raw);
    raw.iter().map(|b| format!("{b:02x}")).collect()
}

fn chat_base(provider: &CloudLlm, url: &str) -> Option<String> {
    if !url.trim().is_empty() {
        return Some(url.trim().trim_end_matches('/').to_string());
    }
    match provider {
        CloudLlm::OpenAI => Some("https://api.openai.com/v1".to_string()),
        CloudLlm::Groq => Some("https://api.groq.com/openai/v1".to_string()),
        CloudLlm::OpenRouter => Some("https://openrouter.ai/api/v1".to_string()),
        CloudLlm::Claude | CloudLlm::Google => None,
    }
}

/// Minimal chat-completions cleanup call (Ollama `/api/generate` or
/// OpenAI-compatible `/v1/chat/completions`). Failures fall back to
/// passthrough at the call site.
fn llm_cleanup(
    url: &str,
    kind: LlmKind,
    provider: &CloudLlm,
    model: &str,
    api_key: &str,
    system: &str,
    text: &str,
) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|e| format!("http client: {e}"))?;
    let prompt = if system.trim().is_empty() {
        format!("Clean up this dictated text: fix punctuation and capitalization, do not change words or add content. Return only the cleaned text.\n\n{text}")
    } else {
        format!("{system}\n\n{text}")
    };
    let (endpoint, body) = match kind {
        LlmKind::Ollama => {
            let base = url.trim().trim_end_matches('/');
            let base = if base.is_empty() {
                "http://127.0.0.1:11434"
            } else {
                base
            };
            (
                format!("{base}/api/generate"),
                serde_json::json!({"model": model, "prompt": prompt, "stream": false}),
            )
        }
        LlmKind::Local => {
            let base = url.trim().trim_end_matches('/');
            if base.is_empty() {
                return Err("local LLM has no URL".to_string());
            }
            (
                format!("{base}/v1/chat/completions"),
                serde_json::json!({"model": model, "messages": [{"role": "user", "content": prompt}]}),
            )
        }
        LlmKind::Cloud => {
            let base = chat_base(provider, url).ok_or_else(|| {
                "this cloud LLM provider is not supported for cleanup yet".to_string()
            })?;
            (
                format!("{base}/chat/completions"),
                serde_json::json!({"model": model, "messages": [{"role": "user", "content": prompt}]}),
            )
        }
    };
    let mut req = client.post(&endpoint).json(&body);
    if kind != LlmKind::Ollama && !api_key.trim().is_empty() {
        req = req.bearer_auth(api_key.trim());
    }
    let resp = req.send().map_err(|e| format!("request: {e}"))?;
    let status = resp.status();
    let raw = resp.text().map_err(|e| format!("read body: {e}"))?;
    if !status.is_success() {
        return Err(format!(
            "server {status}: {}",
            raw.chars().take(200).collect::<String>()
        ));
    }
    let v: serde_json::Value = serde_json::from_str(&raw).map_err(|e| format!("parse: {e}"))?;
    if kind == LlmKind::Ollama {
        return v
            .get("response")
            .and_then(|s| s.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| "no response in reply".to_string());
    }
    v.pointer("/choices/0/message/content")
        .and_then(|s| s.as_str())
        .map(|s| s.trim().to_string())
        .ok_or_else(|| "no content in reply".to_string())
}

pub struct FinishOutcome {
    pub text: String,
    pub raw: String,
    pub engine: String,
    pub duration_secs: i64,
    pub words: u32,
}

pub struct Engine {
    store: Store,
    settings: AppSettings,
    recorder: Recorder,
    rate: u32,
    started_at: Option<Instant>,
    node_id: String,
    _dir: PathBuf,
}

impl Engine {
    pub fn open(dir: PathBuf, key_hex: &str) -> Result<Self, String> {
        // SAFETY: single-threaded at open; sets process env for store::db_key.
        unsafe { std::env::set_var("MYWHISPER_DB_KEY", key_hex.trim()) };
        std::fs::create_dir_all(&dir).map_err(|e| format!("data dir: {e}"))?;
        let db = dir.join("state.db");
        let store = Store::open(&db).map_err(|e| format!("open db: {e}"))?;
        let settings = store.load_settings();
        Ok(Self {
            store,
            settings,
            recorder: Recorder::default(),
            rate: 0,
            started_at: None,
            node_id: random_node_id(),
            _dir: dir,
        })
    }

    pub fn settings_json(&self) -> String {
        serde_json::to_string(&self.settings).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn put_settings(&mut self, json: &str) -> Result<(), String> {
        let s: AppSettings =
            serde_json::from_str(json).map_err(|e| format!("parse settings: {e}"))?;
        self.store
            .save_settings(&s)
            .map_err(|e| format!("save: {e}"))?;
        self.settings = s;
        Ok(())
    }

    pub fn start_utterance(&mut self, rate: u32) {
        self.recorder.start(rate);
        self.rate = rate;
        self.started_at = Some(Instant::now());
    }

    pub fn push_pcm(&mut self, samples: &[i16]) {
        self.recorder.push(samples);
    }

    pub fn cancel_utterance(&mut self) -> i64 {
        let secs = self
            .started_at
            .map(|t| t.elapsed().as_secs() as i64)
            .unwrap_or(0);
        self.recorder = Recorder::default();
        self.started_at = None;
        let _ = self.store.add_discarded(secs.max(1), &self.node_id);
        secs
    }

    pub fn finish_utterance(&mut self) -> Result<FinishOutcome, String> {
        let started = self.started_at.take();
        self.recorder.settle_release(Instant::now());
        let (samples, rate) = self.recorder.finish();
        let duration_secs = started
            .map(|t| t.elapsed().as_secs() as i64)
            .unwrap_or(0)
            .max(1);

        let resampled = audio::Resampler::resample_all(rate.max(1), &samples);
        if resampled.len() < MIN_SAMPLES_16K {
            return Err("too short (under 0.3s of audio)".to_string());
        }
        let selected: Vec<f32> = if self.settings.stt.vad_enabled {
            match audio::apply_vad(&resampled, self.settings.stt.vad_threshold) {
                audio::VadResult::Speech(s) => s,
                audio::VadResult::Silence => {
                    return Err("silence (VAD dropped everything)".to_string())
                }
            }
        } else {
            resampled
        };
        let pcm16 = audio::f32_to_i16(&selected);

        let words = self.store.load_words().unwrap_or_default();
        let Transcript { text: raw, engine } =
            transcribe_chain(&self.settings.stt, &pcm16, &words)?;

        let snippets = self.store.load_snippets().unwrap_or_default();
        let pairs: Vec<(String, String)> = snippets
            .iter()
            .map(|s| (s.trigger.clone(), s.replacement.clone()))
            .collect();
        let mut text = expand_snippets(&raw, &pairs);

        // Optional LLM cleanup (passthrough on any failure).
        let lm = &self.settings.language_models;
        if lm.cleanup_enabled {
            if let Some(entry) = lm.entries.iter().find(|e| e.enabled) {
                let prompt = if lm.cleanup_prompt.trim().is_empty() {
                    String::new()
                } else {
                    lm.cleanup_prompt.clone()
                };
                if let Ok(clean) = llm_cleanup(
                    &entry.url,
                    entry.kind,
                    &entry.provider,
                    &entry.model,
                    &entry.api_key,
                    &prompt,
                    &text,
                ) {
                    if !clean.trim().is_empty() {
                        text = clean;
                    }
                }
            }
        }

        let word_count = text.split_whitespace().count() as u32;
        let _ = self
            .store
            .add_history(&raw, &text, word_count, duration_secs, &self.node_id);
        let _ = self.store.checkpoint();
        Ok(FinishOutcome {
            text,
            raw,
            engine,
            duration_secs,
            words: word_count,
        })
    }

    pub fn history_json(&self, limit: usize) -> String {
        let items: Vec<HistoryEntry> = self.store.load_history(limit, false).unwrap_or_default();
        // Manual mapping: desktop HistoryEntry has no Serialize impl.
        let mapped: Vec<serde_json::Value> = items
            .iter()
            .map(|h| {
                serde_json::json!({
                    "uuid": h.uuid, "text": h.text, "rawText": h.raw_text,
                    "dateLabel": h.date_label, "wordCount": h.word_count,
                    "durationSecs": h.duration_secs, "recordedAt": h.recorded_at,
                    "discarded": h.discarded,
                })
            })
            .collect();
        serde_json::to_string(&mapped).unwrap_or_else(|_| "[]".to_string())
    }

    pub fn clear_history(&self) -> Result<usize, String> {
        self.store
            .clear_history()
            .map_err(|e| format!("clear: {e}"))
    }

    pub fn delete_history(&self, uuid: &str) -> Result<(), String> {
        self.store
            .delete_history(uuid)
            .map_err(|e| format!("delete: {e}"))
    }
}
