//! Local state database: the single replicated file.
//!
//! `state.db` holds settings, dictionary, snippets, history and sync
//! metadata. WAL mode with a checkpoint on clean shutdown keeps it
//! safe to replicate with external file-sync tools, and it is the
//! unit the future node-based P2P sync exchanges.
//!
//! Sync model: every mutable record carries `(updated_at_ms,
//! updated_by, deleted)`. Two devices that diverged from a common
//! version exchange record sets; the union converges and concurrent
//! edits to the same key resolve Last-Writer-Wins on
//! `(updated_at_ms, updated_by)`. Deletes are tombstones so they win
//! over concurrent edits only when newer (same LWW rule).

use rusqlite::Connection;
use rusqlite::OptionalExtension;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::settings::{AppSettings, HistoryEntry, Snippet};

/// Milliseconds since the Unix epoch (wall clock). Sync conflict
/// resolution compares these; ties break on node id (total order).
pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

const SCHEMA: &str = "
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA user_version = 3;
CREATE TABLE IF NOT EXISTS kv (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS words (
    word TEXT PRIMARY KEY,
    updated_at INTEGER NOT NULL DEFAULT 0,
    updated_by TEXT NOT NULL DEFAULT '',
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS snippets (
    trigger TEXT PRIMARY KEY,
    replacement TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL DEFAULT 0,
    updated_by TEXT NOT NULL DEFAULT '',
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS history (
    uuid TEXT PRIMARY KEY,
    text TEXT NOT NULL,
    raw_text TEXT NOT NULL DEFAULT '',
    words INTEGER NOT NULL DEFAULT 0,
    duration_secs INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    discarded INTEGER NOT NULL DEFAULT 0,
    recorded_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
);
CREATE TABLE IF NOT EXISTS devices (
    node_id TEXT PRIMARY KEY,
    name TEXT NOT NULL DEFAULT '',
    address TEXT NOT NULL DEFAULT '',
    addresses TEXT NOT NULL DEFAULT '[]',
    trusted INTEGER NOT NULL DEFAULT 0,
    pending INTEGER NOT NULL DEFAULT 0,
    outgoing INTEGER NOT NULL DEFAULT 0,
    introducer INTEGER NOT NULL DEFAULT 0,
    last_seen INTEGER NOT NULL DEFAULT 0,
    last_sync INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sync_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- Revoked peers: removals survive as a durable deny-list so a stale
-- gossip entry or retained row on another device cannot re-add them.
-- (Desktop #18 port: revocation is local-durable; gossiped revocation
-- is deferred to avoid a wire change.)
CREATE TABLE IF NOT EXISTS revoked_devices (
    node_id TEXT PRIMARY KEY
);
-- Insights read pattern: per-day aggregates over a trailing window.
-- Indexed so it stays a single range scan at any realistic volume.
CREATE INDEX IF NOT EXISTS history_recorded ON history (recorded_at);
";

/// Monotonic data generation: bumped on every mutation (local CRUD
/// and incoming merges). UI pages poll it to refresh lists that
/// changed under them (e.g. words arriving from a sync round).
pub static DATA_GEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub fn data_gen() -> u64 {
    DATA_GEN.load(std::sync::atomic::Ordering::SeqCst)
}

fn bump_data_gen() {
    DATA_GEN.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
}

/// Cross-process change token: an explicit counter in `sync_meta`,
/// bumped on every mutation (local CRUD and incoming merges), read
/// fresh on every poll. Unlike `PRAGMA data_version` (which proved
/// unreliable: stale on the writing connection, frozen on `:memory:`),
/// this is plain data and works identically everywhere. Pair with the
/// process-local `data_gen` in UI refresh polls.
pub fn read_gen(store: &Store) -> i64 {
    store
        .conn
        .query_row("SELECT value FROM sync_meta WHERE key = 'gen'", [], |r| {
            r.get::<_, String>(0)
        })
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0)
}

fn bump_gen(conn: &Connection) {
    let _ = conn.execute(
        "INSERT INTO sync_meta (key, value) VALUES ('gen', '1')
         ON CONFLICT (key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
        [],
    );
}

/// `(dev, ino)` snapshot of the database file, taken at open.
fn file_identity_of(path: &Path) -> Option<(u64, u64)> {
    use std::os::unix::fs::MetadataExt;
    std::fs::metadata(path).ok().map(|m| (m.dev(), m.ino()))
}

pub struct Store {
    conn: Connection,
    file_identity: Option<(u64, u64)>,
}

static HISTORY_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

fn has_column(conn: &Connection, table: &str, column: &str) -> bool {
    conn.prepare(&format!("SELECT {column} FROM {table} LIMIT 0"))
        .is_ok()
}

/// Node ids are 64 hex chars (the hex-encoded ed25519 identity key).
/// Local copy of the desktop `device::valid_node_id` check so peer
/// bookkeeping never stores a malformed identity (device.rs is outside
/// this port's owned files).
fn valid_node_id(id: &str) -> bool {
    id.len() == 64 && id.chars().all(|c| c.is_ascii_hexdigit())
}

/// Database encryption key (32 bytes). Order: explicit env override
/// (headless/testing) first, otherwise the OS keyring, creating and
/// storing a random key on first run.
///
/// ANDROID PORT (see core/README.md): on Android the key comes from
/// `MYWHISPER_DB_KEY`, set in-process by JNI `nativeOpen` from a
/// Keystore-backed EncryptedSharedPreferences value. This is the
/// direct equivalent of the desktop Secret Service entry, with the
/// same bounded semantics: a single read, no fallback, no default —
/// a missing key and a malformed key fail with distinct errors, and a
/// missing key is never silently replaced (desktop `database_key.rs`
/// write-once provisioning).
fn db_key() -> Result<[u8; 32], String> {
    if let Ok(hexkey) = std::env::var("MYWHISPER_DB_KEY") {
        let hexkey = hexkey.trim().to_string();
        if hexkey.len() != 64 {
            return Err("MYWHISPER_DB_KEY must be 64 hex chars".to_string());
        }
        return hex_decode_key(&hexkey).ok_or_else(|| {
            "MYWHISPER_DB_KEY is malformed (not hex); original key preserved".to_string()
        });
    }
    #[cfg(target_os = "android")]
    return Err("MYWHISPER_DB_KEY not set (nativeOpen must provide the Keystore key)".to_string());
    // IOS PORT: iOS never falls back to the desktop keyring (keyring
    // does not compile for iOS); Swift always passes the Keychain key.
    #[cfg(target_os = "ios")]
    return Err("MYWHISPER_DB_KEY not set (engine_open must provide the Keychain key)".to_string());
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        db_key_desktop()
    }
}

/// Desktop-only: Secret Service keyring lookup/creation.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn db_key_desktop() -> Result<[u8; 32], String> {
    let entry = keyring::Entry::new("mywhisper", "state-db")
        .map_err(|e| format!("keyring unavailable: {e} (is a Secret Service running?)"))?;
    match entry.get_password() {
        Ok(stored) => {
            hex_decode_key(&stored).ok_or_else(|| "keyring holds a malformed db key".to_string())
        }
        Err(keyring::Error::NoEntry) => {
            let mut raw = [0u8; 32];
            getrandom::fill(&mut raw).map_err(|e| format!("rng: {e}"))?;
            entry
                .set_password(&hex_encode(&raw))
                .map_err(|e| format!("keyring store failed: {e}"))?;
            Ok(raw)
        }
        Err(e) => Err(format!("keyring read failed: {e}")),
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0xf) as usize] as char);
    }
    out
}

fn hex_decode_key(s: &str) -> Option<[u8; 32]> {
    if s.len() != 64 {
        return None;
    }
    let b = s.as_bytes();
    let mut out = [0u8; 32];
    for i in 0..32 {
        out[i] = (hex_val(b[2 * i])? << 4) | hex_val(b[2 * i + 1])?;
    }
    Some(out)
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

fn key_pragma(key: &[u8; 32]) -> String {
    format!("PRAGMA key = \"x'{}'\"", hex_encode(key))
}

fn is_not_a_database(e: &rusqlite::Error) -> bool {
    format!("{e:?}").contains("not a database")
}

/// Delete SQLite sidecars (`-wal`, `-shm`, `-journal`) next to `path`.
/// After the plaintext→encrypted migration the main file is replaced
/// by `rename`, but the old sidecars survive next to it and still hold
/// plaintext pages. Best-effort: missing files are fine.
fn remove_sidecars(path: &Path) {
    for suffix in ["-wal", "-shm", "-journal"] {
        let sidecar = format!("{}{suffix}", path.display());
        let _ = std::fs::remove_file(sidecar);
    }
}

/// Exclusive migration guard (one per database path). At most one
/// process may create or migrate the database at a time: App + IME run
/// as separate processes on the same `state.db`. A live holder fails
/// the second opener fast instead of racing the plaintext→encrypted
/// replace; a stale lock (dead pid, e.g. crash mid-migration) is
/// reclaimed once. (Desktop #18 serializes this with
/// `private_fs::lock` on `db.init.lock`.)
struct MigrateGuard {
    path: PathBuf,
}

fn acquire_migrate_lock(path: &Path) -> rusqlite::Result<MigrateGuard> {
    let lock = path.with_extension("db.migrate.lock");
    match std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&lock)
    {
        Ok(mut f) => {
            use std::io::Write;
            let _ = write!(f, "{}", std::process::id());
            return Ok(MigrateGuard { path: lock.clone() });
        }
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                format!("migration lock: {e}").into(),
            ));
        }
    }
    // Lock file exists: live holder blocks us, stale lock is reclaimed.
    let stale = std::fs::read_to_string(&lock)
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok())
        .map(|pid| !std::path::Path::new(&format!("/proc/{pid}")).exists())
        .unwrap_or(true);
    if !stale {
        return Err(rusqlite::Error::ToSqlConversionFailure(
            "database migration already in progress in another process".into(),
        ));
    }
    let _ = std::fs::remove_file(&lock);
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&lock)
        .map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(format!("migration lock: {e}").into())
        })?;
    std::fs::write(&lock, std::process::id().to_string()).map_err(|e| {
        rusqlite::Error::ToSqlConversionFailure(format!("migration lock: {e}").into())
    })?;
    Ok(MigrateGuard { path: lock })
}

impl Drop for MigrateGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// Encrypt a legacy plaintext database in place via a temp file:
/// attach fresh encrypted db, export, atomically replace.
///
/// The staging file is pre-created mode 0600 before any encrypted
/// bytes land (desktop #18 pins the staging file 0600 for the same
/// reason), and stale plaintext sidecars are wiped after the rename.
fn migrate_plaintext_to_encrypted(path: &Path, key: &[u8; 32]) -> rusqlite::Result<()> {
    let tmp = path.with_extension("db.migrating");
    let _ = std::fs::remove_file(&tmp);
    remove_sidecars(&tmp);
    // Pre-create the staging file with owner-only permissions so the
    // encrypted copy never inherits a permissive umask.
    {
        use std::os::unix::fs::OpenOptionsExt;
        std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)
            .map_err(|e| {
                rusqlite::Error::ToSqlConversionFailure(format!("migration staging: {e}").into())
            })?;
    }
    let plain = Connection::open(path)?;
    plain.busy_timeout(std::time::Duration::from_secs(5))?;
    let attach = format!(
        "ATTACH DATABASE '{}' AS enc KEY \"x'{}'\"",
        tmp.to_string_lossy().replace('\'', "''"),
        hex_encode(key)
    );
    plain.execute_batch(&attach)?;
    plain.query_row("SELECT sqlcipher_export('enc')", [], |_| Ok(()))?;
    plain.execute_batch("DETACH enc")?;
    drop(plain);
    std::fs::rename(&tmp, path)
        .map_err(|e| rusqlite::Error::ToSqlConversionFailure(format!("replace db: {e}").into()))?;
    // The rename only moves the main file: stale plaintext sidecars
    // from the old db (and any WAL the temp db wrote) must go too.
    remove_sidecars(path);
    remove_sidecars(&tmp);
    Ok(())
}

