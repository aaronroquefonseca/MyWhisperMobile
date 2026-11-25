//! C-ABI bridge for Apple targets (`capple`: C-Apple).
//!
//! Same engine, same `{ok, data?, error?}` envelope semantics as the
//! JNI bridge in `lib.rs` — different shell: C strings over
//! `extern "C"` instead of JNI handles. Swift (`ios/CoreBridge.swift`)
//! calls these through the xcframework built by
//! `scripts/build-core-ios.sh`.
//!
//! Raw-JSON getters (`get_settings`, `get_history`, `get_words`,
//! `get_snippets`) mirror their JNI twins exactly: unwrapped payload,
//! no envelope. Everything else returns the envelope.
//!
//! Every returned pointer is an owned NUL-terminated C string: pass it
//! back to `mywhisper_free_string` when done. Never free a NULL, never
//! use a pointer after freeing it.

use crate::engine::Engine;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

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

fn ok_json(value: serde_json::Value) -> String {
    serde_json::json!({"ok": true, "data": value}).to_string()
}

fn err_json(e: impl std::fmt::Display) -> String {
    serde_json::json!({"ok": false, "error": e.to_string()}).to_string()
}

fn string_to_c(s: &str) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Release a string returned by any `mywhisper_*` function.
/// # Safety
/// `s` must be a pointer previously returned by this bridge (or NULL,
/// which is a no-op). Call at most once per pointer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_free_string(s: *mut c_char) {
    if !s.is_null() {
        unsafe {
            drop(CString::from_raw(s));
        }
    }
}

/// # Safety
/// `p` must be a valid NUL-terminated C string for reads, or NULL
/// (which fails closed with an error, never UB).
unsafe fn cstr_to_string(p: *const c_char) -> Result<String, String> {
    if p.is_null() {
        return Err("null string pointer".to_string());
    }
    unsafe { CStr::from_ptr(p) }
        .to_str()
        .map(|s| s.to_string())
        .map_err(|e| format!("invalid utf-8: {e}"))
}

// -- internal (host-testable) entry points -------------------------------

pub fn core_version_rs() -> String {
    crate::CORE_VERSION.to_string()
}

pub fn engine_open_rs(dir: &str, key_hex: &str) -> String {
    let out = (|| -> Result<String, String> {
        if key_hex.trim().len() != 64 {
            return Err("db key must be 64 hex chars".to_string());
        }
        let eng = Engine::open(PathBuf::from(dir), key_hex.trim())?;
        if ENGINE.get().is_none() {
            let _ = ENGINE.set(Mutex::new(eng));
        } else if let Ok(mut e) = ENGINE.get().unwrap().lock() {
            *e = eng;
        }
        Ok(ok_json(serde_json::json!({"version": crate::CORE_VERSION})))
    })();
    out.unwrap_or_else(err_json)
}

pub fn settings_get_rs() -> String {
    with_engine(|e| e.settings_json(), || err_json("engine not open"))
}

pub fn settings_put_rs(json: &str) -> String {
    with_engine(
        |e| match e.put_settings(json) {
            Ok(()) => ok_json(serde_json::Value::Null),
            Err(err) => err_json(err),
        },
        || err_json("engine not open"),
    )
}

pub fn utterance_start_rs(sample_rate: u32) -> String {
    with_engine(
        |e| {
            e.start_utterance(sample_rate);
            ok_json(serde_json::Value::Null)
        },
        || err_json("engine not open"),
    )
}

pub fn utterance_cancel_rs() -> String {
    match ENGINE.get().map(crate::engine::cancel_unlocked) {
        Some(Ok(secs)) => ok_json(serde_json::json!({"durationSecs": secs})),
        Some(Err(err)) => err_json(err),
        None => err_json("engine not open"),
    }
}

pub fn utterance_finish_rs() -> String {
    match ENGINE.get().map(|m| {
        crate::engine::finish_unlocked(m, |e| {
            e.begin_finish(
                crate::engine::current_utterance(),
                crate::server_stt::ChainPart::All,
            )
        })
    }) {
        Some(Ok(o)) => ok_json(serde_json::json!({
            "text": o.text, "raw": o.raw, "engine": o.engine,
            "durationSecs": o.duration_secs, "words": o.words,
        })),
        Some(Err(err)) => err_json(err),
        None => err_json("engine not open"),
    }
}

