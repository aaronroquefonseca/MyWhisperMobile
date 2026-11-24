//! `mywhisper-core`: portable MyWhisper engine for Android.
//!
//! Verbatim ports from desktop (re-copy + diff to update, see
//! `core/README.md`): `settings`, `audio`, `textnorm`, `store`.
//! Mobile-only: `server_stt` (server STT chain client), `engine`
//! (utterance pipeline), JNI bridge below (this file).

pub mod audio;
pub mod device;
pub mod engine;
pub mod local_llm;
pub mod local_stt;
pub mod net;
pub mod server_stt;
pub mod settings;
pub mod store;
pub mod sync_ctl;
pub mod textnorm;
pub mod tls;
pub mod viz_types;

// IOS PORT: Apple/host builds talk to the engine through the C-ABI
// bridge in `capple.rs` (Swift calls it via the xcframework); the JNI
// bridge below compiles on Android targets only. `CORE_VERSION` and
// the engine modules stay shared. Host builds (`not(android)`,
// incl. CI) compile `capple` so `cargo test` covers it.
#[cfg(not(target_os = "android"))]
pub mod capple;

#[cfg(target_os = "android")]
use engine::Engine;
#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use jni::objects::{JClass, JShortArray, JString};
#[cfg(target_os = "android")]
use jni::sys::{jint, jstring};
#[cfg(target_os = "android")]
use std::path::PathBuf;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

// IOS PORT: JNI-only globals below (Android targets only).
#[cfg(target_os = "android")]
static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

#[cfg(target_os = "android")]
fn with_engine<R>(f: impl FnOnce(&mut Engine) -> R, err: impl FnOnce() -> R) -> R {
    match ENGINE.get() {
        Some(m) => match m.lock() {
            Ok(mut e) => f(&mut e),
            Err(_) => err(),
        },
        None => err(),
    }
}

#[cfg(target_os = "android")]
fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|o| o.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Consecutive active days ending today or yesterday (desktop
/// `app::streak` port over the local-day `active_days` list, desc).
/// UTC day is used for the today/yesterday touch check (1-day
/// tolerance covers the local-bucket skew).
#[cfg(target_os = "android")]
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

#[cfg(target_os = "android")]
fn ok_json(value: serde_json::Value) -> String {
    serde_json::json!({"ok": true, "data": value}).to_string()
}

#[cfg(target_os = "android")]
fn err_json(e: impl std::fmt::Display) -> String {
    serde_json::json!({"ok": false, "error": e.to_string()}).to_string()
}

#[cfg(target_os = "android")]
fn get_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| e.to_string())
}