impl Store {
    /// Open (or create + migrate) the database. Serialized across
    /// processes by the migration guard; the guard is held for the
    /// whole open so a second process can never observe a half-replaced
    /// file.
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        let _guard = acquire_migrate_lock(path)?;
        Self::open_locked(path)
    }

    fn open_locked(path: &Path) -> rusqlite::Result<Self> {
        let key = db_key()
            .map_err(|e| rusqlite::Error::ToSqlConversionFailure(format!("db key: {e}").into()))?;
        let fresh = !path.exists();
        let conn = Connection::open(path)?;
        // App + IME are separate processes on the same file: wait on
        // the writer instead of failing the open with SQLITE_BUSY.
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.execute_batch(&key_pragma(&key))?;
        // Probing a wrongly-keyed (or plaintext) file fails here.
        let probe = conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| {
            r.get::<_, i64>(0)
        });
        if !fresh && let Err(err) = probe {
            if is_not_a_database(&err) {
                drop(conn);
                eprintln!("[store] plaintext database found, encrypting in place");
                migrate_plaintext_to_encrypted(path, &key)?;
                return Self::open_locked(path);
            }
            return Err(err);
        }
        // Refuse to open a database written by a newer build instead of
        // silently running on a schema we do not understand.
        let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if version > 3 {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "database schema is newer than this MyWhisper build".into(),
            ));
        }
        // Snapshot the file identity now: sync export/apply paths abort
        // when the file was replaced under us (desktop #18
        // file-identity check).
        let file_identity = file_identity_of(path);
        conn.execute_batch(SCHEMA)?;
        // Best-effort migration for pre-existing databases: SQLite has
        // no ADD COLUMN IF NOT EXISTS, so a duplicate-column failure
        // just means the column is already there.
        for stmt in [
            "ALTER TABLE words ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE words ADD COLUMN updated_by TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE words ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE snippets ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE snippets ADD COLUMN updated_by TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE snippets ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE snippets ADD COLUMN replacement TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE history ADD COLUMN duration_secs INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE history ADD COLUMN raw_text TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE history ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE history ADD COLUMN discarded INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE devices ADD COLUMN address TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE devices ADD COLUMN addresses TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE devices ADD COLUMN trusted INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE devices ADD COLUMN pending INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE devices ADD COLUMN outgoing INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE devices ADD COLUMN introducer INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE devices ADD COLUMN last_sync INTEGER NOT NULL DEFAULT 0",
        ] {
            let _ = conn.execute(stmt, []);
        }
        // History gained a uuid primary key after v1 (which used an
        // integer id). Backfill deterministic uuids for legacy rows so
        // old databases keep working.
        if !has_column(&conn, "history", "uuid") {
            conn.execute("ALTER TABLE history ADD COLUMN uuid TEXT", [])?;
            conn.execute(
                "UPDATE history SET uuid = 'legacy-' || id || '-' || recorded_at
                 WHERE uuid IS NULL",
                [],
            )?;
        }
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS history_uuid ON history (uuid)",
            [],
        )?;
        // Rows predating raw_text carry the final text as raw (the
        // pipeline was passthrough then, so this is exact).
        let _ = conn.execute("UPDATE history SET raw_text = text WHERE raw_text = ''", []);
        Ok(Self {
            conn,
            file_identity,
        })
    }

    /// Durable sync-authorization epoch: bumped on every trust/topology
    /// change (peer add/remove/trust, revocation, reset). Sync rounds
    /// capture it up front and re-check it inside the commit
    /// transaction, so a concurrently revoked or disabled peer cannot
    /// slip a round through. (Desktop #18 `policy_epoch`.)
    pub fn sync_epoch(&self) -> i64 {
        self.conn
            .query_row(
                "SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'policy_epoch'",
                [],
                |r| r.get(0),
            )
            .unwrap_or(0)
    }

    /// Strict sync-enabled read for authorization gates: the settings
    /// blob must exist, parse, and explicitly enable sync. Anything
    /// else (missing/unreadable/decoded-off) refuses. Never derives
    /// policy from `load_settings()` defaults here.
    pub fn sync_enabled_explicit(&self) -> bool {
        let settings: Option<String> = self
            .conn
            .query_row("SELECT value FROM kv WHERE key = 'settings'", [], |r| {
                r.get(0)
            })
            .ok();
        settings
            .and_then(|json| serde_json::from_str::<serde_json::Value>(&json).ok())
            .and_then(|v| {
                v.get("sync")
                    .and_then(|s| s.get("enabled"))
                    .and_then(|e| e.as_bool())
            })
            .unwrap_or(false)
    }

    fn invalidate_sync(conn: &Connection) -> rusqlite::Result<()> {
        conn.execute(
            "INSERT INTO sync_meta (key, value) VALUES ('policy_epoch', '1')
             ON CONFLICT (key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
            [],
        )?;
        Ok(())
    }

    /// Abort when the database file was replaced under this connection
    /// (restore/re-migration swapped the inode). Export/apply call this
    /// inside their transaction so no round commits against a stale file.
    fn check_sync_identity(&self) -> rusqlite::Result<()> {
        let Some((dev, ino)) = self.file_identity else {
            return Ok(());
        };
        // Resolve via the connection path when available.
        let current = self
            .conn
            .path()
            .filter(|p| !p.is_empty())
            .and_then(|p| file_identity_of(Path::new(p)));
        match current {
            Some(cur) if cur == (dev, ino) => Ok(()),
            Some(_) => Err(rusqlite::Error::ToSqlConversionFailure(
                "database replaced during sync; retry".into(),
            )),
            // In-memory or pathless connections have nothing to check.
            None => Ok(()),
        }
    }

    /// Monotonic LWW clock: `MAX(now, words, snippets, stored) + 1`.
    /// Wall-clock writes alone break convergence (a future-dated peer
    /// would win forever; equal timestamps fall back to node-id order
    /// only when actually equal). Checked arithmetic: overflow near
    /// `i64::MAX` surfaces an error instead of wrapping negative and
    /// resurrecting deleted rows. (Desktop #18 `next_version`.)
    fn next_version(conn: &Connection) -> rusqlite::Result<i64> {
        let now = now_ms();
        let max_words: i64 = conn
            .query_row("SELECT COALESCE(MAX(updated_at), 0) FROM words", [], |r| {
                r.get(0)
            })
            .unwrap_or(0);
        let max_snippets: i64 = conn
            .query_row(
                "SELECT COALESCE(MAX(updated_at), 0) FROM snippets",
                [],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let stored: i64 = conn
            .query_row(
                "SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'clock'",
                [],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or(0);
        let base = now.max(max_words).max(max_snippets).max(stored);
        let next = base.checked_add(1).ok_or_else(|| {
            rusqlite::Error::ToSqlConversionFailure(
                "monotonic sync clock overflow; reset required".into(),
            )
        })?;
        conn.query_row(
            "INSERT INTO sync_meta (key, value) VALUES ('clock', ?1)
             ON CONFLICT (key) DO UPDATE SET value = MAX(CAST(value AS INTEGER), ?1)
             RETURNING CAST(value AS INTEGER)",
            [next],
            |r| r.get(0),
        )
    }

    /// Far-future versions (beyond a 7-day horizon) are rejected: a
    /// peer with a runaway clock must not poison LWW ordering forever.
    /// Old versions always pass (delayed/duplicate delivery is normal).
    /// Remote versions beyond now + horizon are rejected (runaway peer
    /// clock must not poison LWW ordering). Shared with the reset scrub.
    const VERSION_HORIZON_MS: i64 = 7 * 86_400_000;

    fn validate_version(version: i64) -> rusqlite::Result<()> {
        if version < 0 || version > now_ms().saturating_add(Self::VERSION_HORIZON_MS) {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "remote version outside accepted clock horizon".into(),
            ));
        }
        Ok(())
    }

    /// Checkpoint the WAL back into the database file. Call on clean
    /// shutdown so replicators always see a complete file.
    pub fn checkpoint(&self) -> rusqlite::Result<()> {
        self.conn
            .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))?;
        Ok(())
    }

    // -- settings (JSON blob under kv['settings']) -------------------------

    pub fn load_settings(&self) -> AppSettings {
        let json: Option<String> = self
            .conn
            .query_row("SELECT value FROM kv WHERE key = 'settings'", [], |row| {
                row.get(0)
            })
            .ok();
        let mut settings: AppSettings = json
            .and_then(|j| serde_json::from_str(&j).ok())
            .unwrap_or_default();
        // Pre-list configs seed one entry from legacy fields.
        settings.language_models.ensure_seed();
        settings.stt.ensure_seed();
        // Old default left Ollama thinking unset: force off (new
        // default). Runs every load, so existing installs migrate.
        for e in &mut settings.language_models.entries {
            if matches!(e.kind, crate::settings::LlmKind::Ollama) && e.thinking.is_none() {
                e.thinking = Some(false);
            }
        }
        // Typed tables are authoritative for dictionary content.
        settings.dictionary.words = self.load_words().unwrap_or_default();
        settings.dictionary.snippets = self.load_snippets().unwrap_or_default();
        // Pre-per-vendor databases stored Parakeet for everything
        // sherpa; Cohere gets its own vendor now.
        if matches!(
            settings.stt.local_provider,
            crate::settings::LocalSttProvider::Nvidia
        ) && settings.stt.parakeet_model == "cohere-transcribe-03-2026"
        {
            settings.stt.local_provider = crate::settings::LocalSttProvider::Cohere;
        }
        settings
    }

    /// Persist the settings blob. Versioned tables (words/snippets)
    /// are NOT touched here: they are authoritative and already carry
    /// their own timestamps from CRUD/merge paths. Rewriting them on
    /// every settings save would mint fresh versions and could clobber
    /// a peer's concurrent edit on the next sync.
    pub fn save_settings(&self, settings: &AppSettings) -> rusqlite::Result<()> {
        let json = serde_json::to_string(settings).map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(format!("settings serialize: {e}").into())
        })?;
        self.conn.execute(
            "INSERT INTO kv (key, value) VALUES ('settings', ?1)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            [&json],
        )?;
        Ok(())
    }

    // -- dictionary ----------------------------------------------------------

    pub fn load_words(&self) -> rusqlite::Result<Vec<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT word FROM words WHERE deleted = 0 ORDER BY word")?;
        stmt.query_map([], |row| row.get(0))?.collect()
    }

    /// Local write: add or resurrect a word. Versioned with the
    /// monotonic clock (never wall-clock alone) so a skewed clock or a
    /// racing peer cannot silently win.
    pub fn upsert_word(&self, word: &str, node_id: &str) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let version = Self::next_version(&tx)?;
        Self::apply_words_on(
            &tx,
            &[WordRecord {
                word: word.to_string(),
                updated_at: version,
                updated_by: node_id.to_string(),
                deleted: false,
            }],
        )?;
        bump_gen(&tx);
        tx.commit()?;
        bump_data_gen();
        Ok(())
    }

    /// Local write: tombstone a word (propagates as a delete).
    pub fn delete_word(&self, word: &str, node_id: &str) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let version = Self::next_version(&tx)?;
        Self::apply_words_on(
            &tx,
            &[WordRecord {
                word: word.to_string(),
                updated_at: version,
                updated_by: node_id.to_string(),
                deleted: true,
            }],
        )?;
        bump_gen(&tx);
        tx.commit()?;
        bump_data_gen();
        Ok(())
    }

    pub fn load_snippets(&self) -> rusqlite::Result<Vec<Snippet>> {
        let mut stmt = self.conn.prepare(
            "SELECT trigger, replacement FROM snippets WHERE deleted = 0 ORDER BY trigger",
        )?;
        stmt.query_map([], |row| {
            Ok(Snippet {
                trigger: row.get(0)?,
                replacement: row.get(1)?,
            })
        })?
        .collect()
    }

    /// Local write: add/update a snippet (monotonic version, like words).
    pub fn upsert_snippet(
        &self,
        trigger: &str,
        replacement: &str,
        node_id: &str,
    ) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let version = Self::next_version(&tx)?;
        Self::apply_snippets_on(
            &tx,
            &[SnippetRecord {
                trigger: trigger.to_string(),
                replacement: replacement.to_string(),
                updated_at: version,
                updated_by: node_id.to_string(),
                deleted: false,
            }],
        )?;
        bump_gen(&tx);
        tx.commit()?;
        bump_data_gen();
        Ok(())
    }

    /// Local write: tombstone a snippet.
    pub fn delete_snippet(&self, trigger: &str, node_id: &str) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let version = Self::next_version(&tx)?;
        Self::apply_snippets_on(
            &tx,
            &[SnippetRecord {
                trigger: trigger.to_string(),
                replacement: String::new(),
                updated_at: version,
                updated_by: node_id.to_string(),
                deleted: true,
            }],
        )?;
        bump_gen(&tx);
        tx.commit()?;
        bump_data_gen();
        Ok(())
    }

    // -- history (append-only, union by uuid) ---------------------------------

    pub fn load_history(
        &self,
        limit: usize,
        include_discarded: bool,
    ) -> rusqlite::Result<Vec<HistoryEntry>> {
        let mut stmt = self.conn.prepare(
            "SELECT uuid, text, raw_text, datetime(recorded_at, 'unixepoch'), words,
                    duration_secs, recorded_at, discarded
             FROM history WHERE deleted = 0 AND (discarded = 0 OR ?1)
             ORDER BY recorded_at DESC LIMIT ?2",
        )?;
        stmt.query_map([include_discarded as i64, limit as i64], |row| {
            Ok(HistoryEntry {
                uuid: row.get(0)?,
                text: row.get(1)?,
                raw_text: row.get(2)?,
                date_label: row.get(3)?,
                word_count: row.get(4)?,
                duration_secs: row.get(5)?,
                recorded_at: row.get(6)?,
                discarded: row.get(7)?,
            })
        })?
        .collect()
    }

    /// Local delete: tombstone + redact, so the row stays deleted across
    /// sync (union-by-uuid would otherwise resurrect it from a peer)
    /// and no plaintext survives locally or on the wire.
    pub fn delete_history(&self, uuid: &str) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let n = tx.execute(
            "UPDATE history
             SET deleted = 1, text = '', raw_text = '', words = 0, duration_secs = 0
             WHERE uuid = ?1 AND deleted = 0",
            [uuid],
        )?;
        if n > 0 {
            Self::invalidate_sync(&tx)?;
            bump_gen(&tx);
        }
        tx.commit()?;
        if n > 0 {
            bump_data_gen();
        }
        Ok(())
    }

    /// Redact history rows with no duration (pre-fix data that pins
    /// words-per-minute at 0). Tombstones, not deletes: dropping rows
    /// would let a peer resurrect them on the next sync.
    pub fn delete_zero_duration_history(&self) -> rusqlite::Result<usize> {
        self.redact_history_where("duration_secs <= 0", [])
    }

    /// Clear all transcript payloads while preserving replicated
    /// deletion identities (tombstones). Returns rows redacted.
    pub fn clear_history(&self) -> rusqlite::Result<usize> {
        self.redact_history_where("1", [])
    }

    fn redact_history_where(
        &self,
        predicate: &str,
        params: impl rusqlite::Params,
    ) -> rusqlite::Result<usize> {
        let tx = self.conn.unchecked_transaction()?;
        let count = tx.execute(
            &format!(
                "UPDATE history
                 SET deleted = 1, text = '', raw_text = '', words = 0, duration_secs = 0
                 WHERE deleted = 0 AND ({predicate})"
            ),
            params,
        )?;
        if count > 0 {
            Self::invalidate_sync(&tx)?;
            bump_gen(&tx);
        }
        tx.commit()?;
        if count > 0 {
            bump_data_gen();
        }
        Ok(count)
    }

    /// One-shot service commands (GUI -> service): "stop"/"start"
    /// STT servers. Consumed (deleted) on read; stale entries die at
    /// service boot without running.
    pub fn set_server_cmd(&self, cmd: &str) -> rusqlite::Result<()> {
        self.conn.execute(
            "INSERT INTO kv (key, value) VALUES ('stt_server_cmd', ?1)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            [cmd],
        )?;
        bump_gen(&self.conn);
        Ok(())
    }

    pub fn take_server_cmd(&self) -> Option<String> {
        let cmd: String = self
            .conn
            .query_row(
                "SELECT value FROM kv WHERE key = 'stt_server_cmd'",
                [],
                |row| row.get(0),
            )
            .ok()?;
        let _ = self
            .conn
            .execute("DELETE FROM kv WHERE key = 'stt_server_cmd'", []);
        bump_gen(&self.conn);
        Some(cmd)
    }

    /// Distinct local calendar days with at least one entry,
    /// newest first. Feeds the streak calculation.
    pub fn active_days(&self) -> rusqlite::Result<Vec<i64>> {
        let mut stmt = self
            .conn
            .prepare("SELECT DISTINCT CAST(strftime('%s', recorded_at, 'unixepoch', 'localtime') AS INTEGER) / 86400 FROM history WHERE deleted = 0 AND discarded = 0 ORDER BY 1 DESC")?;
        stmt.query_map([], |row| row.get(0))?.collect()
    }

    /// Per-day aggregates for the insights heatmap: `(epoch_day,
    /// total_words, dictation_count)`, ascending, trailing `days`.
    /// Single indexed range scan -- no per-day queries.
    pub fn daily_aggregates(&self, days: u32) -> rusqlite::Result<Vec<(i64, i64, i64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT CAST(strftime('%s', recorded_at, 'unixepoch', 'localtime') AS INTEGER) / 86400 AS day,
                    COALESCE(SUM(words), 0),
                    COUNT(*)
             FROM history
             WHERE deleted = 0 AND discarded = 0 AND recorded_at >= strftime('%s', 'now', ?1)
             GROUP BY day
             ORDER BY day",
        )?;
        stmt.query_map([format!("-{days} days")], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        })?
        .collect()
    }

    /// Activity totals use the whole saved history. Zero-duration legacy
    /// entries count toward words/dictations but cannot measure speaking speed.
    pub fn usage_stats(&self) -> rusqlite::Result<crate::settings::UsageStats> {
        self.conn.query_row(
            "SELECT COALESCE(SUM(words), 0), COUNT(*),
                    COALESCE(SUM(CASE WHEN duration_secs > 0 THEN words ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN duration_secs > 0 THEN duration_secs ELSE 0 END), 0)
             FROM history WHERE deleted = 0 AND discarded = 0",
            [],
            |row| {
                let words = row.get::<_, i64>(0)?.max(0) as u64;
                let count = row.get::<_, i64>(1)?.max(0) as u64;
                let measured_words = row.get::<_, i64>(2)?.max(0) as u64;
                let seconds = row.get::<_, i64>(3)?.max(0) as u64;
                Ok(crate::settings::UsageStats {
                    words_spoken: words,
                    dictations: count,
                    words_per_minute: if seconds > 0 {
                        measured_words as f64 * 60.0 / seconds as f64
                    } else {
                        0.0
                    },
                    streak_days: 0,
                })
            },
        )
    }

    /// Local write: append one entry. The uuid embeds the node id so
    /// concurrently recorded entries never collide.
    pub fn add_history(
        &self,
        raw_text: &str,
        text: &str,
        words: u32,
        duration_secs: i64,
        node_id: &str,
    ) -> rusqlite::Result<String> {
        self.add_history_full(raw_text, text, words, duration_secs, false, node_id)
    }

    /// Discarded (cancelled-midway) row: no text, no words, but a real
    /// duration. No audio is ever stored (RAM-only capture posture —
    /// stricter than the reference, which keeps the clip).
    pub fn add_discarded(&self, duration_secs: i64, node_id: &str) -> rusqlite::Result<String> {
        self.add_history_full("", "", 0, duration_secs, true, node_id)
    }

    fn add_history_full(
        &self,
        raw_text: &str,
        text: &str,
        words: u32,
        duration_secs: i64,
        discarded: bool,
        node_id: &str,
    ) -> rusqlite::Result<String> {
        let seq = HISTORY_SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let uuid = format!("{}-{}-{seq}", &node_id[..node_id.len().min(16)], now_ms());
        self.apply_history(&[HistoryRecord {
            uuid: uuid.clone(),
            text: text.to_string(),
            raw_text: raw_text.to_string(),
            words,
            duration_secs,
            deleted: false,
            discarded,
            recorded_at: now_ms() / 1000,
        }])?;
        Ok(uuid)
    }

    /// Reset all user data: history (incl. tombstones), words,
    /// snippets, stored settings. Devices/sync_meta survive (topology,
    /// not data). Callers reset in-memory state + persist defaults.
    /// Reset all user data. Tombstones and revocations survive (dropping
    /// them would resurrect peers' rows on the next sync), the sync
    /// epoch advances so stale authorized exports fail, and sync comes
    /// back disabled — never re-enabled by a stale default. One
    /// transaction: the reset is all-or-nothing. (Desktop #18
    /// `reset_on`, minus the audio-vault/capture-epoch parts, which
    /// have no Android consumer.)
    pub fn reset_all_data(&self) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        // FIRST drop excursion state, THEN mint the tombstone version:
        // next_version reads MAX(words/snippets) + stored clock, so a
        // poisoned clock or far-future rows would otherwise push the
        // tombstones outside peers' horizon and the deletes would never
        // land (resurrection on next sync).
        tx.execute("DELETE FROM sync_meta WHERE key = 'clock'", [])?;
        let horizon = now_ms().saturating_add(Self::VERSION_HORIZON_MS);
        tx.execute(
            "UPDATE words SET updated_at = 0 WHERE updated_at > ?1",
            [horizon],
        )?;
        tx.execute(
            "UPDATE snippets SET updated_at = 0 WHERE updated_at > ?1",
            [horizon],
        )?;
        let version = Self::next_version(&tx)?;
        tx.execute(
            "UPDATE history
             SET deleted = 1, text = '', raw_text = '', words = 0, duration_secs = 0",
            [],
        )?;
        tx.execute("UPDATE words SET deleted = 1, updated_at = ?1", [version])?;
        tx.execute(
            "UPDATE snippets SET deleted = 1, replacement = '', updated_at = ?1",
            [version],
        )?;
        tx.execute(
            "INSERT OR IGNORE INTO revoked_devices SELECT node_id FROM devices",
            [],
        )?;
        tx.execute("DELETE FROM devices", [])?;
        Self::invalidate_sync(&tx)?;
        tx.execute("DELETE FROM kv", [])?;
        let mut defaults = AppSettings::default();
        defaults.sync.enabled = false;
        let json = serde_json::to_string(&defaults).map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(format!("settings serialize: {e}").into())
        })?;
        tx.execute(
            "INSERT INTO kv (key, value) VALUES ('settings', ?1)",
            [json],
        )?;
        bump_gen(&tx);
        tx.commit()?;
        bump_data_gen();
        Ok(())
    }

    /// Redact rows older than `days` (auto-delete transcripts).
    /// Tombstones survive: dropping them would resurrect peers' rows.
    pub fn purge_old_history(&self, days: u32) -> rusqlite::Result<usize> {
        self.redact_history_where(
            "recorded_at < strftime('%s', 'now', ?1)",
            [format!("-{days} days")],
        )
    }

    // -- sync: export + LWW apply --------------------------------------------

    pub fn export_words(&self) -> rusqlite::Result<Vec<WordRecord>> {
        let mut stmt = self
            .conn
            .prepare("SELECT word, updated_at, updated_by, deleted FROM words")?;
        stmt.query_map([], |row| {
            Ok(WordRecord {
                word: row.get(0)?,
                updated_at: row.get(1)?,
                updated_by: row.get(2)?,
                deleted: row.get::<_, i64>(3)? != 0,
            })
        })?
        .collect()
    }

    pub fn export_snippets(&self) -> rusqlite::Result<Vec<SnippetRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT trigger, replacement, updated_at, updated_by, deleted FROM snippets",
        )?;
        stmt.query_map([], |row| {
            Ok(SnippetRecord {
                trigger: row.get(0)?,
                replacement: row.get(1)?,
                updated_at: row.get(2)?,
                updated_by: row.get(3)?,
                deleted: row.get::<_, i64>(4)? != 0,
            })
        })?
        .collect()
    }

    pub fn export_history(&self) -> rusqlite::Result<Vec<HistoryRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT uuid, text, raw_text, words, duration_secs, deleted, discarded, recorded_at
             FROM history",
        )?;
        stmt.query_map([], |row| {
            Ok(HistoryRecord {
                uuid: row.get(0)?,
                text: row.get(1)?,
                raw_text: row.get(2)?,
                words: row.get(3)?,
                duration_secs: row.get(4)?,
                deleted: row.get(5)?,
                discarded: row.get(6)?,
                recorded_at: row.get(7)?,
            })
        })?
        .collect()
    }

    /// Merge remote word records. Union converges; concurrent edits to
    /// the same word resolve to the newer `(updated_at, updated_by)`.
    /// One transaction, atomic LWW in SQL (no read-then-write race
    /// between the App and IME connections).
    pub fn apply_words(&self, records: &[WordRecord]) -> rusqlite::Result<()> {
        // Horizon check lives at the remote entry only: locally minted
        // versions (upsert/delete via next_version) must never lock out
        // local writes after a clock excursion + correction.
        for r in records {
            Self::validate_version(r.updated_at)?;
        }
        let tx = self.conn.unchecked_transaction()?;
        let changed = Self::apply_words_on(&tx, records)?;
        if changed > 0 {
            bump_gen(&tx);
        }
        tx.commit()?;
        if changed > 0 {
            bump_data_gen();
        }
        Ok(())
    }

    fn apply_words_on(conn: &Connection, records: &[WordRecord]) -> rusqlite::Result<usize> {
        let mut changed = 0;
        for r in records {
            changed += conn.execute(
                "INSERT INTO words (word, updated_at, updated_by, deleted)
                 VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT (word) DO UPDATE SET
                   updated_at = excluded.updated_at,
                   updated_by = excluded.updated_by,
                   deleted = excluded.deleted
                 WHERE (excluded.updated_at, excluded.updated_by)
                     > (words.updated_at, words.updated_by)",
                (&r.word, r.updated_at, &r.updated_by, r.deleted as i64),
            )?;
        }
        Ok(changed)
    }

    pub fn apply_snippets(&self, records: &[SnippetRecord]) -> rusqlite::Result<()> {
        // Remote entry: horizon-checked (see apply_words).
        for r in records {
            Self::validate_version(r.updated_at)?;
        }
        let tx = self.conn.unchecked_transaction()?;
        let changed = Self::apply_snippets_on(&tx, records)?;
        if changed > 0 {
            bump_gen(&tx);
        }
        tx.commit()?;
        if changed > 0 {
            bump_data_gen();
        }
        Ok(())
    }

    fn apply_snippets_on(conn: &Connection, records: &[SnippetRecord]) -> rusqlite::Result<usize> {
        let mut changed = 0;
        for r in records {
            changed += conn.execute(
                "INSERT INTO snippets (trigger, replacement, updated_at, updated_by, deleted)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT (trigger) DO UPDATE SET
                   replacement = excluded.replacement,
                   updated_at = excluded.updated_at,
                   updated_by = excluded.updated_by,
                   deleted = excluded.deleted
                 WHERE (excluded.updated_at, excluded.updated_by)
                     > (snippets.updated_at, snippets.updated_by)",
                (
                    &r.trigger,
                    if r.deleted { "" } else { &r.replacement },
                    r.updated_at,
                    &r.updated_by,
                    r.deleted as i64,
                ),
            )?;
        }
        Ok(changed)
    }

    /// Merge remote history: union on uuid with monotonic tombstones.
    /// A delete/discard flag, once set on either side, sticks, and the
    /// payload is redacted to empty the moment either side tombstones —
    /// a peer holding the old plaintext row can never resurrect text.
    pub fn apply_history(&self, records: &[HistoryRecord]) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let changed = Self::apply_history_on(&tx, records)?;
        if changed > 0 {
            bump_gen(&tx);
        }
        tx.commit()?;
        if changed > 0 {
            bump_data_gen();
        }
        Ok(())
    }

    fn apply_history_on(conn: &Connection, records: &[HistoryRecord]) -> rusqlite::Result<usize> {
        let mut changed = 0;
        for r in records {
            changed += conn.execute(
                "INSERT INTO history (uuid, text, raw_text, words, duration_secs, deleted, discarded, recorded_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT (uuid) DO UPDATE SET
                   deleted = history.deleted OR excluded.deleted,
                   discarded = history.discarded OR excluded.discarded,
                   text = CASE WHEN history.deleted OR excluded.deleted
                                    OR history.discarded OR excluded.discarded
                               THEN '' ELSE history.text END,
                   raw_text = CASE WHEN history.deleted OR excluded.deleted
                                        OR history.discarded OR excluded.discarded
                                   THEN '' ELSE history.raw_text END,
                   words = CASE WHEN history.deleted OR excluded.deleted
                                     OR history.discarded OR excluded.discarded
                                THEN 0 ELSE history.words END,
                   duration_secs = CASE WHEN history.deleted OR excluded.deleted THEN 0
                                        WHEN history.discarded OR excluded.discarded
                                        THEN MAX(history.duration_secs, excluded.duration_secs)
                                        ELSE history.duration_secs END
                 WHERE (excluded.deleted AND NOT history.deleted)
                    OR (excluded.discarded AND NOT history.discarded)",
                (
                    &r.uuid,
                    if r.deleted || r.discarded {
                        ""
                    } else {
                        &r.text
                    },
                    if r.deleted || r.discarded {
                        ""
                    } else {
                        &r.raw_text
                    },
                    if r.deleted || r.discarded { 0 } else { r.words },
                    if r.deleted { 0 } else { r.duration_secs },
                    r.deleted,
                    r.discarded,
                    r.recorded_at,
                ),
            )?;
        }
        Ok(changed)
    }

    /// All three content tables in one transaction: a sync round either
    /// commits everything or leaves everything unchanged (no half-merged
    /// rounds visible to the UI or the next export).
    pub fn apply_content(
        &self,
        words: &[WordRecord],
        snippets: &[SnippetRecord],
        history: &[HistoryRecord],
    ) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let changed = Self::apply_words_on(&tx, words)?
            + Self::apply_snippets_on(&tx, snippets)?
            + Self::apply_history_on(&tx, history)?;
        if changed > 0 {
            bump_gen(&tx);
        }
        tx.commit()?;
        if changed > 0 {
            bump_data_gen();
        }
        Ok(())
    }

    /// One authorized content snapshot: settings/trust/content are read
    /// inside a single transaction, so an export can never mix a stale
    /// trust decision with fresh content — or default away an export
    /// failure. Callers capture `sync_epoch()` first and pass it back
    /// here; any trust/topology change in between fails the export.
    /// (Desktop #18 `export_authorized`; the 16 MiB legacy single-shot
    /// cap is dropped — phone/desktop negotiate full payloads here —
    /// see PR description.)
    pub fn export_authorized(
        &self,
        peer_id: &str,
        epoch: i64,
    ) -> rusqlite::Result<ContentSnapshot> {
        let tx = self.conn.unchecked_transaction()?;
        self.check_sync_identity()?;
        Self::require_sync_authorized(&tx, peer_id, epoch)?;
        let snapshot = ContentSnapshot {
            words: self.export_words()?,
            snippets: self.export_snippets()?,
            history: self.export_history()?,
            peers: self.export_gossip()?,
        };
        tx.commit()?;
        Ok(snapshot)
    }

    fn require_sync_authorized(
        conn: &Connection,
        peer_id: &str,
        epoch: i64,
    ) -> rusqlite::Result<()> {
        let current_epoch: Option<i64> = conn
            .query_row(
                "SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'policy_epoch'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        // No permissive defaults: sync must be explicitly enabled in a
        // readable settings blob. Missing/unreadable/decoded-off all
        // refuse.
        let settings: Option<String> = conn
            .query_row("SELECT value FROM kv WHERE key = 'settings'", [], |r| {
                r.get(0)
            })
            .optional()?;
        let enabled = settings
            .and_then(|json| serde_json::from_str::<serde_json::Value>(&json).ok())
            .and_then(|v| v.get("sync")?.get("enabled")?.as_bool())
            .unwrap_or(false);
        let peer: Option<Peer> = conn
            .query_row(
                "SELECT node_id, name, address, addresses, trusted, pending,
                        outgoing, introducer, last_seen, last_sync
                 FROM devices WHERE node_id = ?1",
                [peer_id],
                Self::peer_from_row,
            )
            .optional()?;
        let revoked: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM revoked_devices WHERE node_id = ?1)",
            [peer_id],
            |r| r.get(0),
        )?;
        if !enabled
            || revoked
            || current_epoch.unwrap_or(0) != epoch
            || !peer.is_some_and(|p| p.trusted)
        {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "sync authorization changed".into(),
            ));
        }
        Ok(())
    }

    /// One authorized content round: re-checks authorization INSIDE the
    /// commit transaction (a revocation racing the round cannot slip
    /// through), applies words/snippets/history/gossip atomically, and
    /// records the sync. Returns gossip-added peers.
    /// (Desktop #18 `apply_sync_round`.)
    pub fn apply_sync_round(
        &self,
        peer_id: &str,
        epoch: i64,
        self_id: &str,
        content: (&[WordRecord], &[SnippetRecord], &[HistoryRecord]),
        gossip: &[PeerGossip],
    ) -> rusqlite::Result<usize> {
        let tx = self.conn.unchecked_transaction()?;
        self.check_sync_identity()?;
        Self::require_sync_authorized(&tx, peer_id, epoch)?;
        // Remote entry: horizon-checked before anything commits.
        for r in content.0 {
            Self::validate_version(r.updated_at)?;
        }
        for r in content.1 {
            Self::validate_version(r.updated_at)?;
        }
        let peer: Option<Peer> = tx
            .query_row(
                "SELECT node_id, name, address, addresses, trusted, pending,
                        outgoing, introducer, last_seen, last_sync
                 FROM devices WHERE node_id = ?1",
                [peer_id],
                Self::peer_from_row,
            )
            .optional()?;
        let changed = Self::apply_words_on(&tx, content.0)?
            + Self::apply_snippets_on(&tx, content.1)?
            + Self::apply_history_on(&tx, content.2)?;
        let added =
            Self::apply_gossip_on(&tx, gossip, peer.is_some_and(|p| p.introducer), self_id)?;
        tx.execute(
            "UPDATE devices SET last_seen = ?2, last_sync = ?2 WHERE node_id = ?1",
            (peer_id, now_ms() / 1000),
        )?;
        if changed > 0 || added > 0 {
            bump_gen(&tx);
        }
        tx.commit()?;
        if changed > 0 || added > 0 {
            bump_data_gen();
        }
        Ok(added)
    }

    pub fn open_memory() -> rusqlite::Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self {
            conn,
            file_identity: None,
        })
    }
}

