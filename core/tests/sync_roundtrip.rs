//! Loopback round-trip: two nodes, real TCP + Noise, real merge.
//! Exercises the verbatim-ported `net.rs`/`device.rs` paths the phone
//! uses against desktop (serve, sync_now, trust verdicts, LWW merge).

use mywhisper_core::device::DeviceIdentity;
use mywhisper_core::net;
use mywhisper_core::store::Store;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

fn setup(dir: &std::path::Path) -> (Store, DeviceIdentity) {
    std::fs::create_dir_all(dir).unwrap();
    // Host builds use SQLCipher: fixed test key.
    unsafe {
        std::env::set_var(
            "MYWHISPER_DB_KEY",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
    };
    let store = Store::open(&dir.join("state.db")).unwrap();
    let id = DeviceIdentity::load_or_create(&dir.join("device.key")).unwrap();
    (store, id)
}

#[test]
fn untrusted_dialer_gets_clean_rejection_and_pending_row() {
    let base = std::env::temp_dir().join(format!("mw-sync-reject-{}", std::process::id()));
    let (alice_store, alice_id) = setup(&base.join("alice"));
    let (_bob_store, bob_id) = setup(&base.join("bob"));

    let listener = net::listen(44974).unwrap().unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let s = stop.clone();
    let db = base.join("alice").join("state.db");
    let creds = alice_id.creds();
    std::thread::spawn(move || net::serve(listener, db, creds, "alice".into(), vec![], &s));

    let bob_db = base.join("bob").join("state.db");
    let err = net::sync_now(
        &["127.0.0.1:44974".to_string()],
        &bob_db,
        &bob_id.creds(),
        "bob",
        &alice_id.node_id,
    )
    .unwrap_err();
    assert!(err.contains("not trusted"), "got: {err}");
    // Responder recorded the stranger as pending, merged nothing.
    let peer = alice_store
        .get_peer(&bob_id.node_id)
        .unwrap()
        .expect("pending row");
    assert!(!peer.trusted && peer.pending);
    stop.store(true, Ordering::Relaxed);
}

#[test]
fn unresolvable_first_address_does_not_abort_round() {
    let base = std::env::temp_dir().join(format!("mw-sync-skip-{}", std::process::id()));
    let (alice_store, alice_id) = setup(&base.join("alice"));
    let (_bob_store, bob_id) = setup(&base.join("bob"));

    alice_store
        .upsert_peer(&bob_id.node_id, "bob", "127.0.0.1:44976", true)
        .unwrap();
    let listener = net::listen(44976).unwrap().unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let s = stop.clone();
    let db = base.join("alice").join("state.db");
    let creds = alice_id.creds();
    std::thread::spawn(move || net::serve(listener, db, creds, "alice".into(), vec![], &s));
    std::thread::sleep(std::time::Duration::from_millis(200));

    // Garbage first: must be skipped, live second tried.
    let bob_db = base.join("bob").join("state.db");
    let report = net::sync_now(
        &[
            "no-such-host.invalid:9".to_string(),
            "127.0.0.1:44976".to_string(),
        ],
        &bob_db,
        &bob_id.creds(),
        "bob",
        &alice_id.node_id,
    )
    .expect("round survives a bad first address");
    assert_eq!(report.peer_name, "alice");
    stop.store(true, Ordering::Relaxed);
}

#[test]
fn trusted_peers_merge_words_and_history_both_ways() {
    let base = std::env::temp_dir().join(format!("mw-sync-merge-{}", std::process::id()));
    let (alice_store, alice_id) = setup(&base.join("alice"));
    let (bob_store, bob_id) = setup(&base.join("bob"));

    alice_store
        .upsert_word("kwyjibo", &alice_id.node_id)
        .unwrap();
    alice_store
        .add_history("raw a", "Hello from alice", 3, 5, &alice_id.node_id)
        .unwrap();
    // Mutual trust (as if both sides accepted pairing requests).
    alice_store
        .upsert_peer(&bob_id.node_id, "bob", "127.0.0.1:44975", true)
        .unwrap();
    bob_store
        .upsert_peer(&alice_id.node_id, "alice", "127.0.0.1:44975", true)
        .unwrap();

    let listener = net::listen(44975).unwrap().unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let s = stop.clone();
    let db = base.join("alice").join("state.db");
    let creds = alice_id.creds();
    std::thread::spawn(move || net::serve(listener, db, creds, "alice".into(), vec![], &s));
    std::thread::sleep(std::time::Duration::from_millis(200));

    let bob_db = base.join("bob").join("state.db");
    let report = net::sync_now(
        &["127.0.0.1:44975".to_string()],
        &bob_db,
        &bob_id.creds(),
        "bob",
        &alice_id.node_id,
    )
    .expect("sync round");
    assert_eq!(report.words_in, 1);
    assert_eq!(report.history_in, 1);
    assert_eq!(report.peer_name, "alice");

    // Bob merged alice's word + history.
    assert!(bob_store
        .load_words()
        .unwrap()
        .contains(&"kwyjibo".to_string()));
    let hist = bob_store.load_history(10, false).unwrap();
    assert!(hist.iter().any(|h| h.text == "Hello from alice"));

    // Alice got nothing new (bob had nothing) but recorded the sync.
    let alice_peer = alice_store.get_peer(&bob_id.node_id).unwrap().unwrap();
    assert!(alice_peer.last_sync > 0);
    stop.store(true, Ordering::Relaxed);
}
