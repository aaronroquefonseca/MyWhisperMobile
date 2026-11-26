//! Node-to-node sync transport (v3): Noise-encrypted TCP.
//!
//! Everything on the wire is encrypted with `Noise_XX` (mutual
//! authentication, forward secrecy) and framed. Trust is
//! table-driven with Syncthing-style introduction:
//!
//! * unknown dialers are recorded as pending requests, never merged;
//! * accepting marks the peer trusted + introducer, then syncs;
//! * introducers' vouched devices join automatically as trusted;
//! * everyone exchanges peer lists, so addresses stay fresh without
//!   typing IPs (mDNS covers LAN discovery on top).
//!
//! Identity binding: the Noise static keys prove a fresh encrypted
//! channel, and each hello carries an ed25519 signature over the
//! session's handshake hash made with the node's long-term identity
//! key. A claimed node id only validates against its own key, so
//! spoofing buys nothing and replays die with the session.
//!
//! Message flow inside the encrypted channel (strict ping-pong, the
//! initiator sends first at every step):
//!
//! * hello both ways: `{"v":1,"node_id":"…","name":"…",
//!   "addresses":[],"introducer":false,"sig":[…]}`
//! * verdict from responder: `{"ok":true,"since":{…}}` or `{"error":"…"}`
//! * records both ways: `{"words":[…],"snippets":[…],
//!   "history":[…],"peers":[…],"since":{…},"mark":{…}}`
//!
//! `since`/`mark` (desktop #67) are optional: `since` is the sender's
//! mark of what it last merged from the receiver, so the receiver
//! sends only later changes; `mark` is the position the sender's
//! payload brings the receiver to. Older builds omit both and get (and
//! send) full payloads.
//!
//! Reachability v1: same LAN (mDNS/discovered/gossiped addresses),
//! VPN, or internet with a known address. Hole punching and relays
//! (libp2p-class) are the later internet phase.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::collections::{BTreeSet, HashSet};
use std::io::{Read, Write};
use std::net::{
    IpAddr, SocketAddr, SocketAddrV6, TcpListener, TcpStream, ToSocketAddrs, UdpSocket,
};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::device::{DeviceIdentity, SyncCreds};
use crate::store::{
    HistoryRecord, PeerGossip, SnippetRecord, Store, SyncMark, WordRecord, valid_node_id,
};

const PROTOCOL_V: u32 = 1;
const NOISE_PARAMS: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
const NOISE_BUF: usize = 65535;
const CHUNK: usize = 60000;
/// A Noise message never exceeds 65535 bytes, so no valid frame (from
/// any build) is larger; checked before allocating.
const FRAME_CAP: usize = NOISE_BUF;
const IO_TIMEOUT: Duration = Duration::from_secs(15);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Anyone can open a Noise session, so until the peer is proven and
/// trusted the session gets a total deadline and small messages only.
const PREAUTH_DEADLINE: Duration = Duration::from_secs(30);
const PREAUTH_MSG_CAP: usize = 64 * 1024;
const MSG_CAP: usize = 256 * 1024 * 1024;
/// After trust, every message gets its own deadline: this floor plus
/// `POSTAUTH_MIN_RATE`, so a stalled peer frees its worker.
const POSTAUTH_BASE: Duration = Duration::from_secs(120);
const POSTAUTH_MIN_RATE: u64 = 256 * 1024;
/// Concurrent inbound connections; extras are closed on accept.
const MAX_INBOUND: usize = 8;
const AUTO_SYNC_SECS: u64 = 60;
const MDNS_SERVICE: &str = "_mywhisper._tcp.local.";
const NODE_MISMATCH: &str = "peer node id mismatch";

#[derive(Debug, Serialize, Deserialize)]
struct Hello {
    v: u32,
    node_id: String,
    name: String,
    #[serde(default)]
    addresses: Vec<String>,
    #[serde(default)]
    introducer: bool,
    #[serde(default)]
    sig: Vec<u8>,
}

#[derive(Debug, Serialize, Deserialize)]
struct Verdict {
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    error: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    since: Option<SyncMark>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Payload {
    #[serde(default)]
    words: Vec<WordRecord>,
    #[serde(default)]
    snippets: Vec<SnippetRecord>,
    #[serde(default)]
    history: Vec<HistoryRecord>,
    #[serde(default)]
    peers: Vec<PeerGossip>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    since: Option<SyncMark>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    mark: Option<SyncMark>,
}

#[derive(Debug)]
pub struct SyncReport {
    pub peer_node: String,
    pub peer_name: String,
    pub words_in: usize,
    pub snippets_in: usize,
    pub history_in: usize,
    pub introduced: usize,
}

/// mDNS-discovered peer (LAN, no configuration).
#[derive(Debug, Clone)]
pub struct Discovered {
    pub node_id: String,
    pub name: String,
    pub addresses: Vec<String>,
}

// -- Noise framing --------------------------------------------------------

/// Time left before `deadline`, per-call capped at `IO_TIMEOUT`.
fn time_left(deadline: Instant, what: &str) -> Result<Duration, String> {
    let left = deadline.saturating_duration_since(Instant::now());
    if left.is_zero() {
        return Err(format!("{what}: deadline passed"));
    }
    Ok(left.min(IO_TIMEOUT))
}

/// `write_all` bounded by a total `deadline`, so a peer reading one
/// byte at a time cannot hold the connection forever.
fn write_frame(stream: &mut TcpStream, bytes: &[u8], deadline: Instant) -> Result<(), String> {
    if bytes.len() > FRAME_CAP {
        return Err("frame too large".to_string());
    }
    let mut out = (bytes.len() as u32).to_be_bytes().to_vec();
    out.extend_from_slice(bytes);
    let mut sent = 0;
    while sent < out.len() {
        stream
            .set_write_timeout(Some(time_left(deadline, "write")?))
            .map_err(|e| format!("timeout: {e}"))?;
        match stream.write(&out[sent..]) {
            Ok(0) => return Err("write: connection closed".to_string()),
            Ok(n) => sent += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(e) => return Err(format!("write: {e}")),
        }
    }
    Ok(())
}

/// `read_exact` that also honours a total `deadline`, so a peer
/// trickling bytes cannot outlast the per-read timeout forever.
fn read_full(stream: &mut TcpStream, buf: &mut [u8], deadline: Instant) -> Result<(), String> {
    let mut filled = 0;
    while filled < buf.len() {
        stream
            .set_read_timeout(Some(time_left(deadline, "read")?))
            .map_err(|e| format!("timeout: {e}"))?;
        match stream.read(&mut buf[filled..]) {
            Ok(0) => return Err("read: connection closed".to_string()),
            Ok(n) => filled += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(e) => return Err(format!("read: {e}")),
        }
    }
    Ok(())
}

fn read_frame(stream: &mut TcpStream, deadline: Instant) -> Result<Vec<u8>, String> {
    let mut len = [0u8; 4];
    read_full(stream, &mut len, deadline)?;
    let len = u32::from_be_bytes(len) as usize;
    if len == 0 || len > FRAME_CAP {
        return Err("bad frame length".to_string());
    }
    let mut buf = vec![0u8; len];
    read_full(stream, &mut buf, deadline)?;
    Ok(buf)
}

fn message_deadline(len: usize) -> Instant {
    Instant::now() + POSTAUTH_BASE + Duration::from_secs(len as u64 / POSTAUTH_MIN_RATE)
}

/// One encrypted session: Noise_XX handshake, then length-framed
/// chunked messages over the transport.
pub struct NoiseStream {
    stream: TcpStream,
    transport: snow::TransportState,
    rbuf: Vec<u8>,
    /// Pre-auth: one deadline for the whole phase. After
    /// `authenticated`: reset per message (`message_deadline`).
    deadline: Instant,
    authed: bool,
}

impl NoiseStream {
    fn builder(private_key: &[u8; 32], initiator: bool) -> Result<snow::HandshakeState, String> {
        let params: snow::params::NoiseParams = NOISE_PARAMS
            .parse()
            .map_err(|e| format!("noise params: {e}"))?;
        let builder = snow::Builder::new(params)
            .local_private_key(private_key)
            .map_err(|e| format!("noise key: {e}"))?;
        if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .map_err(|e| format!("noise build: {e}"))
    }