/// One authorized content snapshot exchanged in a sync round.
#[derive(Debug, Clone, Default)]
pub struct ContentSnapshot {
    pub words: Vec<WordRecord>,
    pub snippets: Vec<SnippetRecord>,
    pub history: Vec<HistoryRecord>,
    pub peers: Vec<PeerGossip>,
}

/// Versioned word row exchanged between nodes.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct WordRecord {
    pub word: String,
    pub updated_at: i64,
    pub updated_by: String,
    pub deleted: bool,
}

/// Versioned snippet row exchanged between nodes.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct SnippetRecord {
    pub trigger: String,
    pub replacement: String,
    pub updated_at: i64,
    pub updated_by: String,
    pub deleted: bool,
}

/// Append-only history row exchanged between nodes.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct HistoryRecord {
    pub uuid: String,
    pub text: String,
    pub raw_text: String,
    pub words: u32,
    pub duration_secs: i64,
    pub deleted: bool,
    pub discarded: bool,
    pub recorded_at: i64,
}

/// Trusted peer row.
///
/// `address` is the legacy single last-known address; `addresses` is
/// the JSON list fed by mDNS, gossip and direct contact. Dial tries
/// all of them. `pending` marks an incoming request awaiting a
/// decision (incoming-only: our own outgoing requests never set it);
/// `outgoing` marks a request WE sent that awaits their accept;
/// `introducer` peers auto-add the devices they vouch for.
#[derive(Debug, Clone)]
pub struct Peer {
    pub node_id: String,
    pub name: String,
    pub address: String,
    pub addresses: Vec<String>,
    pub trusted: bool,
    pub pending: bool,
    pub outgoing: bool,
    pub introducer: bool,
    pub last_seen: i64,
    pub last_sync: i64,
}

