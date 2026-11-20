//! Sync engine manager: listener + mDNS + periodic loops.
//!
//! Thin lifecycle wrapper over the verbatim-ported `net.rs` (same
//! Noise_XX protocol as desktop, so phone and desktop sync directly).
//! One manager per process; the config app and the IME each run their
//! own (same `state.db`, same `device.key` identity).
//!
//! Android note: mDNS needs a MulticastLock — the Kotlin side
//! acquires it before `nativeSyncStart` (see Sync UI).

use crate::device::DeviceIdentity;
use crate::net;
use crate::store::Store;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

pub struct SyncManager {
    creds: crate::device::SyncCreds,
    db_path: PathBuf,
    stop: Arc<AtomicBool>,
    mdns: Option<net::MdnsHandles>,
    discovered: Arc<Mutex<HashMap<String, net::Discovered>>>,
    port: u16,
    reports: Arc<Mutex<Vec<String>>>,
    /// Joined on stop, so a restart can bind the same port again.
    listener: Option<std::thread::JoinHandle<()>>,
}

static SYNC: OnceLock<Mutex<Option<SyncManager>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<SyncManager>> {
    SYNC.get_or_init(|| Mutex::new(None))
}

fn push_report(reports: &Arc<Mutex<Vec<String>>>, line: String) {
    let mut r = reports.lock().unwrap();
    r.push(line);
    while r.len() > 20 {
        r.remove(0);
    }
}

/// Pre-dial authorization gate (desktop #18 `require_sync_authorized`
/// at the control layer): sync must be explicitly enabled in a fresh
/// settings read, and the peer must be trusted and unrevoked. The
/// responder re-checks inside its commit transaction — this gate only
/// fails fast locally so a revoked/disabled round never puts payload
/// on the wire. No permissive defaults: an unreadable settings blob
/// refuses.
fn require_dial_authorized(store: &Store, node_id: &str) -> Result<(), String> {
    // Strict parse (mirrors require_sync_authorized): a missing or
    // unreadable settings blob refuses. load_settings() defaults would
    // fail this gate open on fresh installs.
    if !store.sync_enabled_explicit() {
        return Err("sync is disabled in settings".to_string());
    }
    if store
        .is_revoked(node_id)
        .map_err(|e| format!("revocation lookup: {e}"))?
    {
        return Err("peer revoked".to_string());
    }
    let trusted = store
        .get_peer(node_id)
        .map_err(|e| format!("peer lookup: {e}"))?
        .map(|p| p.trusted)
        .unwrap_or(false);
    if !trusted {
        return Err("peer is not trusted".to_string());
    }
    Ok(())
}

/// Start listener + mDNS + loops from the stored sync settings.
/// Idempotent: stops the previous manager first.
pub fn sync_start(dir: PathBuf) -> Result<String, String> {
    sync_stop();
    let db_path = dir.join("state.db");
    let store = Store::open(&db_path).map_err(|e| format!("open db: {e}"))?;
    // Strict gate (same fail-open class as the dial gate): a missing or
    // unreadable settings blob refuses. load_settings() defaults would
    // start sync on a fresh install that never opted in.
    if !store.sync_enabled_explicit() {
        return Err("sync is disabled in settings".to_string());
    }
    let settings = store.load_settings();
    let identity = DeviceIdentity::load_or_create(&dir.join("device.key"))
        .map_err(|e| format!("identity: {e}"))?;
    let creds = identity.creds();
    let name = if settings.sync.device_name.trim().is_empty() {
        crate::device::default_device_name(&identity.node_id)
    } else {
        settings.sync.device_name.clone()
    };
    let port = settings.sync.listen_port;
    let stop = Arc::new(AtomicBool::new(false));
    let discovered: Arc<Mutex<HashMap<String, net::Discovered>>> =
        Arc::new(Mutex::new(HashMap::new()));
    let reports: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));

    // Listener (port 0 = disabled).
    let listener = net::listen(port).map_err(|e| format!("listen {port}: {e}"))?;
    let listener = if let Some(l) = listener {
        let db = db_path.clone();
        let c = creds.clone();
        let n = name.clone();
        let s = stop.clone();
        let r = reports.clone();
        push_report(&reports, format!("listening on port {port}"));
        Some(std::thread::spawn(move || {
            net::serve(l, db, c, n, &s);
            push_report(&r, "listener stopped".to_string());
        }))
    } else {
        push_report(&reports, "listening disabled (port 0)".to_string());
        None
    };

    // mDNS advertise + browse.
    match net::start_mdns(
        &identity.node_id,
        &name,
        port,
        discovered.clone(),
        settings.sync.advertise,
    ) {
        Ok(handles) => {
            push_report(&reports, "mDNS discovery on".to_string());
            let mgr = SyncManager {
                creds,
                db_path,
                stop,
                mdns: Some(handles),
                discovered,
                port,
                reports,
                listener,
            };
            mgr.spawn_loops(&name);
            let summary = format!("sync on (port {port})");
            *slot().lock().unwrap() = Some(mgr);
            Ok(summary)
        }
        Err(e) => {
            stop.store(true, Ordering::Relaxed);
            if let Some(listener) = listener {
                let _ = listener.join();
            }
            Err(format!("mDNS: {e}"))
        }
    }
}