    /// Run the handshake. Returns the session plus the handshake hash
    /// (signed into hellos) and the peer's proven x25519 static key.
    pub fn handshake(
        mut stream: TcpStream,
        private_key: &[u8; 32],
        initiator: bool,
    ) -> Result<(Self, Vec<u8>, [u8; 32]), String> {
        let deadline = Instant::now() + PREAUTH_DEADLINE;
        let mut hs = Self::builder(private_key, initiator)?;
        let mut buf = vec![0u8; NOISE_BUF];
        let mut payload = vec![0u8; NOISE_BUF];
        if initiator {
            // XX initiator: write, read, write.
            loop {
                let n = hs
                    .write_message(&[], &mut buf)
                    .map_err(|e| format!("handshake write: {e}"))?;
                write_frame(&mut stream, &buf[..n], deadline)?;
                if hs.is_handshake_finished() {
                    break;
                }
                let msg = read_frame(&mut stream, deadline)?;
                hs.read_message(&msg, &mut payload)
                    .map_err(|e| format!("handshake read: {e}"))?;
                if hs.is_handshake_finished() {
                    break;
                }
            }
        } else {
            loop {
                let msg = read_frame(&mut stream, deadline)?;
                hs.read_message(&msg, &mut payload)
                    .map_err(|e| format!("handshake read: {e}"))?;
                if hs.is_handshake_finished() {
                    break;
                }
                let n = hs
                    .write_message(&[], &mut buf)
                    .map_err(|e| format!("handshake write: {e}"))?;
                write_frame(&mut stream, &buf[..n], deadline)?;
            }
        }
        let hash = hs.get_handshake_hash().to_vec();
        let remote: [u8; 32] = hs
            .get_remote_static()
            .ok_or_else(|| "no remote static key".to_string())?
            .try_into()
            .map_err(|_| "bad remote static length".to_string())?;
        let transport = hs
            .into_transport_mode()
            .map_err(|e| format!("transport mode: {e}"))?;
        Ok((
            Self {
                stream,
                transport,
                rbuf: vec![0u8; NOISE_BUF],
                deadline,
                authed: false,
            },
            hash,
            remote,
        ))
    }

    /// The peer is proven and trusted: lift the pre-auth limits.
    fn authenticated(&mut self) {
        self.authed = true;
        self.deadline = message_deadline(0);
    }

    fn write_chunk(&mut self, plaintext: &[u8]) -> Result<(), String> {
        let mut buf = vec![0u8; plaintext.len() + 32];
        let n = self
            .transport
            .write_message(plaintext, &mut buf)
            .map_err(|e| format!("encrypt: {e}"))?;
        write_frame(&mut self.stream, &buf[..n], self.deadline)
    }

    /// Send one logical message (chunked at 60 KiB plaintext).
    pub fn send_msg(&mut self, data: &[u8]) -> Result<(), String> {
        if self.authed {
            self.deadline = message_deadline(data.len());
        }
        let total = data.len() as u64;
        self.write_chunk(&total.to_be_bytes())?;
        for chunk in data.chunks(CHUNK) {
            self.write_chunk(chunk)?;
        }
        Ok(())
    }

    pub fn send_json(&mut self, value: &impl Serialize) -> Result<(), String> {
        let bytes = serde_json::to_vec(value).map_err(|e| format!("encode: {e}"))?;
        self.send_msg(&bytes)
    }

    fn read_chunk(&mut self) -> Result<Vec<u8>, String> {
        let frame = read_frame(&mut self.stream, self.deadline)?;
        let n = self
            .transport
            .read_message(&frame, &mut self.rbuf)
            .map_err(|e| format!("decrypt: {e}"))?;
        Ok(self.rbuf[..n].to_vec())
    }

    /// Receive one logical message, reassembling chunks.
    pub fn recv_msg(&mut self) -> Result<Vec<u8>, String> {
        if self.authed {
            self.deadline = message_deadline(0);
        }
        let head = self.read_chunk()?;
        if head.len() != 8 {
            return Err("bad message header".to_string());
        }
        let total = u64::from_be_bytes(head[..8].try_into().unwrap()) as usize;
        let cap = if self.authed {
            MSG_CAP
        } else {
            PREAUTH_MSG_CAP
        };
        if total > cap {
            return Err("message too large".to_string());
        }
        if self.authed {
            self.deadline = message_deadline(total);
        }
        let mut out = Vec::with_capacity(total.min(1024 * 1024));
        while out.len() < total {
            let chunk = self.read_chunk()?;
            if chunk.is_empty() {
                return Err("empty chunk".to_string());
            }
            out.extend_from_slice(&chunk);
        }
        if out.len() != total {
            return Err("message truncated".to_string());
        }
        Ok(out)
    }