pub fn history_get_rs(limit: usize) -> String {
    with_engine(|e| e.history_json(limit.max(1)), || "[]".to_string())
}

pub fn words_get_rs() -> String {
    with_engine(|e| e.words_json(), || "[]".to_string())
}

pub fn snippets_get_rs() -> String {
    with_engine(|e| e.snippets_json(), || "[]".to_string())
}

// -- C ABI ---------------------------------------------------------------
// # Safety on all below: `*const c_char` args must be valid NUL-terminated
// UTF-8 (or NULL, which fails with an error envelope, never UB beyond
// the documented read). Returned pointers are owned (see module docs).

/// Plain version string (`CARGO_PKG_VERSION`), not enveloped.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_core_version() -> *mut c_char {
    string_to_c(&core_version_rs())
}

/// Open (or create) `state.db` under `files_dir` with the Keychain-held
/// `db_key_hex` (64 hex chars). Must be called before any other method.
/// Returns the `{ok, data: {version}}` envelope.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_engine_open(
    files_dir: *const c_char,
    db_key_hex: *const c_char,
) -> *mut c_char {
    let out = match (unsafe { cstr_to_string(files_dir) }, unsafe {
        cstr_to_string(db_key_hex)
    }) {
        (Ok(dir), Ok(key)) => engine_open_rs(&dir, &key),
        (Err(e), _) | (_, Err(e)) => err_json(e),
    };
    string_to_c(&out)
}

/// Raw settings JSON (no envelope), same bytes as JNI `nativeGetSettings`.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_get_settings() -> *mut c_char {
    string_to_c(&settings_get_rs())
}

/// Envelope (`data: null` on success).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_put_settings(json: *const c_char) -> *mut c_char {
    let out = match unsafe { cstr_to_string(json) } {
        Ok(body) => settings_put_rs(&body),
        Err(e) => err_json(e),
    };
    string_to_c(&out)
}

/// Envelope (`data: null`). Rate in Hz (16000 typical).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_start_utterance(sample_rate: u32) -> *mut c_char {
    string_to_c(&utterance_start_rs(sample_rate))
}

/// Copy `len` int16 samples into the in-flight utterance. Envelope.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_push_pcm(data: *const i16, len: usize) -> *mut c_char {
    let out = if data.is_null() {
        if len == 0 {
            with_engine(
                |e| {
                    e.push_pcm(&[]);
                    ok_json(serde_json::Value::Null)
                },
                || err_json("engine not open"),
            )
        } else {
            err_json("null pcm pointer")
        }
    } else {
        let samples = unsafe { std::slice::from_raw_parts(data, len) };
        with_engine(
            |e| {
                e.push_pcm(samples);
                ok_json(serde_json::Value::Null)
            },
            || err_json("engine not open"),
        )
    };
    string_to_c(&out)
}

/// Block while the server chain transcribes (call off the main thread).
/// Returns `{ok, data: {text, raw, engine, durationSecs, words}}`.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_finish_utterance() -> *mut c_char {
    string_to_c(&utterance_finish_rs())
}

/// Envelope (`data: {durationSecs}`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_cancel_utterance() -> *mut c_char {
    string_to_c(&utterance_cancel_rs())
}

/// Raw history JSON array (no envelope), `limit` clamped to >= 1.
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_get_history(limit: c_int) -> *mut c_char {
    string_to_c(&history_get_rs(limit.max(1) as usize))
}

/// Envelope (`data: {removed}`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_clear_history() -> *mut c_char {
    let out = with_engine(
        |e| match e.clear_history() {
            Ok(n) => ok_json(serde_json::json!({"removed": n})),
            Err(err) => err_json(err),
        },
        || err_json("engine not open"),
    );
    string_to_c(&out)
}

/// Envelope (`data: null`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_delete_history(uuid: *const c_char) -> *mut c_char {
    let out = match unsafe { cstr_to_string(uuid) } {
        Ok(id) => with_engine(
            |e| match e.delete_history(&id) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        ),
        Err(e) => err_json(e),
    };
    string_to_c(&out)
}

/// Raw words JSON array (no envelope).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_get_words() -> *mut c_char {
    string_to_c(&words_get_rs())
}