impl SyncManager {
    fn spawn_loops(&self, name: &str) {
        // Periodic trusted-peer rounds (60s, desktop cadence).
        {
            let db = self.db_path.clone();
            let c = self.creds.clone();
            let s = self.stop.clone();
            let r = self.reports.clone();
            std::thread::spawn(move || {
                net::auto_sync_loop(db, c, s.clone());
                let _ = s;
                push_report(&r, "auto-sync stopped".to_string());
            });
            let _ = name;
        }
        // Sync when a trusted peer appears on the LAN.
        net::watch_discovered(
            self.db_path.clone(),
            self.creds.clone(),
            self.discovered.clone(),
            self.stop.clone(),
        );
        // Catch-up round shortly after start.
        net::sync_on_startup(self.db_path.clone(), self.creds.clone());
    }
}

pub fn sync_stop() {
    if let Some(m) = slot().lock().unwrap().take() {
        m.stop.store(true, Ordering::Relaxed);
        if let Some(h) = m.mdns {
            h.shutdown();
        }
        // The accept loop polls `stop` every 100 ms; wait for it to drop
        // the socket so an immediate restart can rebind (desktop R42).
        if let Some(listener) = m.listener {
            let _ = listener.join();
        }
    }
}

pub fn sync_status_json(dir: &std::path::Path) -> String {
    let guard = slot().lock().unwrap();
    let running = guard.is_some();
    let (node_id, port, lan) = if let Some(m) = guard.as_ref() {
        (
            m.creds.node_id.clone(),
            m.port,
            net::lan_ip().map(|ip| ip.to_string()).unwrap_or_default(),
        )
    } else {
        let node_id = DeviceIdentity::load_or_create(&dir.join("device.key"))
            .map(|i| i.node_id)
            .unwrap_or_default();
        (node_id, 0, String::new())
    };
    let reports: Vec<String> = guard
        .as_ref()
        .map(|m| m.reports.lock().unwrap().clone())
        .unwrap_or_default();
    serde_json::json!({
        "running": running,
        "nodeId": node_id,
        "port": port,
        "lanIp": lan,
        "reports": reports,
    })
    .to_string()
}

/// Peers from the store annotated with live discovery info.
pub fn sync_peers_json(dir: &std::path::Path) -> String {
    let db_path = dir.join("state.db");
    let stored = Store::open(&db_path)
        .ok()
        .and_then(|s| s.list_peers().ok())
        .unwrap_or_default();
    let discovered: HashMap<String, net::Discovered> = slot()
        .lock()
        .unwrap()
        .as_ref()
        .map(|m| m.discovered.lock().unwrap().clone())
        .unwrap_or_default();
    let mut out = Vec::new();
    for p in stored {
        let disc = discovered.get(&p.node_id);
        out.push(serde_json::json!({
            "nodeId": p.node_id,
            "name": if p.name.is_empty() { short_id(&p.node_id) } else { p.name.clone() },
            "addresses": p.addresses,
            "trusted": p.trusted,
            "pending": p.pending,
            "outgoing": p.outgoing,
            "introducer": p.introducer,
            "lastSeen": p.last_seen,
            "lastSync": p.last_sync,
            "discovered": disc.is_some(),
            "discoveredName": disc.map(|d| d.name.clone()).unwrap_or_default(),
            "discoveredAddrs": disc.map(|d| d.addresses.clone()).unwrap_or_default(),
        }));
    }
    // Discovered but unknown: pairing candidates.
    for d in discovered.values() {
        if !out.iter().any(|v| v["nodeId"] == d.node_id.as_str()) {
            out.push(serde_json::json!({
                "nodeId": d.node_id,
                "name": if d.name.is_empty() { short_id(&d.node_id) } else { d.name.clone() },
                "addresses": [],
                "trusted": false,
                "pending": false,
                "outgoing": false,
                "introducer": false,
                "lastSeen": 0,
                "lastSync": 0,
                "discovered": true,
                "discoveredName": d.name,
                "discoveredAddrs": d.addresses,
            }));
        }
    }
    serde_json::to_string(&out).unwrap_or_else(|_| "[]".to_string())
}