impl Peer {
    /// All known dial targets, most-recent-first guess: explicit list
    /// first, then the legacy single address.
    pub fn dial_targets(&self) -> Vec<String> {
        let mut out = self.addresses.clone();
        if !self.address.is_empty() && !out.contains(&self.address) {
            out.push(self.address.clone());
        }
        out
    }
}

fn parse_addresses(raw: &str, legacy: &str) -> Vec<String> {
    let mut out: Vec<String> = serde_json::from_str(raw).unwrap_or_default();
    if !legacy.is_empty() && !out.contains(&legacy.to_string()) {
        out.push(legacy.to_string());
    }
    out
}

fn dump_addresses(addresses: &[String]) -> String {
    serde_json::to_string(addresses).unwrap_or_else(|_| "[]".to_string())
}

/// Merge new observations into a stored address list (dedup, cap 8).
/// Fresh observations win the front slots; empty or oversized entries
/// never reach storage (bounded input from the network).
fn merge_addresses(current: &[String], seen: &[String]) -> Vec<String> {
    let mut out: Vec<String> = seen
        .iter()
        .filter(|a| !a.is_empty() && a.len() <= 256)
        .take(8)
        .cloned()
        .collect();
    for a in current {
        if !out.contains(a) {
            out.push(a.clone());
        }
    }
    out.truncate(8);
    out
}

impl Store {
    fn peer_from_row(row: &rusqlite::Row) -> rusqlite::Result<Peer> {
        let address: String = row.get(2)?;
        let addresses_raw: String = row.get(3)?;
        Ok(Peer {
            node_id: row.get(0)?,
            name: row.get(1)?,
            address: address.clone(),
            addresses: parse_addresses(&addresses_raw, &address),
            trusted: row.get::<_, i64>(4)? != 0,
            pending: row.get::<_, i64>(5)? != 0,
            outgoing: row.get::<_, i64>(6)? != 0,
            introducer: row.get::<_, i64>(7)? != 0,
            last_seen: row.get(8)?,
            last_sync: row.get(9)?,
        })
    }

    pub fn list_peers(&self) -> rusqlite::Result<Vec<Peer>> {
        let mut stmt = self.conn.prepare(
            "SELECT node_id, name, address, addresses, trusted, pending,
                    outgoing, introducer, last_seen, last_sync
             FROM devices ORDER BY name",
        )?;
        stmt.query_map([], Self::peer_from_row)?.collect()
    }

    pub fn get_peer(&self, node_id: &str) -> rusqlite::Result<Option<Peer>> {
        let peer = self
            .conn
            .query_row(
                "SELECT node_id, name, address, addresses, trusted, pending,
                        outgoing, introducer, last_seen, last_sync
                 FROM devices WHERE node_id = ?1",
                [node_id],
                Self::peer_from_row,
            )
            .ok();
        Ok(peer)
    }