    pub fn recv_json<T: serde::de::DeserializeOwned>(&mut self) -> Result<T, String> {
        let bytes = self.recv_msg()?;
        serde_json::from_slice(&bytes).map_err(|e| format!("decode: {e}"))
    }
}

// -- exchange ---------------------------------------------------------------

/// Run one side of an exchange over an encrypted session.
///
/// * `initiator` -- dialer sends first at every step (strict
///   alternation, so large payloads cannot deadlock).
/// * `expect_node` -- when set, the proven session must belong to
///   exactly this node id (client side).
/// * `remote` -- dial-back address observed for this contact.
///
/// The responder opens `db_path` only after the peer proved its id.
/// Records move only through `export_authorized`/`apply_sync_round`,
/// which re-check the sync switch, our own trust in the peer and the
/// permission epoch inside their transactions (desktop #34, R01/R02).
#[allow(clippy::too_many_arguments)]
pub fn exchange(
    session: &mut NoiseStream,
    session_hash: &[u8],
    db_path: &Path,
    creds: &SyncCreds,
    name: &str,
    addresses: &[String],
    initiator: bool,
    expect_node: Option<&str>,
    remote: Option<String>,
) -> Result<SyncReport, String> {
    let open = || Store::open(db_path).map_err(|e| format!("open db: {e}"));
    let dialer_store = if initiator {
        let store = open()?;
        if !store.sync_enabled_explicit() {
            return Err("sync is off".to_string());
        }
        Some(store)
    } else {
        None
    };
    let hello = Hello {
        v: PROTOCOL_V,
        node_id: creds.node_id.clone(),
        name: name.to_string(),
        addresses: addresses.to_vec(),
        introducer: true,
        sig: DeviceIdentity::sign_session_static(&creds.signing, session_hash).to_vec(),
    };
    let peer: Hello = if initiator {
        session.send_json(&hello)?;
        session.recv_json()?
    } else {
        let peer: Hello = session.recv_json()?;
        session.send_json(&hello)?;
        peer
    };
    // Bind the claimed id to the proven handshake: the signature must
    // verify against the claimed node id over this session's hash.
    // Spoofing a node id requires its ed25519 private key, and
    // replaying a proof fails because every handshake hashes fresh
    // ephemerals.
    //
    // Rejections on the responder side always send an error verdict
    // first, so the dialer gets a clean rejection instead of a hang.
    // Spoofed ids are never recorded (no pending row for forgeries).
    let mut sig = [0u8; 64];
    let proof_ok = valid_node_id(&peer.node_id) && peer.sig.len() == 64 && {
        sig.copy_from_slice(&peer.sig);
        DeviceIdentity::verify_session(&peer.node_id, session_hash, &sig)
    };
    let reject = |session: &mut NoiseStream, error: &str| -> Result<SyncReport, String> {
        session.send_json(&Verdict {
            ok: false,
            error: error.to_string(),
            since: None,
        })?;
        Err(error.to_string())
    };
    if initiator {
        if peer.v != PROTOCOL_V {
            return Err(format!("protocol mismatch: v{}", peer.v));
        }
        if !proof_ok {
            return Err("identity proof failed".to_string());
        }
    } else if peer.v != PROTOCOL_V || !proof_ok {
        return reject(session, "identity proof failed");
    }
    let store = match dialer_store {
        Some(store) => store,
        None => open()?,
    };
    if !initiator && !store.sync_enabled_explicit() {
        return reject(session, "sync is off");
    }
    if let Some(expected) = expect_node
        && peer.node_id != expected
    {
        return Err(NODE_MISMATCH.to_string());
    }

    // Contact bookkeeping: unknown nodes become pending requests,
    // known ones refresh addresses.
    let contact_addrs = contact_addresses(&peer.addresses, remote.as_deref());
    let is_new = store
        .record_seen(&peer.node_id, &peer.name, &contact_addrs)
        .map_err(|e| format!("record contact: {e}"))?;
    let peer_row = |store: &Store| {
        store
            .get_peer(&peer.node_id)
            .map_err(|e| format!("peer lookup: {e}"))
    };
    // Re-read below: mutual-consent completion can flip it.
    let mut trusted = peer_row(&store)?.is_some_and(|p| p.trusted);

    let mut their_since = None;
    if initiator {
        let verdict: Verdict = session.recv_json()?;
        if !verdict.ok {
            return Err(if verdict.error.is_empty() {
                "rejected".to_string()
            } else {
                verdict.error
            });
        }
        their_since = verdict.since;
        // Mutual consent, dialer side: we requested them (outgoing) and
        // their verdict accepts us, so complete trust locally (same as
        // the responder-side dial-back completion below).
        if !trusted && peer_row(&store)?.is_some_and(|p| p.outgoing) {
            store
                .decide_request(&peer.node_id, true)
                .map_err(|e| format!("trust peer: {e}"))?;
            trusted = true;
        }
        if !trusted {
            return Err("peer not trusted".to_string());
        }
    } else {
        if !trusted {
            // Responder-side ONLY latch (never on initiator paths): the
            // dialer's identity proof verified above, so this contact is
            // their genuine consent — it latches an incoming request
            // even on known rows (record_seen only parks unknowns).
            store
                .mark_incoming_request(&peer.node_id)
                .map_err(|e| format!("record request: {e}"))?;
            // Mutual consent: we requested them (outgoing) and they
            // dialed back — complete trust now (desktop accept flow:
            // trusted + introducer, markers cleared), no local Accept
            // needed. Strangers stay pending and are rejected below.
            if peer_row(&store)?.is_some_and(|p| p.outgoing && !p.trusted) {
                store
                    .decide_request(&peer.node_id, true)
                    .map_err(|e| format!("trust peer: {e}"))?;
                trusted = true;
            }
        }
        if !trusted {
            session.send_json(&Verdict {
                ok: false,
                error: "not trusted: request recorded, ask them to accept".to_string(),
                since: None,
            })?;
            return Err(if is_new {
                format!("new pairing request from {}", short(&peer.node_id))
            } else {
                "peer not trusted".to_string()
            });
        }
    }
    // What we already merged from this peer: it sends only later changes.
    let our_since = store
        .peer_mark(&peer.node_id)
        .map_err(|e| format!("sync mark: {e}"))?;
    if !initiator {
        session.send_json(&Verdict {
            ok: true,
            error: String::new(),
            since: our_since.clone(),
        })?;
    }
    session.authenticated();
    // Captured once: any switch-off, distrust, revocation or reset
    // after this point fails the export or the merge transaction.
    let epoch = store.sync_epoch();

    // Records, strict alternation again. The responder learns the
    // dialer's `since` from its records, so it exports after reading them.
    let export = |their_since: Option<SyncMark>| -> Result<Vec<u8>, String> {
        let snapshot = store
            .export_authorized(&peer.node_id, epoch, their_since.as_ref())
            .map_err(|e| format!("export: {e}"))?;
        serde_json::to_vec(&Payload {
            words: snapshot.words,
            snippets: snapshot.snippets,
            history: snapshot.history,
            peers: snapshot
                .peers
                .into_iter()
                .filter(|g| g.node_id != creds.node_id)
                .collect(),
            since: our_since.clone(),
            mark: Some(snapshot.mark),
        })
        .map_err(|e| format!("encode: {e}"))
    };
    let theirs: Payload = if initiator {
        session.send_msg(&export(their_since)?)?;
        session.recv_json()?
    } else {
        let theirs: Payload = session.recv_json()?;
        session.send_msg(&export(theirs.since.clone())?)?;
        theirs
    };

    // One atomic merge, re-authorized inside its transaction. Strangers
    // they vouch for join only when we marked the sender an introducer.
    // The mark advances only when every record merged, so an
    // interrupted or partly refused round is sent again.
    let introduced = store
        .apply_sync_round(
            &peer.node_id,
            epoch,
            &creds.node_id,
            (&theirs.words, &theirs.snippets, &theirs.history),
            &theirs.peers,
            theirs.mark.as_ref(),
        )
        .map_err(|e| format!("merge: {e}"))?;
    Ok(SyncReport {
        peer_node: peer.node_id,
        peer_name: peer.name,
        words_in: theirs.words.len(),
        snippets_in: theirs.snippets.len(),
        history_in: theirs.history.len(),
        introduced,
    })
}

/// Display form of a canonical node id (ASCII hex, so byte slicing is
/// safe); anything else is shown whole.
fn short(node_id: &str) -> String {
    if valid_node_id(node_id) {
        format!("{}…{}", &node_id[..8], &node_id[node_id.len() - 4..])
    } else {
        node_id.to_string()
    }
}

/// Peers with a dialed round running in this process.
static DIALING: Mutex<BTreeSet<String>> = Mutex::new(BTreeSet::new());

/// Addresses to store for a contact: what it advertised, plus its
/// observed IP on each advertised port (corrects a wrong self-reported
/// IP). The observed source port itself is ephemeral, never a
/// listener: storing it made every later dial "connection refused".
fn contact_addresses(advertised: &[String], remote: Option<&str>) -> Vec<String> {
    let mut addrs = advertised.to_vec();
    let Some(ip) = remote.and_then(|r| r.parse::<SocketAddr>().ok()).map(|a| a.ip()) else {
        return addrs;
    };
    for port in advertised.iter().filter_map(|a| a.parse::<SocketAddr>().ok()).map(|a| a.port()) {
        let addr = SocketAddr::new(ip, port).to_string();
        if !addrs.contains(&addr) {
            addrs.push(addr);
        }
    }
    addrs
}

/// Dial a peer's known addresses (every resolved address of each) in
/// order; first success wins. Runs the Noise handshake, then requires
/// the proven session to belong to `expect_node` before any record
/// moves. A round already running with that peer makes this one a
/// no-op error instead of a duplicate exchange (desktop #67, R42).
/// `own_addresses` advertises our listen address in hello so the peer
/// stores a dialable address and can dial back (see
/// [`own_listen_addrs`]).
pub fn sync_now(
    addresses: &[String],
    db_path: &Path,
    creds: &SyncCreds,
    name: &str,
    expect_node: &str,
    own_addresses: &[String],
) -> Result<SyncReport, String> {
    if addresses.is_empty() {
        return Err("no known address for peer".to_string());
    }
    // No contact at all while sync is off (exchange re-checks).
    if !Store::open(db_path)
        .map_err(|e| format!("open db: {e}"))?
        .sync_enabled_explicit()
    {
        return Err("sync is off".to_string());
    }
    struct Dialing(String);
    impl Drop for Dialing {
        fn drop(&mut self) {
            DIALING.lock().unwrap().remove(&self.0);
        }
    }
    if !DIALING.lock().unwrap().insert(expect_node.to_string()) {
        return Err("a round with this peer is already running".to_string());
    }
    let _dialing = Dialing(expect_node.to_string());
    let mut last_err = String::new();
    for address in addresses {
        // ANDROID PORT: a single unresolvable entry must not abort the
        // whole round; skip it and try the next.
        let resolved = match address.to_socket_addrs() {
            Ok(resolved) => resolved,
            Err(e) => {
                last_err = format!("resolve {address}: {e}");
                continue;
            }
        };
        for addr in resolved {
            let session = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT)
                .map_err(|e| format!("dial {address}: {e}"))
                .and_then(|stream| NoiseStream::handshake(stream, &creds.noise_static, true));
            let (mut session, hash, _remote_static) = match session {
                Ok(session) => session,
                Err(e) => {
                    last_err = e;
                    continue;
                }
            };
            match exchange(
                &mut session,
                &hash,
                db_path,
                creds,
                name,
                own_addresses,
                true,
                Some(expect_node),
                None,
            ) {
                // A reassigned address can reach another device: keep dialing.
                Err(e) if e == NODE_MISMATCH => last_err = format!("{address}: {e}"),
                Ok(report) => {
                    // ANDROID PORT: persist the working address so the next
                    // round tries the live one first.
                    if let Ok(store) = Store::open(db_path) {
                        let _ = store.record_seen(
                            expect_node,
                            &report.peer_name,
                            std::slice::from_ref(address),
                        );
                    }
                    return Ok(report);
                }
                Err(e) => return Err(e),
            }
        }
    }
    if last_err.is_empty() {
        last_err = "no address for peer".to_string();
    }
    Err(last_err)
}