fn short_id(id: &str) -> String {
    // Byte slicing is safe only on canonical (ASCII hex) ids.
    if crate::store::valid_node_id(id) {
        format!("{}…{}", &id[..8], &id[id.len() - 4..])
    } else {
        id.to_string()
    }
}

/// Accept (trust + introducer, then sync), reject, or withdraw a peer.
/// Unknown-but-discovered nodes are recorded first so the accept
/// path has a row to promote (mirrors desktop trust flow).
/// Cancelling our own sent request (outgoing, no incoming pending)
/// withdraws it without revoking, so the id stays pairable; rejecting
/// an incoming request revokes durably.
pub fn sync_set_trust(
    dir: &std::path::Path,
    node_id: &str,
    accept: bool,
) -> Result<String, String> {
    let db_path = dir.join("state.db");
    let store = Store::open(&db_path).map_err(|e| format!("open db: {e}"))?;
    if store
        .get_peer(node_id)
        .map_err(|e| format!("peer lookup: {e}"))?
        .is_none()
    {
        // Park discovered unknowns so decide_request can promote them.
        let known: Option<net::Discovered> = slot()
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|m| m.discovered.lock().unwrap().get(node_id).cloned());
        if let Some(d) = known {
            store
                .record_seen(&d.node_id, &d.name, &d.addresses)
                .map_err(|e| format!("record contact: {e}"))?;
        } else {
            return Err("unknown peer (not discovered)".to_string());
        }
    } else if !accept {
        let peer = store
            .get_peer(node_id)
            .map_err(|e| format!("peer lookup: {e}"))?
            .ok_or_else(|| "unknown peer".to_string())?;
        if peer.outgoing && !peer.pending && !peer.trusted {
            store
                .withdraw_outgoing(node_id)
                .map_err(|e| format!("withdraw request: {e}"))?;
            return Ok("request withdrawn".to_string());
        }
    }
    store
        .decide_request(node_id, accept)
        .map_err(|e| format!("trust update: {e}"))?;
    if accept {
        let db = db_path.clone();
        let creds = slot().lock().unwrap().as_ref().map(|m| m.creds.clone());
        if let Some(c) = creds {
            std::thread::spawn(move || {
                net::sync_after_change(db, c);
            });
            return Ok("trusted; syncing now".to_string());
        }
        return Ok("trusted".to_string());
    }
    Ok("rejected".to_string())
}

