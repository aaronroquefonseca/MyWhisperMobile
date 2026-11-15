package com.mywhisper.mobile

import java.io.File

/**
 * Row-state machine for the Local STT/LLM download rows (desktop
 * `fill_stt_models` parity: install state + Download/Delete/Retry
 * actions + in-place progress). Pure JVM, unit-tested.
 *
 * States: MISSING (nothing usable) / DOWNLOADING (worker in flight)
 * / CHECKING (pack verify running in background — rows show this,
 * never a stale MISSING, until the first pass lands) / INCOMPLETE
 * (interrupted set: subset of files, short file, or stale `.part` —
 * resumable, NEVER "SHA mismatch") / PRESENT (verified, toggle
 * live) / MISMATCH (COMPLETE files fail verify, re-download) /
 * FAILED (last download threw, Retry offered).
 * Fail-closed: only PRESENT enables the toggle.
 */
object LocalModelDownload {
    enum class RowState { MISSING, DOWNLOADING, CHECKING, INCOMPLETE, PRESENT, MISMATCH, FAILED }

    /**
     * Resolve the row state. Precedence: an in-flight download wins
     * over everything (progress shows in place); otherwise a terminal
     * error pins FAILED until Retry/Delete clears it; otherwise a
     * running background verify reads CHECKING (never stale MISSING);
     * otherwise the verified-pack outcome decides. [incomplete]
     * (subset/short/stale partial, nothing verified) reads as
     * INCOMPLETE — resumable — and never as MISMATCH; MISMATCH needs
     * [packMismatch] (complete files that fail verify).
     */
    @JvmStatic
    fun resolve(
        packPresent: Boolean,
        packMismatch: Boolean,
        downloading: Boolean,
        error: String?,
        incomplete: Boolean = false,
        checking: Boolean = false,
    ): RowState = when {
        downloading -> RowState.DOWNLOADING
        !error.isNullOrEmpty() -> RowState.FAILED
        checking -> RowState.CHECKING
        packPresent -> RowState.PRESENT
        packMismatch -> RowState.MISMATCH
        incomplete -> RowState.INCOMPLETE
        else -> RowState.MISSING
    }

    /** Fail-closed gate: the Local toggle goes live on PRESENT only. */
    @JvmStatic
    fun toggleAllowed(state: RowState): Boolean = state == RowState.PRESENT

    /**
     * True when tapping Select on a row in [state] must offer Download:
     * anything but PRESENT transcribes via the server chain until its
     * own pack verifies. Selecting never removes other packs — packs
     * coexist, exactly one stays active (see [fallbackAfterDelete]).
     * Pure for unit tests.
     */
    @JvmStatic
    fun selectNeedsDownload(state: RowState): Boolean = state != RowState.PRESENT

    /**
     * Single-active fallback after deleting [deletedId]: a deleted
     * bystander keeps [selectedId]; deleting the active pack reselects
     * the first still-[presentIds] entry (catalog/manifest order), else
     * [defaultId]. Always returns a known id — never a dead Local
     * selection; callers pair the default fallback with a server-chain
     * note. Pure for unit tests.
     */
    @JvmStatic
    fun fallbackAfterDelete(
        deletedId: String,
        selectedId: String,
        presentIds: List<String>,
        defaultId: String,
    ): String {
        if (deletedId != selectedId) return selectedId
        return presentIds.firstOrNull { it != deletedId } ?: defaultId
    }