/// Accept loop: one thread per connection, each with its own DB
/// handle (WAL allows concurrent readers/writers across connections).
/// At most `MAX_INBOUND` connections run at once (desktop #34, R16).
pub fn serve(
    listener: TcpListener,
    db_path: PathBuf,
    creds: SyncCreds,
    name: String,
    stop: &AtomicBool,
) {
    let _ = listener.set_nonblocking(true);
    let active = Arc::new(AtomicUsize::new(0));
    /// Frees one inbound slot when its connection thread ends.
    struct Slot(Arc<AtomicUsize>);
    impl Drop for Slot {
        fn drop(&mut self) {
            self.0.fetch_sub(1, Ordering::SeqCst);
        }
    }
    for conn in listener.incoming() {
        if stop.load(Ordering::Relaxed) {
            break;
        }
        match conn {
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(100));
                continue;
            }
            Ok(stream) => {
                let remote = stream
                    .peer_addr()
                    .map(|a| a.to_string())
                    .unwrap_or_else(|_| String::new());
                if active.fetch_add(1, Ordering::SeqCst) >= MAX_INBOUND {
                    active.fetch_sub(1, Ordering::SeqCst);
                    eprintln!("[sync] {remote}: busy, connection dropped");
                    continue;
                }
                let slot = Slot(active.clone());
                let _ = stream.set_nonblocking(false);
                // The address the dialer reached: current after a network change.
                let addresses: Vec<String> = stream
                    .local_addr()
                    .map(|a| a.to_string())
                    .into_iter()
                    .collect();
                let remote = if remote.is_empty() {
                    None
                } else {
                    Some(remote)
                };
                let db_path = db_path.clone();
                let creds = creds.clone();
                let name = name.clone();
                std::thread::spawn(move || {
                    let _slot = slot;
                    let res = (|| -> Result<SyncReport, String> {
                        let (mut session, hash, _remote_static) =
                            NoiseStream::handshake(stream, &creds.noise_static, false)?;
                        exchange(
                            &mut session,
                            &hash,
                            &db_path,
                            &creds,
                            &name,
                            &addresses,
                            false,
                            None,
                            remote.clone(),
                        )
                    })();
                    match res {
                        Ok(report) => eprintln!(
                            "[sync] served {} ({} words in, {} introduced)",
                            short(&report.peer_node),
                            report.words_in,
                            report.introduced
                        ),
                        Err(e) => {
                            eprintln!("[sync] {}: {e}", remote.unwrap_or_else(|| "?".to_string()))
                        }
                    }
                });
            }
            Err(e) => eprintln!("[sync] accept: {e}"),
        }
    }
}

/// Bind the sync listener. Port 0 disables listening.
pub fn listen(port: u16) -> std::io::Result<Option<TcpListener>> {
    if port == 0 {
        return Ok(None);
    }
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    Ok(Some(listener))
}

/// Best-effort LAN address for advertisements (UDP trick: no traffic
/// is actually sent).
pub fn lan_ip() -> Option<IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|a| a.ip())
}

/// Our dialable listen address for hello advertisements, built from
/// the same listen state `serve` uses (configured port + LAN IP).
/// Empty when we do not listen (port 0) or have no LAN address: the
/// hello then carries no addresses, as before. Without this, a peer
/// that only ever saw our dials stores nothing but the ephemeral
/// source port and can never dial back.
pub fn own_listen_addrs(port: u16) -> Vec<String> {
    if port == 0 {
        return Vec::new();
    }
    lan_ip()
        .map(|ip| vec![format!("{ip}:{port}")])
        .unwrap_or_default()
}