/// Manual pairing by ID (mirrors desktop Add device): park the row as
/// OUR outgoing request (never as an incoming pending row), dial once.
/// The expected outcome is the remote's "not trusted" verdict — that
/// verdict IS the delivered pairing request (the remote records us as
/// pending). Reported as such so the UI can say "accept it on the
/// other device".
pub fn sync_add(
    dir: &std::path::Path,
    node_id: &str,
    address: &str,
    name: &str,
) -> Result<String, String> {
    let node_id = node_id.trim().to_lowercase();
    if node_id.len() != 64 || !node_id.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("enter the other device's full 64-character hexadecimal ID".to_string());
    }
    // Never pair with self: an outgoing row to our own id is a nonsense
    // WAITING entry that can never complete.
    let own = DeviceIdentity::load_or_create(&dir.join("device.key"))
        .map_err(|e| format!("identity: {e}"))?;
    if node_id == own.node_id {
        return Err("that is this device's own ID — pair with a different device".to_string());
    }
    let db_path = dir.join("state.db");
    let store = Store::open(&db_path).map_err(|e| format!("open db: {e}"))?;
    // Pairing dials an untrusted peer by design (the dial IS the
    // request), but a revoked id is refused before any contact.
    if store
        .is_revoked(&node_id)
        .map_err(|e| format!("revocation lookup: {e}"))?
    {
        return Err("peer revoked".to_string());
    }
    let mut addrs = Vec::new();
    if !address.trim().is_empty() {
        addrs.push(address.trim().to_string());
    }
    store
        .record_outgoing(&node_id, name.trim(), &addrs, &own.node_id)
        .map_err(|e| format!("record contact: {e}"))?;
    let settings = store.load_settings();
    let creds = slot()
        .lock()
        .unwrap()
        .as_ref()
        .map(|m| m.creds.clone())
        .ok_or_else(|| "sync is not running".to_string())?;
    let own_name = if settings.sync.device_name.trim().is_empty() {
        creds.node_id.clone()
    } else {
        settings.sync.device_name.clone()
    };
    match net::sync_now(
        &addrs,
        &db_path,
        &creds,
        &own_name,
        &node_id,
        &net::own_listen_addrs(settings.sync.listen_port),
    ) {
        Ok(r) => Ok(format!("synced {}: {} words in", r.peer_name, r.words_in)),
        Err(e) if e.contains("not trusted") => {
            Ok("pairing request sent — accept it on the other device, then sync".to_string())
        }
        Err(_) if addrs.is_empty() => {
            Ok("saved — syncs automatically once the device is discovered on the LAN".to_string())
        }
        Err(e) => Err(e),
    }
}
/// One manual round with a single peer (tries stored + discovered
/// addresses).
pub fn sync_peer_now(dir: &std::path::Path, node_id: &str) -> Result<String, String> {
    let db_path = dir.join("state.db");
    let store = Store::open(&db_path).map_err(|e| format!("open db: {e}"))?;
    let settings = store.load_settings();
    // Fail fast on disabled sync, revoked or untrusted peers before
    // any payload moves (the responder re-checks authoritatively).
    require_dial_authorized(&store, node_id)?;
    let peer = store
        .get_peer(node_id)
        .map_err(|e| format!("peer lookup: {e}"))?
        .ok_or_else(|| "unknown peer".to_string())?;
    // Fresh observations first: a stored address may be stale
    // (peer rebooted onto a new port), discovered ones are live.
    let mut addrs: Vec<String> = Vec::new();
    if let Some(d) = slot()
        .lock()
        .unwrap()
        .as_ref()
        .and_then(|m| m.discovered.lock().unwrap().get(node_id).cloned())
    {
        for a in d.addresses {
            if !addrs.contains(&a) {
                addrs.push(a);
            }
        }
    }
    for a in peer.dial_targets() {
        if !addrs.contains(&a) {
            addrs.push(a);
        }
    }
    let creds = slot()
        .lock()
        .unwrap()
        .as_ref()
        .map(|m| m.creds.clone())
        .ok_or_else(|| "sync is not running".to_string())?;
    let name = if settings.sync.device_name.trim().is_empty() {
        creds.node_id.clone()
    } else {
        settings.sync.device_name.clone()
    };
    let display = if peer.name.is_empty() {
        short_id(node_id)
    } else {
        peer.name.clone()
    };
    let r = net::sync_now(
        &addrs,
        &db_path,
        &creds,
        &name,
        node_id,
        &net::own_listen_addrs(settings.sync.listen_port),
    )
    .map_err(|e| format!("{display}: {e} — is the device online with sync running?"))?;
    Ok(format!(
        "synced {}: {} words, {} snippets, {} history in, {} introduced",
        r.peer_name, r.words_in, r.snippets_in, r.history_in, r.introduced
    ))
}

/// Manual round with all trusted peers.
pub fn sync_all_now(dir: &std::path::Path) -> Vec<String> {
    let db_path = dir.join("state.db");
    let creds = match slot().lock().unwrap().as_ref().map(|m| m.creds.clone()) {
        Some(c) => c,
        None => return vec!["sync is not running".to_string()],
    };
    net::sync_all_trusted(&db_path, &creds)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static N: AtomicU64 = AtomicU64::new(0);

    fn temp_dir() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "mywhisper-syncctl-test-{}-{}",
            std::process::id(),
            N.fetch_add(1, Ordering::SeqCst)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Pairing with our own id is refused before anything parks: no
    /// nonsense WAITING row, whatever the spelling (case/padding).
    #[test]
    fn sync_add_rejects_own_id_without_parking() {
        crate::store::ensure_test_db_key();
        let dir = temp_dir();
        let own = DeviceIdentity::load_or_create(&dir.join("device.key"))
            .unwrap()
            .node_id;
        let err = sync_add(&dir, &own, "", "").unwrap_err();
        assert!(err.contains("own ID"), "unexpected error: {err}");
        let spelled = format!("  {}  ", own.to_uppercase());
        let err = sync_add(&dir, &spelled, "", "").unwrap_err();
        assert!(err.contains("own ID"), "unexpected error: {err}");
        let store = crate::store::open_test_db(&dir.join("state.db")).unwrap();
        assert!(store.get_peer(&own).unwrap().is_none());
        assert!(store.list_outgoing().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