    /// Insert or update a peer row (manual add / edits). Trust changes
    /// advance the sync epoch so in-flight authorized exports for this
    /// peer fail closed. Re-trusting clears the revocation and any
    /// outgoing request marker (mutual contact resolved); introduction
    /// authority is NOT granted here — it stays an explicit local choice
    /// (`set_peer_introducer`) or comes from accepting a request
    /// (`decide_request`), and is revoked when trust is removed.
    pub fn upsert_peer(
        &self,
        node_id: &str,
        name: &str,
        address: &str,
        trusted: bool,
    ) -> rusqlite::Result<()> {
        if !valid_node_id(node_id) {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "invalid peer identity".into(),
            ));
        }
        let tx = self.conn.unchecked_transaction()?;
        Self::invalidate_sync(&tx)?;
        if trusted {
            tx.execute("DELETE FROM revoked_devices WHERE node_id = ?1", [node_id])?;
        }
        let addresses = if address.is_empty() {
            "[]".to_string()
        } else {
            dump_addresses(&[address.to_string()])
        };
        tx.execute(
            "INSERT INTO devices
               (node_id, name, address, addresses, trusted, pending, outgoing, introducer,
                last_seen, last_sync)
             VALUES (?1, ?2, ?3, ?4, ?5, 0, 0, 0, 0, 0)
             ON CONFLICT (node_id) DO UPDATE SET
               name = excluded.name,
               address = excluded.address,
               addresses = excluded.addresses,
               trusted = excluded.trusted,
               introducer = CASE WHEN excluded.trusted THEN devices.introducer ELSE 0 END,
               pending = 0,
               outgoing = 0",
            (node_id, name, address, addresses, trusted as i64),
        )?;
        tx.commit()
    }

    /// Revoke a peer: the row is removed AND the id joins the durable
    /// deny-list, so gossip or a stale database can never re-add it.
    /// Trust changes advance the sync epoch.
    pub fn remove_peer(&self, node_id: &str) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        Self::invalidate_sync(&tx)?;
        tx.execute(
            "INSERT OR IGNORE INTO revoked_devices (node_id) VALUES (?1)",
            [node_id],
        )?;
        tx.execute("DELETE FROM devices WHERE node_id = ?1", [node_id])?;
        tx.commit()
    }

    pub fn is_revoked(&self, node_id: &str) -> rusqlite::Result<bool> {
        self.conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM revoked_devices WHERE node_id = ?1)",
            [node_id],
            |r| r.get(0),
        )
    }

    /// Introduction authority is an explicit local choice, independent
    /// of trust: granting it to an untrusted peer is refused.
    pub fn set_peer_introducer(&self, node_id: &str, enabled: bool) -> rusqlite::Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        let peer: Option<Peer> = tx
            .query_row(
                "SELECT node_id, name, address, addresses, trusted, pending,
                        outgoing, introducer, last_seen, last_sync
                 FROM devices WHERE node_id = ?1",
                [node_id],
                Self::peer_from_row,
            )
            .optional()?;
        let Some(peer) = peer else {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "peer does not exist".into(),
            ));
        };
        if enabled && !peer.trusted {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "trust the peer before granting introduction authority".into(),
            ));
        }
        tx.execute(
            "UPDATE devices SET introducer = ?2 WHERE node_id = ?1",
            (node_id, enabled as i64),
        )?;
        Self::invalidate_sync(&tx)?;
        bump_gen(&tx);
        tx.commit()
    }

    /// Rename without touching trust, pending, outgoing or introducer flags.
    pub fn rename_peer(&self, node_id: &str, name: &str) -> rusqlite::Result<()> {
        self.conn.execute(
            "UPDATE devices SET name = ?2 WHERE node_id = ?1",
            (node_id, name),
        )?;
        Ok(())
    }

    /// Replace the discovered address list for a peer.
    pub fn set_peer_addresses(&self, node_id: &str, addresses: &[String]) -> rusqlite::Result<()> {
        self.conn.execute(
            "UPDATE devices SET addresses = ?2 WHERE node_id = ?1",
            (node_id, dump_addresses(addresses)),
        )?;
        Ok(())
    }

    /// Record a successful exchange with a peer.
    pub fn touch_peer_synced(&self, node_id: &str, name: &str) -> rusqlite::Result<()> {
        let now = now_ms() / 1000;
        self.conn.execute(
            "INSERT INTO devices (node_id, name, last_seen, last_sync)
             VALUES (?1, ?2, ?3, ?3)
             ON CONFLICT (node_id) DO UPDATE SET
               name = excluded.name,
               last_seen = excluded.last_seen,
               last_sync = excluded.last_sync",
            (node_id, name, now),
        )?;
        Ok(())
    }

    /// Record inbound contact. Unknown nodes become pending requests
    /// (never auto-trusted, never outgoing); known nodes refresh
    /// addresses/last_seen. Revoked or malformed identities are refused
    /// outright — they must never regain even a pending row.
    pub fn record_seen(
        &self,
        node_id: &str,
        name: &str,
        addresses: &[String],
    ) -> rusqlite::Result<bool> {
        if !valid_node_id(node_id) || self.is_revoked(node_id)? {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "invalid or revoked peer".into(),
            ));
        }
        let now = now_ms() / 1000;
        let existing = self.get_peer(node_id)?;
        match existing {
            None => {
                self.conn.execute(
                    "INSERT INTO devices
                       (node_id, name, addresses, trusted, pending, outgoing, last_seen)
                     VALUES (?1, ?2, ?3, 0, 1, 0, ?4)",
                    (node_id, name, dump_addresses(addresses), now),
                )?;
                Ok(true)
            }
            Some(peer) => {
                let merged = merge_addresses(&peer.addresses, addresses);
                let peer_name = if name.is_empty() {
                    peer.name
                } else {
                    name.to_string()
                };
                self.conn.execute(
                    "UPDATE devices SET name = ?2, addresses = ?3, last_seen = ?4
                     WHERE node_id = ?1",
                    (node_id, peer_name, dump_addresses(&merged), now),
                )?;
                Ok(false)
            }
        }
    }

    /// Responder-side ONLY latch for an incoming pairing dial: sets
    /// `pending` on an untrusted row. Unknown dialers were already parked
    /// by `record_seen`, but known rows never gain `pending` there — so
    /// without this latch their request would stay invisible forever.
    /// Trusted rows are untouched. Revoked or malformed identities are
    /// refused.
    ///
    /// Never call on initiator paths: our own dial must not self-approve
    /// into an incoming request (the inverted-state hole). The responder
    /// calls this after the identity proof verifies, so forgeries never
    /// latch.
    pub fn mark_incoming_request(&self, node_id: &str) -> rusqlite::Result<bool> {
        if !valid_node_id(node_id) || self.is_revoked(node_id)? {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "invalid or revoked peer".into(),
            ));
        }
        let n = self.conn.execute(
            "UPDATE devices SET pending = 1 WHERE node_id = ?1 AND trusted = 0",
            [node_id],
        )?;
        Ok(n > 0)
    }

    /// Record OUR outgoing pairing request. Fresh rows park as
    /// `outgoing` with `pending` clear (pending stays incoming-only, so
    /// our own request never renders with an Accept button); known rows
    /// keep their trust/pending state and just gain the marker plus
    /// fresh addresses. Already-trusted rows only refresh addresses.
    /// Revoked or malformed identities are refused like `record_seen`.
    pub fn record_outgoing(
        &self,
        node_id: &str,
        name: &str,
        addresses: &[String],
    ) -> rusqlite::Result<bool> {
        if !valid_node_id(node_id) || self.is_revoked(node_id)? {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "invalid or revoked peer".into(),
            ));
        }
        let now = now_ms() / 1000;
        let existing = self.get_peer(node_id)?;
        match existing {
            None => {
                self.conn.execute(
                    "INSERT INTO devices
                       (node_id, name, addresses, trusted, pending, outgoing, last_seen)
                     VALUES (?1, ?2, ?3, 0, 0, 1, ?4)",
                    (node_id, name, dump_addresses(addresses), now),
                )?;
                Ok(true)
            }
            Some(peer) => {
                if peer.trusted {
                    let merged = merge_addresses(&peer.addresses, addresses);
                    let peer_name = if name.is_empty() {
                        peer.name
                    } else {
                        name.to_string()
                    };
                    self.conn.execute(
                        "UPDATE devices SET name = ?2, addresses = ?3, last_seen = ?4
                         WHERE node_id = ?1",
                        (node_id, peer_name, dump_addresses(&merged), now),
                    )?;
                    return Ok(false);
                }
                let merged = merge_addresses(&peer.addresses, addresses);
                let peer_name = if name.is_empty() {
                    peer.name
                } else {
                    name.to_string()
                };
                self.conn.execute(
                    "UPDATE devices
                     SET name = ?2, addresses = ?3, outgoing = 1, last_seen = ?4
                     WHERE node_id = ?1",
                    (node_id, peer_name, dump_addresses(&merged), now),
                )?;
                Ok(false)
            }
        }
    }

    /// Withdraw OUR outgoing request without revoking: the row is
    /// dropped and the id stays pairable (unlike `decide_request`
    /// reject, which durably denies the id). Refused unless the row is
    /// an untrusted outgoing marker.
    pub fn withdraw_outgoing(&self, node_id: &str) -> rusqlite::Result<()> {
        let peer = self.get_peer(node_id)?;
        let Some(peer) = peer else {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "unknown peer".into(),
            ));
        };
        if peer.trusted || !peer.outgoing {
            return Err(rusqlite::Error::ToSqlConversionFailure(
                "no outgoing request to withdraw".into(),
            ));
        }
        self.conn
            .execute("DELETE FROM devices WHERE node_id = ?1", [node_id])?;
        Ok(())
    }

    /// Decide a pairing request. Accepting trusts the peer, clears both
    /// request markers and grants introduction authority (desktop trust
    /// flow: its vouched devices join automatically on the next merge).
    /// Use `set_peer_introducer` to revoke that authority afterwards.
    /// Rejecting revokes the id durably. Both paths advance the sync
    /// epoch.
    pub fn decide_request(&self, node_id: &str, accept: bool) -> rusqlite::Result<()> {
        if accept {
            let tx = self.conn.unchecked_transaction()?;
            tx.execute(
                "UPDATE devices SET trusted = 1, pending = 0, outgoing = 0, introducer = 1
                 WHERE node_id = ?1",
                [node_id],
            )?;
            Self::invalidate_sync(&tx)?;
            tx.commit()?;
        } else {
            self.remove_peer(node_id)?;
        }
        Ok(())
    }

    pub fn list_pending(&self) -> rusqlite::Result<Vec<Peer>> {
        Ok(self
            .list_peers()?
            .into_iter()
            .filter(|p| p.pending)
            .collect())
    }

    /// Our sent requests awaiting their accept (never includes incoming
    /// `pending` rows on their own — the two markers are independent).
    pub fn list_outgoing(&self) -> rusqlite::Result<Vec<Peer>> {
        Ok(self
            .list_peers()?
            .into_iter()
            .filter(|p| p.outgoing && !p.trusted)
            .collect())
    }

    /// Gossip payload: nodes we vouch for (trusted peers + self is
    /// added by the sender, not stored here).
    pub fn export_gossip(&self) -> rusqlite::Result<Vec<PeerGossip>> {
        Ok(self
            .list_peers()?
            .into_iter()
            .filter(|p| p.trusted)
            .map(|p| PeerGossip {
                node_id: p.node_id,
                name: p.name,
                addresses: p.addresses,
                last_seen: p.last_seen,
            })
            .collect())
    }

    /// Merge gossiped peers: refresh addresses of known nodes;
    /// auto-add strangers vouched by an introducer as trusted.
    /// Revoked or malformed ids are skipped (gossip must never repair a
    /// revocation). One transaction.
    pub fn apply_gossip(
        &self,
        gossip: &[PeerGossip],
        introducer: bool,
        self_id: &str,
    ) -> rusqlite::Result<usize> {
        let tx = self.conn.unchecked_transaction()?;
        let added = Self::apply_gossip_on(&tx, gossip, introducer, self_id)?;
        tx.commit()?;
        Ok(added)
    }

    fn apply_gossip_on(
        conn: &Connection,
        gossip: &[PeerGossip],
        introducer: bool,
        self_id: &str,
    ) -> rusqlite::Result<usize> {
        let mut added = 0;
        for g in gossip {
            if g.node_id == self_id || !valid_node_id(&g.node_id) {
                continue;
            }
            let revoked: bool = conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM revoked_devices WHERE node_id = ?1)",
                [&g.node_id],
                |r| r.get(0),
            )?;
            if revoked {
                continue;
            }
            let peer: Option<Peer> = conn
                .query_row(
                    "SELECT node_id, name, address, addresses, trusted, pending,
                            outgoing, introducer, last_seen, last_sync
                     FROM devices WHERE node_id = ?1",
                    [&g.node_id],
                    Self::peer_from_row,
                )
                .optional()?;
            match peer {
                Some(peer) => {
                    let merged = merge_addresses(&peer.addresses, &g.addresses);
                    conn.execute(
                        "UPDATE devices SET addresses = ?2, last_seen = MAX(last_seen, ?3)
                         WHERE node_id = ?1",
                        (g.node_id.clone(), dump_addresses(&merged), g.last_seen),
                    )?;
                    // Names ride along with gossip so renames propagate
                    // without waiting for a full sync round.
                    if !g.name.is_empty() && g.name != peer.name {
                        conn.execute(
                            "UPDATE devices SET name = ?2 WHERE node_id = ?1",
                            (g.node_id.clone(), g.name.clone()),
                        )?;
                    }
                }
                None if introducer => {
                    conn.execute(
                        "INSERT INTO devices
                           (node_id, name, addresses, trusted, pending, outgoing, last_seen)
                         VALUES (?1, ?2, ?3, 1, 0, 0, ?4)",
                        (
                            g.node_id.clone(),
                            g.name.clone(),
                            dump_addresses(&merge_addresses(&[], &g.addresses)),
                            g.last_seen,
                        ),
                    )?;
                    added += 1;
                }
                None => {}
            }
        }
        Ok(added)
    }
}