/// Envelope (`data: null`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_add_word(word: *const c_char) -> *mut c_char {
    let out = match unsafe { cstr_to_string(word) } {
        Ok(w) => with_engine(
            |e| match e.add_word(&w) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        ),
        Err(e) => err_json(e),
    };
    string_to_c(&out)
}

/// Envelope (`data: null`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_delete_word(word: *const c_char) -> *mut c_char {
    let out = match unsafe { cstr_to_string(word) } {
        Ok(w) => with_engine(
            |e| match e.delete_word(&w) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        ),
        Err(e) => err_json(e),
    };
    string_to_c(&out)
}

/// Raw snippets JSON array (no envelope).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_get_snippets() -> *mut c_char {
    string_to_c(&snippets_get_rs())
}

/// Envelope (`data: null`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_put_snippet(
    trigger: *const c_char,
    replacement: *const c_char,
) -> *mut c_char {
    let out = match (unsafe { cstr_to_string(trigger) }, unsafe {
        cstr_to_string(replacement)
    }) {
        (Ok(t), Ok(r)) => with_engine(
            |e| match e.put_snippet(&t, &r) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        ),
        (Err(e), _) | (_, Err(e)) => err_json(e),
    };
    string_to_c(&out)
}

/// Envelope (`data: null`).
/// # Safety
/// Pointer args must be valid for reads (NUL-terminated UTF-8 for
/// strings; `len` samples for PCM) or NULL (fails closed with an
/// error envelope). The returned C string is owned — release it with
/// `mywhisper_free_string`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mywhisper_delete_snippet(trigger: *const c_char) -> *mut c_char {
    let out = match unsafe { cstr_to_string(trigger) } {
        Ok(t) => with_engine(
            |e| match e.delete_snippet(&t) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        ),
        Err(e) => err_json(e),
    };
    string_to_c(&out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static CAPPLE_TEST_SEQ: AtomicU64 = AtomicU64::new(0);
    static CAPPLE_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn tmp_dir() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "mywhisper-capple-test-{}-{}",
            std::process::id(),
            CAPPLE_TEST_SEQ.fetch_add(1, Ordering::SeqCst)
        ));
        let _ = std::fs::remove_dir_all(&path);
        path
    }

    fn open_test_engine() -> PathBuf {
        let dir = tmp_dir();
        let out = engine_open_rs(dir.to_str().unwrap(), &"ab".repeat(32));
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v.pointer("/ok"), Some(&serde_json::Value::Bool(true)));
        assert_eq!(
            v.pointer("/data/version").and_then(|v| v.as_str()),
            Some(crate::CORE_VERSION)
        );
        dir
    }

    fn data_of(raw: &str) -> serde_json::Value {
        let v: serde_json::Value = serde_json::from_str(raw).unwrap();
        assert_eq!(v.pointer("/ok"), Some(&serde_json::Value::Bool(true)));
        v.pointer("/data").cloned().unwrap()
    }

    #[test]
    fn open_get_put_roundtrip_is_byte_identical() {
        let _guard = CAPPLE_TEST_LOCK.lock().unwrap();
        open_test_engine();
        // Seed schema parity: the same `AppSettings` struct the JNI
        // bridge serializes, so these bytes match Android's
        // `nativeGetSettings` for a fresh install.
        let first = settings_get_rs();
        let v: serde_json::Value = serde_json::from_str(&first).unwrap();
        for key in [
            "preferences",
            "hotkeys",
            "stt",
            "language_models",
            "privacy",
            "system",
            "dictionary",
            "sync",
        ] {
            assert!(v.pointer(&format!("/{key}")).is_some(), "missing {key}");
        }
        // Idempotent put: storing what we loaded reads back byte-for-byte.
        let put = settings_put_rs(&first);
        assert_eq!(data_of(&put), serde_json::Value::Null);
        assert_eq!(settings_get_rs(), first);
    }

    #[test]
    fn settings_mutation_persists() {
        let _guard = CAPPLE_TEST_LOCK.lock().unwrap();
        open_test_engine();
        let mut v: serde_json::Value = serde_json::from_str(&settings_get_rs()).unwrap();
        v["privacy"]["history_enabled"] = serde_json::Value::Bool(false);
        let body = serde_json::to_string(&v).unwrap();
        assert_eq!(data_of(&settings_put_rs(&body)), serde_json::Value::Null);
        let back: serde_json::Value = serde_json::from_str(&settings_get_rs()).unwrap();
        assert_eq!(back["privacy"]["history_enabled"], false);
    }

    #[test]
    fn open_rejects_bad_key() {
        let _guard = CAPPLE_TEST_LOCK.lock().unwrap();
        let dir = tmp_dir();
        let out = engine_open_rs(dir.to_str().unwrap(), "short");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v.pointer("/ok"), Some(&serde_json::Value::Bool(false)));
    }

    #[test]
    fn history_words_snippets_passthrough() {
        let _guard = CAPPLE_TEST_LOCK.lock().unwrap();
        open_test_engine();
        assert_eq!(history_get_rs(10), "[]");
        assert_eq!(words_get_rs(), "[]");
        assert_eq!(snippets_get_rs(), "[]");
        // Envelope shape on dictionary writes (data: null on success).
        let word = format!("capple{}", CAPPLE_TEST_SEQ.fetch_add(1, Ordering::SeqCst));
        assert_eq!(words_get_rs_after_add(&word), serde_json::Value::Null);
        assert!(words_get_rs().contains(&word));
        let trigger = format!("trig{}", CAPPLE_TEST_SEQ.fetch_add(1, Ordering::SeqCst));
        let put = with_engine(
            |e| match e.put_snippet(&trigger, "Replacement") {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        );
        assert_eq!(data_of(&put), serde_json::Value::Null);
        assert!(snippets_get_rs().contains(&trigger));
    }

    fn words_get_rs_after_add(word: &str) -> serde_json::Value {
        let out = with_engine(
            |e| match e.add_word(word) {
                Ok(()) => ok_json(serde_json::Value::Null),
                Err(err) => err_json(err),
            },
            || err_json("engine not open"),
        );
        data_of(&out)
    }

    #[test]
    fn c_abi_open_get_put_roundtrip() {
        let _guard = CAPPLE_TEST_LOCK.lock().unwrap();
        let dir = tmp_dir();
        let cdir = CString::new(dir.to_str().unwrap()).unwrap();
        let ckey = CString::new("ab".repeat(32)).unwrap();
        let open_ptr = unsafe { mywhisper_engine_open(cdir.as_ptr(), ckey.as_ptr()) };
        assert!(!open_ptr.is_null());
        let open: String = unsafe {
            let s = CStr::from_ptr(open_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(open_ptr);
            s
        };
        assert_eq!(data_of(&open)["version"], crate::CORE_VERSION);
        // NULL key fails closed with an error envelope, never UB.
        let null_ptr = unsafe { mywhisper_engine_open(cdir.as_ptr(), std::ptr::null()) };
        let err: String = unsafe {
            let s = CStr::from_ptr(null_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(null_ptr);
            s
        };
        let v: serde_json::Value = serde_json::from_str(&err).unwrap();
        assert_eq!(v.pointer("/ok"), Some(&serde_json::Value::Bool(false)));
        // Raw get/put through the C ABI (settings getter is unwrapped).
        let get_ptr = unsafe { mywhisper_get_settings() };
        let first: String = unsafe {
            let s = CStr::from_ptr(get_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(get_ptr);
            s
        };
        assert!(first.contains("\"privacy\""));
        let cfirst = CString::new(first.clone()).unwrap();
        let put_ptr = unsafe { mywhisper_put_settings(cfirst.as_ptr()) };
        let put: String = unsafe {
            let s = CStr::from_ptr(put_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(put_ptr);
            s
        };
        assert_eq!(data_of(&put), serde_json::Value::Null);
        let get2_ptr = unsafe { mywhisper_get_settings() };
        let second: String = unsafe {
            let s = CStr::from_ptr(get2_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(get2_ptr);
            s
        };
        assert_eq!(second, first);
        // Version + free-NULL are safe no-ops shapes.
        let ver_ptr = unsafe { mywhisper_core_version() };
        let ver: String = unsafe {
            let s = CStr::from_ptr(ver_ptr).to_string_lossy().into_owned();
            mywhisper_free_string(ver_ptr);
            s
        };
        assert_eq!(ver, crate::CORE_VERSION);
        unsafe { mywhisper_free_string(std::ptr::null_mut()) };
    }
}