    /**
     * Whole-MB progress fraction for a LinearProgressIndicator, null
     * when the total is unknown (indeterminate bar). Pure for tests.
     */
    @JvmStatic
    fun progressFraction(receivedBytes: Long, totalBytes: Long?): Float? {
        if (totalBytes == null || totalBytes <= 0 || receivedBytes < 0) return null
        return (receivedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    @JvmStatic
    fun progressMb(receivedBytes: Long, totalBytes: Long?): Pair<Long, Long?> =
        Pair(receivedBytes / 1_000_000, totalBytes?.let { it / 1_000_000 })

    /**
     * Fingerprint of a pack's on-disk inputs for the verify cache:
     * `name=length@mtime` per file (`name:-` when absent), joined.
     * Stat-only (no content reads) — safe to compute on any thread,
     * including composition. Any download/delete/modify changes the
     * fingerprint, so cached verdicts can never go stale.
     * Pure for unit tests.
     */
    @JvmStatic
    fun packFingerprint(files: List<File>): String =
        files.sortedBy { it.name }.joinToString("|") { f ->
            try {
                val a = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                "${f.name}=${a.size()}@${a.lastModifiedTime()}@${a.creationTime()}@${a.fileKey()}"
            } catch (_: java.nio.file.NoSuchFileException) {
                "${f.name}:-"
            }
        }

    /**
     * Fingerprint-gated verify cache: verification (GB-scale SHA)
     * runs at most once per on-disk state. Recomposition bumps,
     * tab switches and scrolls hit the cache with stat-only cost —
     * no re-hash storms, no UI-thread hashing, no ANR. Callers post
     * results back to row state themselves (CHECKING meanwhile).
     * Thread-safe. Pure JVM-testable with fakes.
     */
    class PackVerifyCache {
        private data class Work(
            val fingerprint: String,
            val task: java.util.concurrent.FutureTask<Any>,
            val owner: java.util.concurrent.atomic.AtomicReference<Thread>,
        )
        private val lock = Any()
        private val map = mutableMapOf<String, Work>()

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> getOrPut(id: String, fingerprint: String, compute: () -> T): T {
            while (true) {
                val work = synchronized(lock) {
                    map[id]?.takeIf { it.fingerprint == fingerprint } ?: run {
                        val owner = java.util.concurrent.atomic.AtomicReference<Thread>()
                        Work(fingerprint, java.util.concurrent.FutureTask {
                            owner.set(Thread.currentThread())
                            compute() as Any
                        }, owner).also { map[id] = it }
                    }
                }
                // FutureTask runs once even when multiple callers race. Never hash under lock.
                work.task.run()
                try {
                    return work.task.get() as T
                } catch (e: java.util.concurrent.ExecutionException) {
                    synchronized(lock) { if (map[id] === work) map.remove(id) }
                    // The owner may have left composition while another caller still needs it.
                    if (e.cause is InterruptedException && work.owner.get() !== Thread.currentThread() &&
                        !Thread.currentThread().isInterrupted) continue
                    throw e
                } catch (e: Exception) {
                    synchronized(lock) { if (work.task.isDone && map[id] === work) map.remove(id) }
                    throw e
                }
            }
        }

        /** Never return a verdict for a fingerprint that changed while verification ran. */
        fun <T : Any> getStable(
            id: String,
            fingerprint: String,
            currentFingerprint: () -> String?,
            stillCurrent: () -> Boolean = { true },
            compute: () -> T,
        ): T? {
            if (!stillCurrent() || currentFingerprint() != fingerprint) return null
            val result = getOrPut(id, fingerprint) {
                val value = compute()
                check(currentFingerprint() == fingerprint) { "Pack changed during verification" }
                value
            }
            return result.takeIf { stillCurrent() && currentFingerprint() == fingerprint }
        }

        fun invalidate(id: String) = synchronized(lock) { map.remove(id); Unit }

        fun invalidateAll() = synchronized(lock) { map.clear() }

        fun has(id: String, fingerprint: String): Boolean = synchronized(lock) {
            val work = map[id]
            fingerprint.isNotEmpty() && work?.fingerprint == fingerprint &&
                work.task.isDone && !work.task.isCancelled
        }
    }

    /**
     * Network posture for the constraint-blocked download message.
     * UNMETERED = work can run; METERED/OFFLINE = WorkManager holds
     * ENQUEUED (unmetered constraint) and the row would otherwise sit
     * at 0 bytes forever with no explanation (owner repro: cancelled
     * a stalled 1GB download, retry stalled identically).
     */
    enum class NetState { OFFLINE, METERED, UNMETERED }

    /**
     * True when a DOWNLOADING row has no bytes yet AND the network
     * posture explains it (metered/offline) — the row should say
     * "waiting", not fake progress. Null/unknown posture never
     * triggers (no wrong text); unmetered never triggers (zero bytes
     * there just means starting up). Pure for unit tests.
     */
    @JvmStatic
    fun needsWaiting(receivedBytes: Long?, net: NetState?): Boolean =
        (receivedBytes ?: 0L) <= 0L && (net == NetState.METERED || net == NetState.OFFLINE)
}