/// Dial our own sync port through our LAN address, the way a peer
/// would. Used for the reachability indicator.
pub fn self_reachable(port: u16) -> Option<String> {
    let ip = lan_ip()?;
    let addr = format!("{ip}:{port}");
    let sock = addr.to_socket_addrs().ok()?.next()?;
    match TcpStream::connect_timeout(&sock, CONNECT_TIMEOUT) {
        Ok(_) => Some(addr),
        Err(_) => None,
    }
}

/// Allow-command hint for the installed firewall, if any (pure
/// presence check, no privileges, no queries: nothing here can pop
/// polkit). The Check button prints it next to the dial result so
/// the user opens the port themselves, whatever they run.
pub fn firewall_allow_hint(port: u16) -> Option<String> {
    fn present(bin: &str) -> bool {
        std::env::var_os("PATH")
            .map(|paths| std::env::split_paths(&paths).any(|p| p.join(bin).is_file()))
            == Some(true)
    }
    if present("firewall-cmd") {
        Some(format!(
            "sudo firewall-cmd --add-port={port}/tcp --permanent && sudo firewall-cmd --reload"
        ))
    } else if present("ufw") {
        Some(format!("sudo ufw allow {port}/tcp"))
    } else {
        None
    }
}

// -- mDNS ----------------------------------------------------------------

pub struct MdnsHandles {
    _daemon: mdns_sd::ServiceDaemon,
}

impl MdnsHandles {
    /// Unregister + stop the daemon (browser recv ends, threads exit).
    pub fn shutdown(&self) {
        let _ = self._daemon.shutdown();
    }
}

/// Advertise this node on the LAN and browse for peers in the
/// background. Discovered nodes land in `sink` (deduplicated by node
/// id); the caller polls it to refresh the UI.
pub fn start_mdns(
    node_id: &str,
    name: &str,
    port: u16,
    sink: Arc<Mutex<HashMap<String, Discovered>>>,
    advertise: bool,
) -> Result<MdnsHandles, String> {
    let daemon = mdns_sd::ServiceDaemon::new().map_err(|e| format!("mdns: {e}"))?;
    // The listener is IPv4-only (`listen`): advertising IPv6 addresses
    // hands peers addresses that always answer "connection refused".
    daemon
        .disable_interface(mdns_sd::IfKind::IPv6)
        .map_err(|e| format!("mdns: {e}"))?;
    if advertise {
        let short_id = &node_id[..node_id.len().min(8)];
        let mut txt = std::collections::HashMap::new();
        txt.insert("node".to_string(), node_id.to_string());
        txt.insert("name".to_string(), name.to_string());
        let host = format!("mywhisper-{short_id}.local.");
        // The daemon tracks interface addresses, so a DHCP or network
        // change re-advertises the new address (desktop #67, R42).
        let info = mdns_sd::ServiceInfo::new(MDNS_SERVICE, short_id, &host, (), port, Some(txt))
            .map_err(|e| format!("mdns service: {e}"))?
            .enable_addr_auto();
        daemon
            .register(info)
            .map_err(|e| format!("mdns register: {e}"))?;
    }

    let browser = daemon
        .browse(MDNS_SERVICE)
        .map_err(|e| format!("mdns browse: {e}"))?;
    let self_id = node_id.to_string();
    std::thread::spawn(move || {
        // Service instance name -> node id, to forget removed services.
        let mut instances: HashMap<String, String> = HashMap::new();
        while let Ok(event) = browser.recv() {
            match event {
                mdns_sd::ServiceEvent::ServiceResolved(info) => {
                    let node = info
                        .get_property_val_str("node")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    // Unauthenticated LAN input: only canonical ids get in.
                    if node == self_id || !valid_node_id(&node) {
                        continue;
                    }
                    let name = info
                        .get_property_val_str("name")
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    let port = info.get_port();
                    let addresses: Vec<String> = info
                        .get_addresses()
                        .iter()
                        .map(|ip| dial_address(ip, port))
                        .collect();
                    if addresses.is_empty() {
                        continue;
                    }
                    eprintln!("[sync] mdns found {name} {addresses:?}");
                    instances.insert(info.get_fullname().to_string(), node.clone());
                    sink.lock().unwrap().insert(
                        node.clone(),
                        Discovered {
                            node_id: node,
                            name,
                            addresses,
                        },
                    );
                }
                mdns_sd::ServiceEvent::ServiceRemoved(_, full_name) => {
                    eprintln!("[sync] mdns lost {full_name}");
                    // Forget it, so its return counts as a new appearance.
                    if let Some(node) = instances.remove(&full_name) {
                        sink.lock().unwrap().remove(&node);
                    }
                }
                _ => {}
            }
        }
    });
    Ok(MdnsHandles { _daemon: daemon })
}

/// `ip:port` a dial can parse: IPv6 gets brackets and its numeric scope.
fn dial_address(ip: &mdns_sd::ScopedIp, port: u16) -> String {
    match ip {
        mdns_sd::ScopedIp::V6(v6) => {
            SocketAddrV6::new(*v6.addr(), port, 0, v6.scope_id().index).to_string()
        }
        other => SocketAddr::new(other.to_ip_addr(), port).to_string(),
    }
}

// -- periodic and triggered rounds ------------------------------------------

/// One best-effort round with every trusted peer that has a known
/// address. Returns per-peer outcome lines for logging. Unreachable
/// peers are simply skipped; the next round (or the next local
/// change) retries them.
pub fn sync_all_trusted(db_path: &Path, creds: &SyncCreds) -> Vec<String> {
    let store = match Store::open(db_path) {
        Ok(s) => s,
        Err(e) => return vec![format!("open db: {e}")],
    };
    if !store.sync_enabled_explicit() {
        return vec!["sync is off".to_string()];
    }
    let settings = store.load_settings();
    let name = if settings.sync.device_name.is_empty() {
        creds.node_id.clone()
    } else {
        settings.sync.device_name.clone()
    };
    let peers: Vec<_> = store
        .list_peers()
        .unwrap_or_default()
        .into_iter()
        .filter(|p| p.trusted && !p.dial_targets().is_empty())
        .collect();
    let own_addresses = own_listen_addrs(settings.sync.listen_port);
    let mut out = Vec::new();
    for peer in peers {
        match sync_now(
            &peer.dial_targets(),
            db_path,
            creds,
            &name,
            &peer.node_id,
            &own_addresses,
        ) {
            Ok(r) => out.push(format!(
                "synced {} ({} words in)",
                short(&r.peer_node),
                r.words_in
            )),
            Err(e) => out.push(format!("{} unreachable ({e})", short(&peer.node_id))),
        }
    }
    out
}

/// Every minute, sync with each trusted peer that has a known
/// address. Failures just log; the next round retries.
pub fn auto_sync_loop(db_path: PathBuf, creds: SyncCreds, stop: Arc<AtomicBool>) {
    for _ in 0..AUTO_SYNC_SECS {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        for line in sync_all_trusted(&db_path, &creds) {
            eprintln!("[sync] auto: {line}");
        }
        for _ in 0..AUTO_SYNC_SECS {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    }
}

/// Fire-and-forget sync round after a local change (dictionary edit,
/// rename, …). Same best-effort semantics as the periodic loop.
pub fn sync_after_change(db_path: PathBuf, creds: SyncCreds) {
    std::thread::spawn(move || {
        for line in sync_all_trusted(&db_path, &creds) {
            eprintln!("[sync] on-change: {line}");
        }
    });
}

/// One catch-up round shortly after launch, so a fresh start converges
/// without waiting for the periodic loop.
pub fn sync_on_startup(db_path: PathBuf, creds: SyncCreds) {
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(3));
        for line in sync_all_trusted(&db_path, &creds) {
            eprintln!("[sync] startup: {line}");
        }
    });
}