/// Open (or create) `state.db` under `filesDir` with the Keystore-held
/// `dbKeyHex` (64 hex chars). Must be called before any other method.
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetSettings(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(|e| e.settings_json(), || err_json("engine not open"));
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativePutSettings(
    mut env: JNIEnv,
    _cls: JClass,
    json: JString,
) -> jstring {
    let out = {
        let body = get_string(&mut env, &json).unwrap_or_default();
        with_engine(
            |e| match e.put_settings(&body) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeFinishUtterance(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = finish_out(|e| e.begin_finish());
    jstr(&mut env, &out)
}

/// Runs a finish without holding the engine lock across the network
/// or cleanup (see `engine::finish_unlocked`).
#[cfg(target_os = "android")]
fn finish_out(begin: impl FnOnce(&mut Engine) -> engine::FinishJob) -> String {
    match ENGINE.get().map(|m| engine::finish_unlocked(m, begin)) {
        Some(Ok(o)) => finish_json(o),
        Some(Err(err)) => err_json(err),
        None => err_json("engine not open"),
    }
}

#[cfg(target_os = "android")]
fn finish_json(o: engine::FinishOutcome) -> String {
    ok_json(serde_json::json!({
        "text": o.text, "raw": o.raw, "engine": o.engine,
        "durationSecs": o.duration_secs, "words": o.words,
        "cleanup": o.cleanup,
    }))
}

/// Finish an utterance transcribed on-device: LLM cleanup + history
/// (same tail as `nativeFinishUtterance`).
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeFinishLocalUtterance(
    mut env: JNIEnv,
    _cls: JClass,
    raw: JString,
    text: JString,
    engine_name: JString,
    duration_secs: jint,
) -> jstring {
    let raw = get_string(&mut env, &raw).unwrap_or_default();
    let text = get_string(&mut env, &text).unwrap_or_default();
    let engine_name = get_string(&mut env, &engine_name).unwrap_or_default();
    let out =
        finish_out(|e| e.begin_finish_with_text(&raw, &text, &engine_name, duration_secs as i64));
    jstr(&mut env, &out)
}

/// Register the Kotlin on-device LLM runner (`null` clears it). The
/// runner's `cleanup(String, String): String` is called on the
/// finish thread (outside the engine lock). A thrown exception becomes the entry's
/// error and the cleanup chain falls through.
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSetLocalLlmRunner(
    mut env: JNIEnv,
    _cls: JClass,
    runner: jni::objects::JObject,
) -> jstring {
    if runner.is_null() {
        crate::local_llm::set_local_runner(None);
        return jstr(&mut env, &ok_json(serde_json::Value::Null));
    }
    let (vm, obj) = match (env.get_java_vm(), env.new_global_ref(&runner)) {
        (Ok(vm), Ok(obj)) => (vm, obj),
        _ => return jstr(&mut env, &err_json("local llm runner ref failed")),
    };
    crate::local_llm::set_local_runner(Some(std::sync::Arc::new(
        move |system: &str, raw: &str| -> Result<String, String> {
            let mut env = vm
                .attach_current_thread()
                .map_err(|e| format!("local llm attach: {e}"))?;
            call_local_runner(&mut env, obj.as_obj(), system, raw)
        },
    )));
    jstr(&mut env, &ok_json(serde_json::Value::Null))
}

#[cfg(target_os = "android")]
fn call_local_runner(
    env: &mut JNIEnv,
    runner: &jni::objects::JObject,
    system: &str,
    raw: &str,
) -> Result<String, String> {
    let sys = env
        .new_string(system)
        .map_err(|e| format!("local llm arg: {e}"))?;
    let txt = env
        .new_string(raw)
        .map_err(|e| format!("local llm arg: {e}"))?;
    let res = env.call_method(
        runner,
        "cleanup",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        &[(&sys).into(), (&txt).into()],
    );
    if env.exception_check().unwrap_or(false) {
        let thrown = env.exception_occurred().ok();
        let _ = env.exception_clear();
        let msg = thrown
            .and_then(|t| {
                env.call_method(&t, "toString", "()Ljava/lang/String;", &[])
                    .ok()
            })
            .and_then(|v| v.l().ok())
            .and_then(|o| get_string(env, &JString::from(o)).ok())
            .unwrap_or_else(|| "local llm runner threw".to_string());
        let _ = env.exception_clear();
        return Err(msg);
    }
    let out = res
        .and_then(|v| v.l())
        .map_err(|e| format!("local llm call: {e}"))?;
    if out.is_null() {
        return Err("local llm returned null".to_string());
    }
    get_string(env, &JString::from(out))
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeCancelUtterance(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    // Never waits on an in-flight finish: it no longer holds the lock
    // across the network, and the invalidation itself is lock-free.
    let out = match ENGINE.get().map(engine::cancel_unlocked) {
        Some(Ok(secs)) => ok_json(serde_json::json!({"durationSecs": secs})),
        Some(Err(err)) => err_json(err),
        None => err_json("engine not open"),
    };
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetHistoryPage(
    mut env: JNIEnv,
    _cls: JClass,
    limit: jint,
    before_ts: jni::sys::jlong,
    before_uuid: JString,
    query: JString,
) -> jstring {
    let before_uuid = get_string(&mut env, &before_uuid).unwrap_or_default();
    let query = get_string(&mut env, &query).unwrap_or_default();
    // Empty uuid = first page (newest rows).
    let before = (!before_uuid.is_empty()).then_some((before_ts, before_uuid.as_str()));
    let out = with_engine(
        |e| e.history_page_json(limit.max(1) as usize, before, &query),
        || "[]".to_string(),
    );
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDeleteHistory(
    mut env: JNIEnv,
    _cls: JClass,
    uuid: JString,
) -> jstring {
    let out = {
        let id = get_string(&mut env, &uuid).unwrap_or_default();
        with_engine(
            |e| match e.delete_history(&id) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}

#[cfg(target_os = "android")]
fn engine_dir() -> Result<PathBuf, String> {
    with_engine(
        |e| Ok(e.dir().to_path_buf()),
        || Err("engine not open".to_string()),
    )
}

/// The effective default cleanup prompt (desktop-identical).
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncStop(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    crate::sync_ctl::sync_stop();
    jstr(&mut env, &ok_json(serde_json::Value::Null))
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

/// This device's display name: explicit setting wins, otherwise the
/// generated adjective-noun name (same as desktop).
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDisplayName(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| {
            let s = e.settings_json();
            let name: String = serde_json::from_str::<serde_json::Value>(&s)
                .ok()
                .and_then(|v| {
                    v.pointer("/sync/device_name")
                        .and_then(|n| n.as_str())
                        .map(|s| s.to_string())
                })
                .unwrap_or_default();
            ok_json(serde_json::Value::String(crate::device::display_name(
                &name,
                e.node_id(),
            )))
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

/// Manual pairing by ID (see sync_ctl::sync_add).
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeSyncAdd(
    mut env: JNIEnv,
    _cls: JClass,
    node_id: JString,
    address: JString,
    name: JString,
) -> jstring {
    let out = (|| -> String {
        let id = get_string(&mut env, &node_id).unwrap_or_default();
        let addr = get_string(&mut env, &address).unwrap_or_default();
        let nm = get_string(&mut env, &name).unwrap_or_default();
        let dir = match engine_dir() {
            Ok(d) => d,
            Err(e) => return err_json(e),
        };
        match crate::sync_ctl::sync_add(&dir, &id, &addr, &nm) {
            Ok(s) => ok_json(serde_json::Value::String(s)),
            Err(err) => err_json(err),
        }
    })();
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
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

// -- on-device LLM (cleanup path only) ----------------------------------

/// Local-LLM status for the Settings UI:
/// `{"provider":"mediapipe","modelPath","ready","sizeBytes"}`.
/// Reads the stored `Local` entry (first enabled) or reports not-ready.
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeLocalLlmStatus(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(
        |e| {
            let s: serde_json::Value =
                serde_json::from_str(&e.settings_json()).unwrap_or(serde_json::Value::Null);
            let entry = s
                .pointer("/language_models/entries")
                .and_then(|v| v.as_array())
                .and_then(|arr| {
                    arr.iter().find(|v| {
                        v.pointer("/kind").and_then(|k| k.as_str()) == Some("Local")
                            && v.pointer("/enabled")
                                .and_then(|x| x.as_bool())
                                .unwrap_or(false)
                    })
                })
                .and_then(|v| serde_json::from_value(v.clone()).ok());
            match entry {
                Some(en) => crate::local_llm::status_json(&en, e.dir()),
                None => serde_json::json!({"provider":"mediapipe","ready":false}).to_string(),
            }
        },
        || err_json("engine not open"),
    );
    jstr(&mut env, &out)
}

/// Byte-stable cleanup prompt packing (mirrors Kotlin so both sides agree).
/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeLocalLlmPrompt(
    mut env: JNIEnv,
    _cls: JClass,
    system: JString,
    transcript: JString,
) -> jstring {
    let out = {
        let sys = get_string(&mut env, &system).unwrap_or_default();
        let raw = get_string(&mut env, &transcript).unwrap_or_default();
        crate::local_llm::build_cleanup_prompt(&sys, &raw)
    };
    jstr(&mut env, &out)
}

// -- dictionary (store-backed, desktop app_dictionary parity) --------

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetWords(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(|e| e.words_json(), || "[]".to_string());
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeAddWord(
    mut env: JNIEnv,
    _cls: JClass,
    word: JString,
) -> jstring {
    let out = {
        let w = get_string(&mut env, &word).unwrap_or_default();
        with_engine(
            |e| match e.add_word(&w) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDeleteWord(
    mut env: JNIEnv,
    _cls: JClass,
    word: JString,
) -> jstring {
    let out = {
        let w = get_string(&mut env, &word).unwrap_or_default();
        with_engine(
            |e| match e.delete_word(&w) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeGetSnippets(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    let out = with_engine(|e| e.snippets_json(), || "[]".to_string());
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativePutSnippet(
    mut env: JNIEnv,
    _cls: JClass,
    trigger: JString,
    replacement: JString,
) -> jstring {
    let out = {
        let t = get_string(&mut env, &trigger).unwrap_or_default();
        let r = get_string(&mut env, &replacement).unwrap_or_default();
        with_engine(
            |e| match e.put_snippet(&t, &r) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}

/// # Safety
/// Called from the JVM with valid JNI handles (`env` attached, object args valid local refs).
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_mywhisper_mobile_CoreBridge_nativeDeleteSnippet(
    mut env: JNIEnv,
    _cls: JClass,
    trigger: JString,
) -> jstring {
    let out = {
        let t = get_string(&mut env, &trigger).unwrap_or_default();
        with_engine(
            |e| match e.delete_snippet(&t) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        )
    };
    jstr(&mut env, &out)
}
