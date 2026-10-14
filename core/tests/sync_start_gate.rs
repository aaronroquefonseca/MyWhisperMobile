//! sync_start strict gate: a missing or unreadable settings blob must
//! refuse, even though load_settings() defaults report enabled.
//!
//! Separate binary from the lib tests so the fixed test key in the
//! process env cannot race keyring-backed tests (see tests/
//! sync_roundtrip.rs, same pattern).

use mywhisper_core::store::Store;
use mywhisper_core::sync_ctl;
use std::sync::atomic::{AtomicU64, Ordering};

static N: AtomicU64 = AtomicU64::new(0);

fn fresh_dir(tag: &str) -> std::path::PathBuf {
    unsafe {
        std::env::set_var(
            "MYWHISPER_DB_KEY",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
    };
    let dir = std::env::temp_dir().join(format!(
        "mw-sync-start-gate-{}-{}-{tag}",
        std::process::id(),
        N.fetch_add(1, Ordering::SeqCst)
    ));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn sync_start_refuses_without_an_explicit_settings_blob() {
    let dir = fresh_dir("no-blob");
    // Fresh database, settings never saved: load_settings() reports
    // enabled via defaults, but the strict gate must refuse.
    let store = Store::open(&dir.join("state.db")).unwrap();
    assert!(store.load_settings().sync.enabled);
    assert!(!store.sync_enabled_explicit());
    drop(store);
    let err = sync_ctl::sync_start(dir.clone()).unwrap_err();
    assert!(
        err.contains("disabled"),
        "fresh install must not start sync, got: {err}"
    );
    assert!(sync_ctl::sync_status_json(&dir).contains("false"));
    let _ = std::fs::remove_dir_all(&dir);
}