/// Gossiped peer descriptor exchanged inside sync payloads.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct PeerGossip {
    pub node_id: String,
    pub name: String,
    #[serde(default)]
    pub addresses: Vec<String>,
    pub last_seen: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn activity_counts_all_history_and_only_measured_speed() {
        let store = Store::open_memory().unwrap();
        for _ in 0..205 {
            store.add_history("raw", "text", 10, 5, "node").unwrap();
        }
        store
            .add_history("legacy", "legacy", 100, 0, "node")
            .unwrap();
        store.add_discarded(30, "node").unwrap();
        let deleted = store
            .add_history("deleted", "deleted", 100, 2, "node")
            .unwrap();
        store.delete_history(&deleted).unwrap();
        let stats = store.usage_stats().unwrap();
        assert_eq!(stats.dictations, 206);
        assert_eq!(stats.words_spoken, 2150);
        assert_eq!(stats.words_per_minute, 120.0);
    }

    #[test]
    fn bulk_deletion_survives_sync_with_an_older_peer() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        a.add_history("raw", "text", 10, 5, "node").unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        assert_eq!(a.clear_history().unwrap(), 1);
        a.apply_history(&b.export_history().unwrap()).unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        assert!(a.load_history(10, true).unwrap().is_empty());
        assert!(b.load_history(10, true).unwrap().is_empty());
        assert_eq!(a.usage_stats().unwrap().dictations, 0);
    }

    #[test]
    fn retained_history_deletion_survives_sync() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        a.add_history("raw", "text", 10, 5, "node").unwrap();
        a.conn
            .execute(
                "UPDATE history SET recorded_at = strftime('%s','now','-60 days')",
                [],
            )
            .unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        assert_eq!(a.purge_old_history(30).unwrap(), 1);
        a.apply_history(&b.export_history().unwrap()).unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        assert!(a.load_history(10, true).unwrap().is_empty());
        assert!(b.load_history(10, true).unwrap().is_empty());
    }

    #[test]
    fn server_cmd_is_one_shot() {
        let store = Store::open_memory().unwrap();
        assert_eq!(store.take_server_cmd(), None);
        store.set_server_cmd("stop").unwrap();
        assert_eq!(store.take_server_cmd().as_deref(), Some("stop"));
        // Consumed: second read finds nothing.
        assert_eq!(store.take_server_cmd(), None);
    }

    #[test]
    fn settings_engine_switch_roundtrip() {
        use crate::settings::LocalSttProvider;
        let store = Store::open_memory().unwrap();
        let mut s = store.load_settings();
        // Scoped mutation, then a separate persist read (never nested
        // borrows). Seeded chain survives the round trip.
        {
            s.stt.local_provider = LocalSttProvider::Nvidia;
        }
        store.save_settings(&s).unwrap();
        let reloaded = store.load_settings();
        assert_eq!(reloaded.stt.local_provider, LocalSttProvider::Nvidia);
        assert!(!reloaded.stt.entries.is_empty());
        assert_eq!(reloaded.stt.chain().len(), 1);
    }

    #[test]
    fn discarded_rows_filter_and_sync() {
        let store = Store::open_memory().unwrap();
        let uuid = store.add_discarded(3, "node-x").unwrap();
        // Hidden by default, visible with the toggle, flagged.
        assert_eq!(store.load_history(10, false).unwrap().len(), 0);
        let shown = store.load_history(10, true).unwrap();
        assert_eq!(shown.len(), 1);
        assert!(shown[0].discarded);
        assert_eq!(shown[0].duration_secs, 3);
        assert_eq!(shown[0].word_count, 0);
        // Stats queries never see discards.
        assert!(store.active_days().unwrap().is_empty());
        assert!(store.daily_aggregates(30).unwrap().is_empty());
        // Tombstoning a discard keeps it hidden everywhere.
        store.delete_history(&uuid).unwrap();
        assert_eq!(store.load_history(10, true).unwrap().len(), 0);
    }

    #[test]
    fn auto_delete_keeps_tombstones() {
        let store = Store::open_memory().unwrap();
        let uuid = store.add_history("r", "t", 2, 5, "node-x").unwrap();
        store.delete_history(&uuid).unwrap();
        // Ancient live rows go; tombstones stay (delete-sync safety).
        let n = store.purge_old_history(0).unwrap();
        assert_eq!(n, 0, "tombstone must survive auto-delete");
        store.add_history("r2", "t2", 2, 5, "node-x").unwrap();
        // Backdate the live row 60 days.
        store
            .conn
            .execute(
                "UPDATE history SET recorded_at = strftime('%s','now','-60 days') WHERE deleted = 0",
                [],
            )
            .unwrap();
        let n = store.purge_old_history(30).unwrap();
        assert_eq!(n, 1);
    }

    #[test]
    fn shared_gen_moves_on_write() {
        let store = Store::open_memory().unwrap();
        let v0 = read_gen(&store);
        store.upsert_word("hello", "node-x").unwrap();
        let v1 = read_gen(&store);
        assert!(v1 != v0, "shared gen must move on write ({v0} -> {v1})");
    }

    /// Two connections, same file (two processes in miniature): a
    /// write through one is visible to the other on the next read.
    /// This is the exact property UI refresh polls rely on.
    #[test]
    fn shared_gen_visible_across_connections() {
        static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "mywhisper-gen-test-{}-{}",
            std::process::id(),
            N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        ));
        // NOTE: Store::open needs the keyring; the test env has
        // a session keyring, same as production on this machine.
        // If the keyring is unavailable here, skip rather than fail:
        // crypto setup is covered by the app path, not this property.
        let Ok(a) = Store::open(&dir) else {
            return;
        };
        let v0 = read_gen(&a);
        {
            let b = Store::open(&dir).unwrap();
            b.upsert_word("cross-conn", "node-y").unwrap();
        }
        let v1 = read_gen(&a);
        assert!(v1 != v0, "gen write must be visible cross-connection");
        assert!(a.load_words().unwrap().contains(&"cross-conn".to_string()));
        drop(a);
        let _ = std::fs::remove_file(&dir);
    }

    const A: &str = "node-aaaa";
    const B: &str = "node-bbbb";

    fn snippet(store: &Store, trigger: &str) -> Option<String> {
        store
            .load_snippets()
            .unwrap()
            .into_iter()
            .find(|s| s.trigger == trigger)
            .map(|s| s.replacement)
    }

    fn words(store: &Store) -> Vec<String> {
        let mut w = store.load_words().unwrap();
        w.sort();
        w
    }

    /// Full bidirectional exchange, as one sync round-trip would do.
    fn sync(a: &Store, b: &Store) {
        b.apply_words(&a.export_words().unwrap()).unwrap();
        a.apply_words(&b.export_words().unwrap()).unwrap();
        b.apply_snippets(&a.export_snippets().unwrap()).unwrap();
        a.apply_snippets(&b.export_snippets().unwrap()).unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        a.apply_history(&b.export_history().unwrap()).unwrap();
    }

    #[test]
    fn divergent_edits_converge_with_newest_winning() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();

        // Common base: same snippet on both (older version).
        for store in [&a, &b] {
            store
                .apply_snippets(&[SnippetRecord {
                    trigger: "omw".to_string(),
                    replacement: "On my way".to_string(),
                    updated_at: 1000,
                    updated_by: A.to_string(),
                    deleted: false,
                }])
                .unwrap();
            store
                .apply_words(&[WordRecord {
                    word: "gone".to_string(),
                    updated_at: 1000,
                    updated_by: A.to_string(),
                    deleted: false,
                }])
                .unwrap();
        }

        // Diverge with no contact: A edits the snippet at t=2000,
        // B edits the SAME snippet at t=3000. A adds w1, B adds w2,
        // A deletes "gone" at t=2500.
        a.apply_snippets(&[SnippetRecord {
            trigger: "omw".to_string(),
            replacement: "On my way home".to_string(),
            updated_at: 2000,
            updated_by: A.to_string(),
            deleted: false,
        }])
        .unwrap();
        b.apply_snippets(&[SnippetRecord {
            trigger: "omw".to_string(),
            replacement: "On my way!!".to_string(),
            updated_at: 3000,
            updated_by: B.to_string(),
            deleted: false,
        }])
        .unwrap();
        a.apply_words(&[WordRecord {
            word: "w1".to_string(),
            updated_at: 2000,
            updated_by: A.to_string(),
            deleted: false,
        }])
        .unwrap();
        b.apply_words(&[WordRecord {
            word: "w2".to_string(),
            updated_at: 3000,
            updated_by: B.to_string(),
            deleted: false,
        }])
        .unwrap();
        a.apply_words(&[WordRecord {
            word: "gone".to_string(),
            updated_at: 2500,
            updated_by: A.to_string(),
            deleted: true,
        }])
        .unwrap();
        a.apply_history(&[HistoryRecord {
            uuid: "a-1".to_string(),
            text: "hello from a".to_string(),
            raw_text: "hello from a".to_string(),
            words: 3,
            duration_secs: 5,
            deleted: false,
            discarded: false,
            recorded_at: 2000,
        }])
        .unwrap();
        b.apply_history(&[HistoryRecord {
            uuid: "b-1".to_string(),
            text: "hello from b".to_string(),
            raw_text: "raw from b".to_string(),
            words: 3,
            duration_secs: 5,
            deleted: false,
            discarded: false,
            recorded_at: 3000,
        }])
        .unwrap();

        // Reconnect and sync.
        sync(&a, &b);

        // Newer concurrent edit wins on BOTH sides...
        assert_eq!(snippet(&a, "omw").as_deref(), Some("On my way!!"));
        assert_eq!(snippet(&b, "omw").as_deref(), Some("On my way!!"));
        // ...disjoint adds union...
        assert_eq!(words(&a), ["w1", "w2"]);
        assert_eq!(words(&b), ["w1", "w2"]);
        // ...the newer tombstone deletes everywhere...
        assert!(!words(&a).contains(&"gone".to_string()));
        assert!(!words(&b).contains(&"gone".to_string()));
        // ...history unions by uuid, raw survives the round trip...
        assert_eq!(a.load_history(10, false).unwrap().len(), 2);
        assert_eq!(b.load_history(10, false).unwrap().len(), 2);
        let raws: Vec<String> = a
            .load_history(10, false)
            .unwrap()
            .iter()
            .map(|h| h.raw_text.clone())
            .collect();
        assert!(raws.contains(&"raw from b".to_string()));
        // ...a history tombstone deletes everywhere and never
        // resurrects from the peer that still holds the row...
        a.delete_history("b-1").unwrap();
        assert_eq!(a.load_history(10, false).unwrap().len(), 1);
        sync(&a, &b);
        assert_eq!(a.load_history(10, false).unwrap().len(), 1);
        assert_eq!(b.load_history(10, false).unwrap().len(), 1);

        // Sync is idempotent: a second round changes nothing.
        let before_a = a.export_snippets().unwrap();
        sync(&a, &b);
        assert_eq!(a.export_snippets().unwrap(), before_a);
        assert_eq!(snippet(&a, "omw").as_deref(), Some("On my way!!"));
    }

    #[test]
    fn older_write_does_not_resurrect() {
        let a = Store::open_memory().unwrap();
        a.apply_snippets(&[SnippetRecord {
            trigger: "k".to_string(),
            replacement: "new".to_string(),
            updated_at: 5000,
            updated_by: A.to_string(),
            deleted: false,
        }])
        .unwrap();
        // A delayed/duplicate older record must not win.
        a.apply_snippets(&[SnippetRecord {
            trigger: "k".to_string(),
            replacement: "stale".to_string(),
            updated_at: 4000,
            updated_by: B.to_string(),
            deleted: false,
        }])
        .unwrap();
        assert_eq!(snippet(&a, "k").as_deref(), Some("new"));
    }

    #[test]
    fn hex_key_rejects_non_hex() {
        // 64 chars but non-hex: same class of bad key the Android
        // length-only check used to let through. Fail closed.
        assert!(hex_decode_key(&"zz".repeat(32)).is_none());
        assert!(hex_decode_key(&"gg".repeat(32)).is_none());
        assert!(
            hex_decode_key("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg")
                .is_none()
        );
        assert!(hex_decode_key("short").is_none());
        assert!(hex_decode_key(&"ab".repeat(31)).is_none());
        assert!(hex_decode_key(&"ab".repeat(33)).is_none());
        assert!(hex_decode_key(&"ab".repeat(32)).is_some());
        assert!(hex_decode_key(&"AB".repeat(32)).is_some());
    }

    #[test]
    fn plaintext_migration_removes_sidecars() {
        static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1000);
        let dir = std::env::temp_dir().join(format!(
            "mywhisper-migrate-test-{}-{}",
            std::process::id(),
            N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        ));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("state.db");
        // Deterministic test key (no env mutation: parallel tests share
        // the process env, so this test passes the key explicitly).
        let test_key = "0123456789abcdef".repeat(4);
        assert_eq!(test_key.len(), 64);
        // Legacy plaintext db with one marker row.
        {
            let plain = Connection::open(&path).unwrap();
            plain
                .execute_batch("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                .unwrap();
            plain
                .execute("INSERT INTO kv (key, value) VALUES ('probe', 'kept')", [])
                .unwrap();
            drop(plain);
        }
        // Stale plaintext sidecars: L0 residue the migration must wipe.
        for suffix in ["-wal", "-shm", "-journal"] {
            let sidecar = format!("{}{suffix}", path.display());
            std::fs::write(&sidecar, b"plaintext residue").unwrap();
        }
        // Exercise the migration directly so the wipe is observed
        // before reopen recreates live (encrypted) WAL sidecars.
        let key = hex_decode_key(&test_key).unwrap();
        migrate_plaintext_to_encrypted(&path, &key).unwrap();
        for suffix in ["-wal", "-shm", "-journal"] {
            assert!(
                !std::path::Path::new(&format!("{}{suffix}", path.display())).exists(),
                "sidecar {suffix} must be wiped by migration"
            );
        }
        let store_conn = Connection::open(&path).unwrap();
        store_conn.execute_batch(&key_pragma(&key)).unwrap();
        // Reopened live sidecars (WAL) may exist again, but must never
        // carry the old plaintext bytes.
        for suffix in ["-wal", "-shm", "-journal"] {
            let sidecar = format!("{}{suffix}", path.display());
            if let Ok(bytes) = std::fs::read(&sidecar) {
                assert!(
                    !bytes.windows(17).any(|w| w == b"plaintext residue"),
                    "sidecar {suffix} must not retain plaintext"
                );
            }
        }
        let kept: String = store_conn
            .query_row("SELECT value FROM kv WHERE key = 'probe'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(kept, "kept");
        drop(store_conn);
        let _ = std::fs::remove_dir_all(&dir);
    }

    // -- desktop #18 port: authz / tombstone / epoch tests ------------------

    /// Deterministic 64-hex peer id (mirrors the real ed25519 identity
    /// format so `valid_node_id` accepts it).
    fn peer_id(seed: u8) -> String {
        std::iter::repeat_with(|| format!("{seed:02x}"))
            .take(32)
            .collect()
    }

    /// Persist a settings blob with sync explicitly enabled (fresh
    /// `open_memory` stores carry no blob, which refuses by design).
    fn enable_sync(store: &Store) {
        let mut s = store.load_settings();
        s.sync.enabled = true;
        store.save_settings(&s).unwrap();
    }

    fn trust(store: &Store, peer: &str) {
        store.upsert_peer(peer, "peer", "", true).unwrap();
    }

    #[test]
    fn authorized_export_needs_trust_current_epoch_and_enabled_sync() {
        let store = Store::open_memory().unwrap();
        let peer = peer_id(0x11);
        // No settings blob yet: refuses (no permissive defaults).
        store.upsert_peer(&peer, "peer", "", true).unwrap();
        assert!(store.export_authorized(&peer, store.sync_epoch()).is_err());
        enable_sync(&store);
        let epoch = store.sync_epoch();
        store
            .add_history("raw", "hello", 2, 5, &peer_id(0x22))
            .unwrap();
        assert_eq!(
            store.export_authorized(&peer, epoch).unwrap().history.len(),
            1
        );

        // Stale epoch fails after any trust/topology change.
        trust(&store, &peer_id(0x33));
        assert_ne!(store.sync_epoch(), epoch);
        assert!(store.export_authorized(&peer, epoch).is_err());

        // Unknown peer fails even with the fresh epoch.
        let fresh = store.sync_epoch();
        assert!(store.export_authorized(&peer_id(0x44), fresh).is_err());

        // Disabled sync fails.
        let mut s = store.load_settings();
        s.sync.enabled = false;
        store.save_settings(&s).unwrap();
        assert!(store.export_authorized(&peer, fresh).is_err());
    }

    #[test]
    fn revoked_peer_is_denied_everywhere_and_never_repair_via_gossip() {
        let store = Store::open_memory().unwrap();
        enable_sync(&store);
        let peer = peer_id(0x55);
        let intro = peer_id(0x66);
        trust(&store, &peer);
        trust(&store, &intro);
        store.set_peer_introducer(&intro, true).unwrap();
        let epoch = store.sync_epoch();
        assert!(store.export_authorized(&peer, epoch).is_ok());

        store.remove_peer(&peer).unwrap();
        assert!(store.is_revoked(&peer).unwrap());
        let epoch = store.sync_epoch();
        // Export, re-contact and gossip repair all refuse.
        assert!(store.export_authorized(&peer, epoch).is_err());
        assert!(store.record_seen(&peer, "peer", &[]).is_err());
        let gossip = vec![PeerGossip {
            node_id: peer.clone(),
            name: "peer".to_string(),
            addresses: vec![],
            last_seen: 0,
        }];
        assert_eq!(
            store.apply_gossip(&gossip, true, &peer_id(0x77)).unwrap(),
            0
        );
        assert!(store.get_peer(&peer).unwrap().is_none());
        // Malformed identities are refused the same way.
        assert!(store.record_seen("not-a-node-id", "x", &[]).is_err());
        assert!(store.upsert_peer("short", "x", "", true).is_err());

        // Explicit re-trust clears the revocation.
        store.upsert_peer(&peer, "peer", "", true).unwrap();
        assert!(!store.is_revoked(&peer).unwrap());
        assert!(store.export_authorized(&peer, store.sync_epoch()).is_ok());
    }

    #[test]
    fn introduction_authority_is_explicit_not_implied_by_trust() {
        let store = Store::open_memory().unwrap();
        let peer = peer_id(0x88);
        trust(&store, &peer);
        // Granting to an unknown peer is refused...
        assert!(store.set_peer_introducer(&peer_id(0x99), true).is_err());
        // ...manual trust alone grants nothing...
        assert!(!store.get_peer(&peer).unwrap().unwrap().introducer);
        // ...but accepting a request grants it (desktop mesh flow: the
        // vouched devices join automatically on the next gossip merge)...
        let pending = peer_id(0x9a);
        store.record_seen(&pending, "new", &[]).unwrap();
        store.decide_request(&pending, true).unwrap();
        let row = store.get_peer(&pending).unwrap().unwrap();
        assert!(row.trusted && !row.pending && !row.outgoing && row.introducer);
        // ...and it stays explicitly revocable afterwards.
        store.set_peer_introducer(&pending, false).unwrap();
        assert!(!store.get_peer(&pending).unwrap().unwrap().introducer);
        // Rejecting revokes durably.
        let denied = peer_id(0x9b);
        store.record_seen(&denied, "denied", &[]).unwrap();
        store.decide_request(&denied, false).unwrap();
        assert!(store.is_revoked(&denied).unwrap());
    }

    #[test]
    fn outgoing_shows_waiting_not_incoming() {
        let store = Store::open_memory().unwrap();
        // Our sent request parks as outgoing, never as incoming pending:
        // it must not render with an Accept button.
        let peer = peer_id(0xc5);
        store.record_outgoing(&peer, "other", &[]).unwrap();
        let row = store.get_peer(&peer).unwrap().unwrap();
        assert!(row.outgoing && !row.pending && !row.trusted);
        assert!(
            store.list_pending().unwrap().is_empty(),
            "own request must not render as an incoming request"
        );
        assert_eq!(store.list_outgoing().unwrap().len(), 1);
        // Inbound contact still lands as incoming pending alongside it.
        let inbound = peer_id(0xc6);
        store.record_seen(&inbound, "asker", &[]).unwrap();
        assert_eq!(store.list_pending().unwrap().len(), 1);
        assert_eq!(store.list_outgoing().unwrap().len(), 1);
        // Accepting clears the outgoing marker with the rest.
        store.decide_request(&peer, true).unwrap();
        let row = store.get_peer(&peer).unwrap().unwrap();
        assert!(row.trusted && !row.pending && !row.outgoing);
        assert!(store.list_outgoing().unwrap().is_empty());
        // Cancelling withdraws without revoking: the id stays pairable.
        let cancel = peer_id(0xc7);
        store.record_outgoing(&cancel, "other2", &[]).unwrap();
        store.withdraw_outgoing(&cancel).unwrap();
        assert!(store.get_peer(&cancel).unwrap().is_none());
        assert!(!store.is_revoked(&cancel).unwrap());
        store.record_outgoing(&cancel, "other2", &[]).unwrap();
        assert_eq!(store.list_outgoing().unwrap().len(), 1);
    }

    #[test]
    fn accept_grants_introducer_mesh() {
        let store = Store::open_memory().unwrap();
        let a = peer_id(0xd5);
        let b = peer_id(0xd6);
        // A asks to pair; accepting trusts A AND marks them introducer
        // (desktop flow), so devices A vouches for join automatically.
        store.record_seen(&a, "a", &[]).unwrap();
        store.decide_request(&a, true).unwrap();
        let row = store.get_peer(&a).unwrap().unwrap();
        assert!(row.trusted && !row.pending && row.introducer);
        // A's gossip vouches for B: B joins trusted with no manual step.
        let vouches = store.get_peer(&a).unwrap().unwrap().introducer;
        let gossip = vec![PeerGossip {
            node_id: b.clone(),
            name: "b".to_string(),
            addresses: vec![],
            last_seen: 0,
        }];
        assert_eq!(
            store
                .apply_gossip(&gossip, vouches, &peer_id(0xd7))
                .unwrap(),
            1
        );
        let brow = store.get_peer(&b).unwrap().unwrap();
        assert!(brow.trusted && !brow.pending && !brow.outgoing);
    }

    #[test]
    fn sync_round_applies_atomically_and_rechecks_inside_commit() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        for s in [&a, &b] {
            enable_sync(s);
        }
        let pa = peer_id(0xa1);
        let pb = peer_id(0xb1);
        // Each store trusts the other under a synthetic id.
        a.upsert_peer(&pb, "b", "", true).unwrap();
        b.upsert_peer(&pa, "a", "", true).unwrap();
        let epoch_b = b.sync_epoch();

        let words = vec![WordRecord {
            word: "kwyjibo".to_string(),
            updated_at: now_ms(),
            updated_by: pa.clone(),
            deleted: false,
        }];
        let history = vec![HistoryRecord {
            uuid: "round-1".to_string(),
            text: "hi".to_string(),
            raw_text: "hi".to_string(),
            words: 1,
            duration_secs: 2,
            deleted: false,
            discarded: false,
            recorded_at: now_ms() / 1000,
        }];
        let added = b
            .apply_sync_round(&pa, epoch_b, &pb, (&words, &[], &history), &[])
            .unwrap();
        assert_eq!(added, 0);
        assert!(b.load_words().unwrap().contains(&"kwyjibo".to_string()));
        assert_eq!(b.load_history(10, false).unwrap().len(), 1);

        // Stale epoch commits nothing (all-or-nothing).
        let stale_words = vec![WordRecord {
            word: "evil".to_string(),
            updated_at: now_ms(),
            updated_by: pa.clone(),
            deleted: false,
        }];
        b.upsert_peer(&peer_id(0xc1), "churn", "", true).unwrap();
        assert!(
            b.apply_sync_round(&pa, epoch_b, &pb, (&stale_words, &[], &[]), &[])
                .is_err()
        );
        assert!(!b.load_words().unwrap().contains(&"evil".to_string()));
    }

    #[test]
    fn history_deletes_redact_payloads_and_survive_as_tombstones() {
        let a = Store::open_memory().unwrap();
        let b = Store::open_memory().unwrap();
        let uuid = a
            .add_history("secret raw", "secret", 2, 5, "node-x")
            .unwrap();
        b.apply_history(&a.export_history().unwrap()).unwrap();
        a.delete_history(&uuid).unwrap();
        // Local row keeps the tombstone but no text.
        let row = a.export_history().unwrap();
        assert_eq!(row.len(), 1);
        assert!(row[0].deleted && row[0].text.is_empty() && row[0].raw_text.is_empty());
        // The tombstone redacts the peer that still holds plaintext.
        b.apply_history(&a.export_history().unwrap()).unwrap();
        let brow = b.export_history().unwrap();
        assert!(brow[0].deleted && brow[0].text.is_empty());
        assert!(a.load_history(10, true).unwrap().is_empty());

        // Legacy zero-duration cleanup redacts instead of deleting.
        a.add_history("legacy", "legacy", 4, 0, "node-x").unwrap();
        assert_eq!(a.delete_zero_duration_history().unwrap(), 1);
        let rows = a.export_history().unwrap();
        assert!(rows.iter().all(|r| r.deleted));
        // Clear-all preserves every tombstone identity (the row is
        // already a tombstone here, so nothing new is redacted).
        assert_eq!(b.clear_history().unwrap(), 0);
        assert!(b.load_history(10, true).unwrap().is_empty());
        assert!(!b.export_history().unwrap().is_empty());
    }

    #[test]
    fn reset_keeps_tombstones_revocations_and_kills_sync() {
        let store = Store::open_memory().unwrap();
        enable_sync(&store);
        let peer = peer_id(0xd1);
        trust(&store, &peer);
        store.upsert_word("hello", "node-x").unwrap();
        store.add_history("raw", "text", 2, 5, "node-x").unwrap();
        let epoch = store.sync_epoch();

        store.reset_all_data().unwrap();
        // Epoch advanced: stale authorized exports fail.
        assert_ne!(store.sync_epoch(), epoch);
        assert!(store.export_authorized(&peer, epoch).is_err());
        // Trust gone, revocation durable, sync back disabled.
        assert!(store.list_peers().unwrap().is_empty());
        assert!(store.is_revoked(&peer).unwrap());
        assert!(!store.load_settings().sync.enabled);
        // Content tombstones survived (no resurrection from peers).
        assert!(store.load_words().unwrap().is_empty());
        assert!(!store.export_words().unwrap().is_empty());
        assert!(store.export_words().unwrap().iter().all(|w| w.deleted));
        assert!(store.export_history().unwrap().iter().all(|h| h.deleted));
    }

    #[test]
    fn monotonic_clock_increases_and_overflow_errors_without_wrapping() {
        let store = Store::open_memory().unwrap();
        let first = Store::next_version(&store.conn).unwrap();
        let second = Store::next_version(&store.conn).unwrap();
        assert!(second > first);
        // A racing wall clock cannot move versions backwards: stored
        // clock dominates.
        store.upsert_word("w", "node-x").unwrap();
        let third = Store::next_version(&store.conn).unwrap();
        assert!(third > second);

        // Near-max clock saturates to MAX once, then errors (never
        // wraps negative / resurrects).
        store
            .conn
            .execute(
                "INSERT INTO words (word, updated_at, updated_by, deleted)
                 VALUES ('maxed', ?1, 'node-x', 0)",
                [i64::MAX - 1],
            )
            .unwrap();
        assert_eq!(Store::next_version(&store.conn).unwrap(), i64::MAX);
        assert!(Store::next_version(&store.conn).is_err());
        assert!(Store::next_version(&store.conn).is_err());
    }

    #[test]
    fn future_dated_versions_rejected_while_delayed_ones_merge() {
        let store = Store::open_memory().unwrap();
        // Delayed/duplicate old records are normal transport: accepted.
        store
            .apply_words(&[WordRecord {
                word: "old".to_string(),
                updated_at: 1000,
                updated_by: peer_id(0xe1),
                deleted: false,
            }])
            .unwrap();
        assert!(store.load_words().unwrap().contains(&"old".to_string()));
        // A peer 30 days in the future must not poison LWW ordering.
        assert!(
            store
                .apply_words(&[WordRecord {
                    word: "future".to_string(),
                    updated_at: now_ms() + 30 * 86_400_000,
                    updated_by: peer_id(0xe2),
                    deleted: false,
                }])
                .is_err()
        );
        assert!(!store.load_words().unwrap().contains(&"future".to_string()));
    }

    #[test]
    fn local_writes_survive_clock_excursion_and_correction() {
        let store = Store::open_memory().unwrap();
        // Excursion poisons the stored monotonic clock far into the future.
        let poisoned = now_ms() + 30 * 86_400_000;
        store
            .conn
            .execute(
                "INSERT INTO sync_meta (key, value) VALUES ('clock', ?1)",
                [poisoned.to_string()],
            )
            .unwrap();
        // Clock corrected back, stored poison remains: local dictionary
        // writes must keep working (horizon applies to remote input only).
        store.upsert_word("local", "node-x").unwrap();
        store.delete_word("local", "node-x").unwrap();
        store.upsert_snippet("t", "r", "node-x").unwrap();
        store.delete_snippet("t", "node-x").unwrap();
        assert!(store.load_words().unwrap().is_empty());
        // Remote poison is still rejected at the entry.
        assert!(
            store
                .apply_words(&[WordRecord {
                    word: "poison".to_string(),
                    updated_at: poisoned,
                    updated_by: peer_id(0xf1),
                    deleted: false,
                }])
                .is_err()
        );
        assert!(
            store
                .apply_snippets(&[SnippetRecord {
                    trigger: "poison".to_string(),
                    replacement: "x".to_string(),
                    updated_at: poisoned,
                    updated_by: peer_id(0xf1),
                    deleted: false,
                }])
                .is_err()
        );
        // And a poisoned sync round commits nothing.
        enable_sync(&store);
        let peer = peer_id(0xf2);
        trust(&store, &peer);
        let epoch = store.sync_epoch();
        let bad = vec![WordRecord {
            word: "round-poison".to_string(),
            updated_at: poisoned,
            updated_by: peer.clone(),
            deleted: false,
        }];
        assert!(
            store
                .apply_sync_round(&peer, epoch, &peer_id(0xf3), (&bad, &[], &[]), &[])
                .is_err()
        );
        assert!(
            !store
                .load_words()
                .unwrap()
                .contains(&"round-poison".to_string())
        );
    }

    #[test]
    fn reset_clears_poisoned_monotonic_clock() {
        let store = Store::open_memory().unwrap();
        let poisoned = now_ms() + 30 * 86_400_000;
        // Content rows present before reset, versions excursion-poisoned
        // (as minted by local writes during the excursion): the case the
        // old mint-before-scrub order got wrong.
        store.upsert_word("w", "node-x").unwrap();
        store.upsert_snippet("t", "r", "node-x").unwrap();
        store
            .conn
            .execute("UPDATE words SET updated_at = ?1", [poisoned])
            .unwrap();
        store
            .conn
            .execute("UPDATE snippets SET updated_at = ?1", [poisoned])
            .unwrap();
        store
            .conn
            .execute(
                "INSERT INTO sync_meta (key, value) VALUES ('clock', ?1)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                [poisoned.to_string()],
            )
            .unwrap();
        store.reset_all_data().unwrap();
        // Tombstones minted near the wall clock: peers inside the 7-day
        // horizon accept them instead of resurrecting the rows.
        for w in store.export_words().unwrap() {
            assert!(w.deleted);
            assert!(
                (w.updated_at - now_ms()).abs() < 60_000,
                "tombstone version near wall clock, got {}",
                w.updated_at
            );
        }
        for sn in store.export_snippets().unwrap() {
            assert!(sn.deleted);
            assert!(
                (sn.updated_at - now_ms()).abs() < 60_000,
                "tombstone version near wall clock, got {}",
                sn.updated_at
            );
        }
        // The poison is gone: reset re-mints a wall-clock clock row.
        let clock: Option<String> = store
            .conn
            .query_row("SELECT value FROM sync_meta WHERE key = 'clock'", [], |r| {
                r.get(0)
            })
            .ok();
        let clock: i64 = clock.expect("reset re-mints a sane clock").parse().unwrap();
        assert!(
            (clock - now_ms()).abs() < 60_000,
            "stored clock near wall clock, got {clock}"
        );
        // Post-reset versions come from the wall clock again.
        let v = Store::next_version(&store.conn).unwrap();
        assert!((v - now_ms()).abs() < 60_000, "clock recovered, got {v}");
    }

    #[test]
    fn dial_gate_needs_an_explicit_settings_blob() {
        let store = Store::open_memory().unwrap();
        // Fresh store: load_settings() would report enabled via defaults,
        // but the strict gate refuses without a persisted opt-in.
        assert!(store.load_settings().sync.enabled);
        assert!(!store.sync_enabled_explicit());
        enable_sync(&store);
        assert!(store.sync_enabled_explicit());
        let mut s = store.load_settings();
        s.sync.enabled = false;
        store.save_settings(&s).unwrap();
        assert!(!store.sync_enabled_explicit());
        // Corrupt blob: defaults would still report enabled, strict
        // parse refuses.
        store
            .conn
            .execute("UPDATE kv SET value = '{broken' WHERE key = 'settings'", [])
            .unwrap();
        assert!(store.load_settings().sync.enabled);
        assert!(!store.sync_enabled_explicit());
    }

    #[test]
    fn mark_incoming_request_latches_only_untrusted() {
        let store = Store::open_memory().unwrap();
        // Unknown id: nothing to latch.
        assert!(!store.mark_incoming_request(&peer_id(0xf5)).unwrap());
        // Known untrusted row latches pending (e.g. our WAITING row when
        // they dial back, or a known stranger redialling).
        let peer = peer_id(0xf6);
        store.record_outgoing(&peer, "wanted", &[]).unwrap();
        assert!(store.mark_incoming_request(&peer).unwrap());
        let row = store.get_peer(&peer).unwrap().unwrap();
        assert!(row.pending && row.outgoing && !row.trusted);
        // Trusted rows are untouched.
        let buddy = peer_id(0xf7);
        trust(&store, &buddy);
        assert!(!store.mark_incoming_request(&buddy).unwrap());
        assert!(!store.get_peer(&buddy).unwrap().unwrap().pending);
        // Revoked ids are refused, never latched.
        let denied = peer_id(0xf8);
        store.record_seen(&denied, "denied", &[]).unwrap();
        store.decide_request(&denied, false).unwrap();
        assert!(store.mark_incoming_request(&denied).is_err());
    }

    #[test]
    fn v2_database_migrates_outgoing_column() {
        static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(2000);
        // Pin the db key so file-backed opens in this process use it.
        // Fresh temp DBs elsewhere are unaffected: any valid key opens
        // them, and no test asserts the keyring source.
        unsafe {
            std::env::set_var("MYWHISPER_DB_KEY", "ab".repeat(32));
        }
        let dir = std::env::temp_dir().join(format!(
            "mywhisper-v2migrate-test-{}-{}",
            std::process::id(),
            N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        ));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("state.db");
        // A previous-release file: current schema minus the outgoing
        // column, stamped user_version 2.
        let v2_schema = SCHEMA
            .replace("    outgoing INTEGER NOT NULL DEFAULT 0,\n", "")
            .replace("PRAGMA user_version = 3;", "PRAGMA user_version = 2;");
        assert!(!v2_schema.contains("outgoing"));
        let incoming = peer_id(0xe5);
        let buddy = peer_id(0xe6);
        {
            let plain = Connection::open(&path).unwrap();
            plain.execute_batch(&v2_schema).unwrap();
            plain
                .execute(
                    "INSERT INTO devices
                        (node_id, name, addresses, trusted, pending, last_seen)
                     VALUES (?1, 'asker', '[]', 0, 1, 0)",
                    [&incoming],
                )
                .unwrap();
            plain
                .execute(
                    "INSERT INTO devices
                        (node_id, name, addresses, trusted, pending, last_seen)
                     VALUES (?1, 'buddy', '[]', 1, 0, 0)",
                    [&buddy],
                )
                .unwrap();
            drop(plain);
        }
        // Opening migrates (plaintext legacy encrypts in place, then the
        // v2 columns gain `outgoing` defaulting to 0).
        let store = Store::open(&path).unwrap();
        let version: i64 = store
            .conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, 3);
        assert!(has_column(&store.conn, "devices", "outgoing"));
        // Pre-existing rows read back with outgoing clear: the old
        // pending row is still incoming-only.
        let row = store.get_peer(&incoming).unwrap().unwrap();
        assert!(row.pending && !row.outgoing && !row.trusted);
        assert_eq!(store.list_pending().unwrap().len(), 1);
        assert!(store.list_outgoing().unwrap().is_empty());
        // ...and the new outgoing path works on the migrated file.
        let fresh = peer_id(0xe7);
        store.record_outgoing(&fresh, "fresh", &[]).unwrap();
        assert_eq!(store.list_outgoing().unwrap().len(), 1);
        assert_eq!(store.list_pending().unwrap().len(), 1);
        drop(store);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