/// Watch mDNS discovery: whenever a *trusted* peer newly appears on
/// the LAN, sync immediately instead of waiting for the loop.
pub fn watch_discovered(
    db_path: PathBuf,
    creds: SyncCreds,
    discovered: Arc<Mutex<HashMap<String, Discovered>>>,
    stop: Arc<AtomicBool>,
) {
    std::thread::spawn(move || {
        let mut present = HashSet::new();
        loop {
            for _ in 0..10 {
                if stop.load(Ordering::Relaxed) {
                    return;
                }
                std::thread::sleep(Duration::from_secs(1));
            }
            let Ok(store) = Store::open(&db_path) else {
                continue;
            };
            let snapshot = discovered.lock().unwrap().clone();
            if note_discovered(&store, &snapshot, &mut present) {
                eprintln!("[sync] peer appeared, syncing now");
                for line in sync_all_trusted(&db_path, &creds) {
                    eprintln!("[sync] on-appear: {line}");
                }
            }
        }
    });
}

/// One appear-watcher pass: store the addresses discovery found for
/// trusted peers (a DHCP move needs no open UI), and report whether
/// one appeared since the last pass (`present`).
fn note_discovered(
    store: &Store,
    discovered: &HashMap<String, Discovered>,
    present: &mut HashSet<String>,
) -> bool {
    let mut now_present = HashSet::new();
    for peer in store.list_peers().unwrap_or_default() {
        let Some(found) = discovered.get(&peer.node_id).filter(|_| peer.trusted) else {
            continue;
        };
        let merged = crate::store::merge_addresses(&peer.addresses, &found.addresses);
        if merged != peer.addresses {
            let _ = store.set_peer_addresses(&peer.node_id, &merged);
        }
        now_present.insert(peer.node_id);
    }
    let appeared = now_present.iter().any(|id| !present.contains(id));
    *present = now_present;
    appeared
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device::DeviceIdentity;
    use crate::store::open_test_db;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_SEQ: AtomicU64 = AtomicU64::new(0);

    /// File-backed test store with sync explicitly switched on.
    fn sync_db(path: &Path) -> Store {
        let store = open_test_db(path).unwrap();
        let mut settings = store.load_settings();
        settings.sync.enabled = true;
        store.save_settings(&settings).unwrap();
        store
    }

    fn temp_db(tag: &str) -> PathBuf {
        let n = TEST_SEQ.fetch_add(1, Ordering::SeqCst);
        let pid = std::process::id();
        std::env::temp_dir().join(format!("mywhisper-sync-test-{pid}-{n}-{tag}.db"))
    }

    /// One served round: `server` answers a single dial from `client`.
    fn round(
        server: (&Path, &DeviceIdentity),
        client: (&Path, &DeviceIdentity),
    ) -> (Result<SyncReport, String>, Result<SyncReport, String>) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let (db, creds) = (server.0.to_path_buf(), server.1.creds());
        let served = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let (mut session, hash, _) =
                NoiseStream::handshake(stream, &creds.noise_static, false)?;
            exchange(
                &mut session,
                &hash,
                &db,
                &creds,
                "s",
                &[],
                false,
                None,
                None,
            )
        });
        let dialed = sync_now(
            &[format!("127.0.0.1:{port}")],
            client.0,
            &client.1.creds(),
            "c",
            &server.1.node_id,
            &[],
        );
        (dialed, served.join().unwrap())
    }

    fn paired() -> (DeviceIdentity, DeviceIdentity, PathBuf, PathBuf) {
        let (id_a, id_b) = (DeviceIdentity::ephemeral(), DeviceIdentity::ephemeral());
        let (db_a, db_b) = (temp_db("pa"), temp_db("pb"));
        sync_db(&db_a)
            .upsert_peer(&id_b.node_id, "b", "", true)
            .unwrap();
        sync_db(&db_b)
            .upsert_peer(&id_a.node_id, "a", "", true)
            .unwrap();
        (id_a, id_b, db_a, db_b)
    }

    #[test]
    fn sync_off_moves_no_records_in_either_direction() {
        let (id_a, id_b, db_a, db_b) = paired();
        let store_a = open_test_db(&db_a).unwrap();
        let store_b = open_test_db(&db_b).unwrap();
        store_a.upsert_word("from-a", &id_a.node_id).unwrap();
        store_b.upsert_word("from-b", &id_b.node_id).unwrap();
        let off = |store: &Store| {
            let mut settings = store.load_settings();
            settings.sync.enabled = false;
            store.save_settings(&settings).unwrap();
        };
        // Responder off: it refuses with a verdict, before any record.
        off(&store_a);
        let (dialed, served) = round((&db_a, &id_a), (&db_b, &id_b));
        assert_eq!(dialed.unwrap_err(), "sync is off");
        assert!(served.is_err());
        // Dialer off: it never dials.
        sync_db(&db_a);
        off(&store_b);
        let err = sync_now(
            &["127.0.0.1:9".into()],
            &db_b,
            &id_b.creds(),
            "b",
            &id_a.node_id,
            &[],
        )
        .unwrap_err();
        assert_eq!(err, "sync is off");
        assert_eq!(store_a.load_words().unwrap(), ["from-a"]);
        assert_eq!(store_b.load_words().unwrap(), ["from-b"]);
    }

    #[test]
    fn dialer_sends_nothing_to_a_peer_it_does_not_trust() {
        let (id_a, id_b, db_a, db_b) = paired();
        let store_b = open_test_db(&db_b).unwrap();
        store_b.upsert_word("secret", &id_b.node_id).unwrap();
        // B knows A only as a pending stranger; A trusts B.
        store_b.decide_request(&id_a.node_id, false).unwrap();
        let (dialed, served) = round((&db_a, &id_a), (&db_b, &id_b));
        assert!(dialed.is_err() && served.is_err());
        assert!(
            open_test_db(&db_a)
                .unwrap()
                .load_words()
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn later_rounds_send_only_changes() {
        let (id_a, id_b, db_a, db_b) = paired();
        let store_a = open_test_db(&db_a).unwrap();
        let store_b = open_test_db(&db_b).unwrap();
        store_a.upsert_word("a1", &id_a.node_id).unwrap();
        store_b.upsert_word("b1", &id_b.node_id).unwrap();
        let (dialed, served) = round((&db_a, &id_a), (&db_b, &id_b));
        assert_eq!(dialed.unwrap().words_in, 1);
        assert_eq!(served.unwrap().words_in, 1);
        // Second round: both sides know each other's marks. Only the
        // new edit travels (merged rows echo once, then settle).
        let _ = round((&db_a, &id_a), (&db_b, &id_b));
        store_a.upsert_word("a2", &id_a.node_id).unwrap();
        let (dialed, served) = round((&db_a, &id_a), (&db_b, &id_b));
        assert_eq!(dialed.unwrap().words_in, 1);
        assert_eq!(served.unwrap().words_in, 0);
        let mut words = store_b.load_words().unwrap();
        words.sort();
        assert_eq!(words, ["a1", "a2", "b1"]);
    }

    #[test]
    fn messages_without_marks_decode_and_none_marks_are_omitted() {
        // An older build's payload and verdict carry no since/mark.
        let old: Payload = serde_json::from_str(r#"{"words":[],"peers":[]}"#).unwrap();
        assert!(old.since.is_none() && old.mark.is_none());
        let verdict: Verdict = serde_json::from_str(r#"{"ok":true}"#).unwrap();
        assert!(verdict.ok && verdict.since.is_none());
        let sent = serde_json::to_value(Payload::default()).unwrap();
        assert!(sent.get("since").is_none() && sent.get("mark").is_none());
    }

    #[test]
    fn unauthenticated_peers_cannot_send_large_messages() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server_key = DeviceIdentity::ephemeral().creds().noise_static;
        let served = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let (mut session, _, _) = NoiseStream::handshake(stream, &server_key, false).unwrap();
            session.recv_msg()
        });
        let stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let client_key = DeviceIdentity::ephemeral().creds().noise_static;
        let (mut session, _, _) = NoiseStream::handshake(stream, &client_key, true).unwrap();
        let _ = session.send_msg(&vec![b'x'; PREAUTH_MSG_CAP + 1]);
        assert_eq!(served.join().unwrap().unwrap_err(), "message too large");
    }

    #[test]
    fn mdns_addresses_are_dialable() {
        let v4: IpAddr = "192.168.1.5".parse().unwrap();
        let v6: IpAddr = "fe80::1".parse().unwrap();
        assert_eq!(dial_address(&v4.into(), 7), "192.168.1.5:7");
        let v6 = dial_address(&v6.into(), 7);
        assert!(v6.starts_with("[fe80::1") && v6.ends_with("]:7"), "{v6}");
        assert!(v6.to_socket_addrs().is_ok());
    }

    #[test]
    fn contact_addresses_never_store_the_source_port() {
        let advertised = vec!["10.0.0.9:42973".to_string()];
        // Observed IP differs from the self-reported one: keep both, on the listen port.
        assert_eq!(
            contact_addresses(&advertised, Some("192.168.1.76:52686")),
            ["10.0.0.9:42973", "192.168.1.76:42973"]
        );
        // Nothing advertised (not listening): nothing dialable to store.
        assert!(contact_addresses(&[], Some("192.168.1.76:52686")).is_empty());
        assert_eq!(contact_addresses(&advertised, None), advertised);
    }

    /// Full client/server round-trip over real loopback TCP with Noise
    /// encryption: B dials A, both sides converge, untrusted dialers
    /// are rejected.
    #[test]
    fn loopback_sync_converges_and_rejects_untrusted() {
        let id_a = DeviceIdentity::ephemeral();
        let id_b = DeviceIdentity::ephemeral();
        let db_a = temp_db("a");
        let db_b = temp_db("b");
        let store_a = sync_db(&db_a);
        let store_b = sync_db(&db_b);

        // A trusts B; B trusts A.
        store_a
            .upsert_peer(&id_b.node_id, "b", "unused", true)
            .unwrap();
        store_b
            .upsert_peer(&id_a.node_id, "a", "unused", true)
            .unwrap();

        // Divergent content on both sides.
        store_a.upsert_word("from-a", &id_a.node_id).unwrap();
        store_b.upsert_word("from-b", &id_b.node_id).unwrap();
        store_a
            .upsert_snippet("ta", "A says", &id_a.node_id)
            .unwrap();
        store_b
            .upsert_snippet("tb", "B says", &id_b.node_id)
            .unwrap();

        // A serves on an ephemeral port.
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let db_a2 = db_a.clone();
        let creds_a = id_a.creds();
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let (mut session, hash, _remote) =
                NoiseStream::handshake(stream, &creds_a.noise_static, false).unwrap();
            exchange(
                &mut session,
                &hash,
                &db_a2,
                &creds_a,
                "a",
                &[],
                false,
                None,
                None,
            )
            .unwrap();
            // Serve exactly one round for the test.
        });

        let report = sync_now(
            &[format!("127.0.0.1:{port}")],
            &db_b,
            &id_b.creds(),
            "b",
            &id_a.node_id,
            &[],
        )
        .unwrap();
        server.join().unwrap();

        assert_eq!(report.peer_node, id_a.node_id);
        assert!(report.words_in >= 1);

        // Both sides converged.
        let mut wa: Vec<_> = store_a.load_words().unwrap();
        let mut wb: Vec<_> = store_b.load_words().unwrap();
        wa.sort();
        wb.sort();
        assert_eq!(wa, wb);
        assert!(wa.contains(&"from-a".to_string()));
        assert!(wa.contains(&"from-b".to_string()));
        let ta = store_a.load_snippets().unwrap();
        assert_eq!(ta.len(), 2);

        // Untrusted dialer is rejected: fresh node C, trusted nowhere.
        let id_c = DeviceIdentity::ephemeral();
        let db_c = temp_db("c");
        sync_db(&db_c);
        let listener2 = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port2 = listener2.local_addr().unwrap().port();
        let db_a3 = db_a.clone();
        let creds_a2 = id_a.creds();
        let server2 = std::thread::spawn(move || {
            let (stream, _) = listener2.accept().unwrap();
            let (mut session, hash, _remote) =
                NoiseStream::handshake(stream, &creds_a2.noise_static, false).unwrap();
            exchange(
                &mut session,
                &hash,
                &db_a3,
                &creds_a2,
                "a",
                &[],
                false,
                None,
                None,
            )
        });
        let err = sync_now(
            &[format!("127.0.0.1:{port2}")],
            &db_c,
            &id_c.creds(),
            "c",
            &id_a.node_id,
            &[],
        )
        .unwrap_err();
        let server_err = server2.join().unwrap().unwrap_err();
        assert!(
            err.contains("not trusted") || server_err.contains("not trusted"),
            "client err: {err}, server err: {server_err}"
        );
        // The rejected node left a pending request behind.
        let pending = store_a.list_pending().unwrap();
        assert!(pending.iter().any(|p| p.node_id == id_c.node_id));

        std::fs::remove_file(&db_a).ok();
        std::fs::remove_file(&db_b).ok();
        std::fs::remove_file(&db_c).ok();
    }

    /// Requester-side completion without a local Accept: A sent B a
    /// pairing request (WAITING: outgoing, never Accepted locally) and B
    /// accepted there, then dials back. B's proof-verified dial-back IS
    /// B's consent, so A's responder completes trust (trusted +
    /// introducer) inside the round — data moves both ways and B's
    /// vouched peer joins A through the mesh.
    ///
    /// Address honesty: NOTHING hands A's listen port to B out-of-band.
    /// B learns it only from A's hello addresses (phase 1) and dials
    /// back using only stored targets (phase 2). Without hello
    /// advertisement B stores just the ephemeral source port and the
    /// dial-back refuses — this test fails.
    #[test]
    fn requester_waiting_plus_acceptor_dialback_completes_without_accept() {
        let id_a = DeviceIdentity::ephemeral();
        let id_b = DeviceIdentity::ephemeral();
        let id_c = DeviceIdentity::ephemeral();
        let db_a = temp_db("ea");
        let db_b = temp_db("eb");
        let store_a = sync_db(&db_a);
        let store_b = sync_db(&db_b);

        // A sent B a pairing request: WAITING, never Accepted on A.
        store_a
            .record_outgoing(&id_b.node_id, "b", &[], &id_a.node_id)
            .unwrap();
        assert_eq!(store_a.list_pending().unwrap().len(), 0);
        // B vouches for C (joins A through the mesh on completion).
        store_b
            .upsert_peer(&id_c.node_id, "c", "unused", true)
            .unwrap();

        // Divergent content on both sides.
        store_a.upsert_word("from-a", &id_a.node_id).unwrap();
        store_b.upsert_word("from-b", &id_b.node_id).unwrap();

        // A listens on an ephemeral port; B listens too, for the
        // request-delivery dial. Neither port leaves this test except
        // inside hello addresses.
        let listener_a = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port_a = listener_a.local_addr().unwrap().port();
        let addr_a = format!("127.0.0.1:{port_a}");
        let listener_b = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port_b = listener_b.local_addr().unwrap().port();

        // Phase 1: B serves; A dials to deliver the request, advertising
        // its own listen address. B rejects (trusts nobody yet) — the
        // rejection IS the delivered request.
        let db_b2 = db_b.clone();
        let creds_b = id_b.creds();
        let phase1 = std::thread::spawn(move || {
            let (stream, _) = listener_b.accept().unwrap();
            let (mut session, hash, _remote) =
                NoiseStream::handshake(stream, &creds_b.noise_static, false).unwrap();
            let res = exchange(
                &mut session,
                &hash,
                &db_b2,
                &creds_b,
                "b",
                &[],
                false,
                None,
                None,
            );
            res.unwrap_err();
        });
        let err = sync_now(
            &[format!("127.0.0.1:{port_b}")],
            &db_a,
            &id_a.creds(),
            "a",
            &id_b.node_id,
            std::slice::from_ref(&addr_a),
        )
        .unwrap_err();
        assert!(err.contains("not trusted"), "got: {err}");
        phase1.join().unwrap();

        // B learned a dialable address for A ONLY via the hello, and
        // accepts there (desktop flow: trusted + introducer).
        let targets = open_test_db(&db_b)
            .unwrap()
            .get_peer(&id_a.node_id)
            .unwrap()
            .unwrap()
            .dial_targets();
        assert!(
            targets.contains(&addr_a),
            "B must learn A's listen addr from hello, got {targets:?}"
        );
        store_b
            .upsert_peer(&id_a.node_id, "a", "unused", true)
            .unwrap();
        store_b.set_peer_introducer(&id_a.node_id, true).unwrap();

        // Phase 2: A serves the dial-back; B dials using ONLY stored
        // (exchanged) targets — the live port is never passed explicitly.
        let db_a2 = db_a.clone();
        let creds_a = id_a.creds();
        let phase2 = std::thread::spawn(move || {
            let (stream, _) = listener_a.accept().unwrap();
            let (mut session, hash, _remote) =
                NoiseStream::handshake(stream, &creds_a.noise_static, false).unwrap();
            exchange(
                &mut session,
                &hash,
                &db_a2,
                &creds_a,
                "a",
                &[],
                false,
                None,
                None,
            )
            .unwrap();
        });
        let report = sync_now(
            &targets,
            &db_b,
            &id_b.creds(),
            "b",
            &id_a.node_id,
            &[format!("127.0.0.1:{port_b}")],
        )
        .unwrap();
        phase2.join().unwrap();

        assert_eq!(report.peer_node, id_a.node_id);
        // Data moved both ways through the completed round.
        assert!(
            store_a
                .load_words()
                .unwrap()
                .contains(&"from-b".to_string())
        );
        assert!(
            store_b
                .load_words()
                .unwrap()
                .contains(&"from-a".to_string())
        );

        // A's WAITING row completed with NO local Accept on A: trusted +
        // introducer, both markers cleared.
        let row = store_a.get_peer(&id_b.node_id).unwrap().unwrap();
        assert!(row.trusted && row.introducer && !row.pending && !row.outgoing);
        assert!(store_a.list_pending().unwrap().is_empty());
        assert!(store_a.list_outgoing().unwrap().is_empty());

        // Mesh: B's vouched C joined A as trusted automatically.
        let c = store_a.get_peer(&id_c.node_id).unwrap().unwrap();
        assert!(c.trusted && !c.pending && !c.outgoing);

        std::fs::remove_file(&db_a).ok();
        std::fs::remove_file(&db_b).ok();
    }

    /// An attacker with their own valid keypair who claims someone
    /// else's node id in hello is rejected: the session proof cannot
    /// verify against the victim's key.
    #[test]
    fn spoofed_node_id_rejected() {
        let id_victim = DeviceIdentity::ephemeral();
        let id_attacker = DeviceIdentity::ephemeral();
        let db_v = temp_db("v");
        let store_v = sync_db(&db_v);
        // Victim trusts... nobody relevant; attacker dials claiming
        // to BE the victim's trusted peer would need that peer's key.
        // Here the attacker claims victim's own id to a fresh server
        // that trusts the victim id: handshake succeeds (valid keys
        // on both sides) but the hello proof fails.
        store_v
            .upsert_peer(&id_victim.node_id, "victim", "unused", true)
            .unwrap();

        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let db_v2 = db_v.clone();
        let _ = id_victim.node_id.clone();
        let creds_vk = id_victim.creds();
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let (mut session, hash, _remote) =
                NoiseStream::handshake(stream, &creds_vk.noise_static, false).unwrap();
            exchange(
                &mut session,
                &hash,
                &db_v2,
                &creds_vk,
                "v",
                &[],
                false,
                None,
                None,
            )
        });

        // Attacker crafts a hello claiming the victim id, but can only
        // sign with their own key: build the exchange manually with a
        // forged hello over a valid attacker handshake.
        let db_x = temp_db("x");
        sync_db(&db_x);
        let stream = TcpStream::connect_timeout(
            &format!("127.0.0.1:{port}")
                .to_socket_addrs()
                .unwrap()
                .next()
                .unwrap(),
            Duration::from_secs(10),
        )
        .unwrap();
        let (mut session, hash, _remote) =
            NoiseStream::handshake(stream, &id_attacker.creds().noise_static, true).unwrap();
        // Forge: valid framing, victim's id, attacker's signature.
        let forged = Hello {
            v: PROTOCOL_V,
            node_id: id_victim.node_id.clone(),
            name: "attacker".to_string(),
            addresses: vec![],
            introducer: false,
            sig: DeviceIdentity::sign_session_static(&id_attacker.creds().signing, &hash).to_vec(),
        };
        session.send_json(&forged).unwrap();
        let _peer_hello: Hello = session.recv_json().unwrap();
        let verdict: Verdict = session.recv_json().unwrap();
        assert!(!verdict.ok, "spoofed hello must be rejected");
        drop(session);
        let server_err = server.join().unwrap().unwrap_err();
        assert!(
            server_err.contains("identity proof failed"),
            "server err: {server_err}"
        );

        std::fs::remove_file(&db_v).ok();
        std::fs::remove_file(&db_x).ok();
    }
}
