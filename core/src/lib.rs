//! `mywhisper-core`: portable MyWhisper engine for Android.
//!
//! Verbatim ports from desktop (re-copy + diff to update, see
//! `core/README.md`): `settings`, `audio`, `textnorm`, `store`.
//! Mobile-only: `server_stt` (server STT chain client), `engine`
//! (utterance pipeline), JNI bridge below (this file).

pub mod audio;
pub mod device;
pub mod engine;
pub mod net;
pub mod server_stt;
pub mod settings;
pub mod store;
pub mod sync_ctl;
pub mod textnorm;
pub mod viz_types;

use engine::Engine;
use jni::JNIEnv;
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jint, jstring};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

fn with_engine<R>(f: impl FnOnce(&mut Engine) -> R, err: impl FnOnce() -> R) -> R {
    match ENGINE.get() {
        Some(m) => match m.lock() {
            Ok(mut e) => f(&mut e),
            Err(_) => err(),
        },
        None => err(),
    }
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|o| o.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Consecutive active days ending today or yesterday (desktop
/// `app::streak` port over the local-day `active_days` list, desc).
/// UTC day is used for the today/yesterday touch check (1-day
/// tolerance covers the local-bucket skew).
fn streak_days(store: &crate::store::Store) -> u32 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let days = store.active_days().unwrap_or_default();
    let today_utc = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64 / 86400)
        .unwrap_or(0);
    let max = match days.first() {
        Some(m) => *m,
        None => return 0,
    };
    if max < today_utc - 1 {
        return 0;
    }
    let mut run = 0u32;
    let mut expected = max;
    for d in days.iter() {
        if *d == expected {
            run += 1;
            expected -= 1;
        } else if *d < expected {
            break;
        }
    }
    run
}

fn ok_json(value: serde_json::Value) -> String {
    serde_json::json!({"ok": true, "data": value}).to_string()
}

fn err_json(e: impl std::fmt::Display) -> String {
    serde_json::json!({"ok": false, "error": e.to_string()}).to_string()
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| e.to_string())
}

/// Open (or create) `state.db` under `filesDir` with the Keystore-held
/// `dbKeyHex` (64 hex chars). Must be called before any other method.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeOpen(
    mut env: JNIEnv,
    _cls: JClass,
    files_dir: JString,
    db_key_hex: JString,
) -> jstring {
    let out = (|| -> Result<String, String> {
        let dir = get_string(&mut env, &files_dir)?;
        let key = get_string(&mut env, &db_key_hex)?;
        if key.trim().len() != 64 {
            return Err("db key must be 64 hex chars".to_string());
        }
        let eng = Engine::open(PathBuf::from(dir), key.trim())?;
        if ENGINE.get().is_none() {
            let _ = ENGINE.set(Mutex::new(eng));
        } else if let Ok(mut e) = ENGINE.get().unwrap().lock() {
            *e = eng;
        }
        Ok(ok_json(serde_json::json!({"version": CORE_VERSION})))
    })();
    jstr(&mut env, &out.unwrap_or_else(err_json))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetSettings(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(|e| e.settings_json(), || err_json("engine not open"));
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativePutSettings(
    mut env: JNIEnv,
    _cls: JClass,
    json: JString,
) -> jstring {
    let out = (|| -> String {
        let body = get_string(&mut env, &json).unwrap_or_default();
        with_engine(
            |e| match e.put_settings(&body) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    })();
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeStartUtterance(
    mut env: JNIEnv,
    _cls: JClass,
    sample_rate: jint,
) -> jstring {
    let out = with_engine(
        |e| {
            e.start_utterance(sample_rate as u32);
            ok_json(serde_json::Value::Null)
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativePushPcm(
    mut env: JNIEnv,
    _cls: JClass,
    data: JShortArray,
    length: jint,
) -> jstring {
    let out = with_engine(
        |e| {
            let n = length.max(0) as usize;
            let mut buf = vec![0i16; n];
            if env.get_short_array_region(&data, 0, &mut buf).is_err() {
                return err_json("pcm copy failed");
            }
            e.push_pcm(&buf);
            ok_json(serde_json::Value::Null)
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

/// Block while the server chain transcribes (call off the UI thread).
/// Returns `{ok, data: {text, raw, engine, durationSecs, words}}`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeFinishUtterance(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| match e.finish_utterance() {
            Ok(o) => ok_json(serde_json::json!({
                "text": o.text, "raw": o.raw, "engine": o.engine,
                "durationSecs": o.duration_secs, "words": o.words,
            })),
            Err(err) => err_json(err),
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeCancelUtterance(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| {
            let secs = e.cancel_utterance();
            ok_json(serde_json::json!({"durationSecs": secs}))
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetHistory(
    mut env: JNIEnv,
    _cls: JClass,
    limit: jint,
) -> jstring {
    let out = with_engine(
        |e| e.history_json(limit.max(1) as usize),
        || "[]".to_string(),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeClearHistory(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| match e.clear_history() {
            Ok(n) => ok_json(serde_json::json!({"removed": n})),
            Err(err) => err_json(err),
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDeleteHistory(
    mut env: JNIEnv,
    _cls: JClass,
    uuid: JString,
) -> jstring {
    let out = (|| -> String {
        let id = get_string(&mut env, &uuid).unwrap_or_default();
        with_engine(
            |e| match e.delete_history(&id) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    })();
    jstr(&mut env, &out)
}

fn engine_dir() -> Result<PathBuf, String> {
    with_engine(
        |e| Ok(e.dir().to_path_buf()),
        || Err("engine not open".to_string()),
    )
}

/// The effective default cleanup prompt (desktop-identical).
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDefaultCleanupPrompt(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jstr(
        &mut env,
        &ok_json(serde_json::Value::String(
            engine::DEFAULT_CLEANUP_PROMPT.to_string(),
        )),
    )
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativePurgeHistory(
    mut env: JNIEnv,
    _cls: JClass,
    days: jint,
) -> jstring {
    let out = with_engine(
        |e| match e.purge_history(days.max(0) as u32) {
            Ok(n) => ok_json(serde_json::json!({"removed": n})),
            Err(err) => err_json(err),
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeUsageStats(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| {
            let dir = e.dir().to_path_buf();
            let node = e.node_id().to_string();
            match crate::store::Store::open(&dir.join("state.db")) {
                Ok(s) => match s.usage_stats() {
                    Ok(mut u) => {
                        // Desktop refresh_stats derives streak separately.
                        u.streak_days = streak_days(&s);
                        ok_json(serde_json::json!({
                            "wordsSpoken": u.words_spoken,
                            "dictations": u.dictations,
                            "wordsPerMinute": u.words_per_minute,
                            "streakDays": u.streak_days,
                            "nodeId": node,
                        }))
                    }
                    Err(err) => err_json(format!("stats: {err}")),
                },
                Err(err) => err_json(format!("open db: {err}")),
            }
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

/// Per-day aggregates for the trailing `days` days:
/// `[{dayStart, words, utterances}]` (dayStart = epoch seconds).
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDailyActivity(
    mut env: JNIEnv,
    _cls: JClass,
    days: jint,
) -> jstring {
    let out = with_engine(
        |e| {
            let dir = e.dir().to_path_buf();
            match crate::store::Store::open(&dir.join("state.db")) {
                Ok(s) => match s.daily_aggregates(days.max(1) as u32) {
                    Ok(rows) => {
                        let mapped: Vec<serde_json::Value> = rows
                            .iter()
                            .map(|(day, words, n)| {
                                serde_json::json!({
                                    "dayStart": day * 86400,
                                    "words": words,
                                    "utterances": n,
                                })
                            })
                            .collect();
                        ok_json(serde_json::Value::Array(mapped))
                    }
                    Err(err) => err_json(format!("activity: {err}")),
                },
                Err(err) => err_json(format!("open db: {err}")),
            }
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

// -- sync ---------------------------------------------------------------

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncStart(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = match engine_dir() {
        Ok(dir) => match crate::sync_ctl::sync_start(dir) {
            Ok(s) => ok_json(serde_json::Value::String(s)),
            Err(err) => err_json(err),
        },
        Err(err) => err_json(err),
    };
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncStop(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    crate::sync_ctl::sync_stop();
    jstr(&mut env, &ok_json(serde_json::Value::Null))
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncStatus(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = match engine_dir() {
        Ok(dir) => crate::sync_ctl::sync_status_json(&dir),
        Err(err) => err_json(err),
    };
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncPeers(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = match engine_dir() {
        Ok(dir) => crate::sync_ctl::sync_peers_json(&dir),
        Err(_) => "[]".to_string(),
    };
    jstr(&mut env, &out)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncTrust(
    mut env: JNIEnv,
    _cls: JClass,
    node_id: JString,
    accept: jni::sys::jboolean,
) -> jstring {
    let out = (|| -> String {
        let id = get_string(&mut env, &node_id).unwrap_or_default();
        let dir = match engine_dir() {
            Ok(d) => d,
            Err(e) => return err_json(e),
        };
        match crate::sync_ctl::sync_set_trust(&dir, &id, accept != 0) {
            Ok(s) => ok_json(serde_json::Value::String(s)),
            Err(err) => err_json(err),
        }
    })();
    jstr(&mut env, &out)
}

/// Blocking single-peer round (call off the UI thread).
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncNow(
    mut env: JNIEnv,
    _cls: JClass,
    node_id: JString,
) -> jstring {
    let out = (|| -> String {
        let id = get_string(&mut env, &node_id).unwrap_or_default();
        let dir = match engine_dir() {
            Ok(d) => d,
            Err(e) => return err_json(e),
        };
        match crate::sync_ctl::sync_peer_now(&dir, &id) {
            Ok(s) => ok_json(serde_json::Value::String(s)),
            Err(err) => err_json(err),
        }
    })();
    jstr(&mut env, &out)
}

/// Blocking round with all trusted peers (call off the UI thread).
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncAllNow(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = match engine_dir() {
        Ok(dir) => ok_json(serde_json::Value::Array(
            crate::sync_ctl::sync_all_now(&dir)
                .into_iter()
                .map(serde_json::Value::String)
                .collect(),
        )),
        Err(err) => err_json(err),
    };
    jstr(&mut env, &out)
}
