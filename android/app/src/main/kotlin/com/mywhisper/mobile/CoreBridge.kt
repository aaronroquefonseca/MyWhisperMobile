package com.mywhisper.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge to `mywhisper-core` (core/src/lib.rs).
 *
 * Every call returns the core's `{ok, data?, error?}` envelope as a raw
 * string; helpers below parse it. Transcription blocks — always call
 * `finishUtterance` off the UI thread.
 */
object CoreBridge {
    init {
        System.loadLibrary("mywhisper_core")
    }

    @JvmStatic external fun nativeOpen(filesDir: String, dbKeyHex: String): String
    @JvmStatic external fun nativeGetSettings(): String
    @JvmStatic external fun nativePutSettings(json: String): String
    @JvmStatic external fun nativeStartUtterance(sampleRate: Int): String
    @JvmStatic external fun nativePushPcm(data: ShortArray, length: Int): String
    @JvmStatic external fun nativeFinishUtterance(): String
    @JvmStatic external fun nativeFinishLocalUtterance(raw: String, text: String, engine: String, durationSecs: Int): String
    @JvmStatic external fun nativeSetLocalLlmRunner(runner: LocalLlmCleanupRunner?): String
    @JvmStatic external fun nativeCancelUtterance(): String
    @JvmStatic external fun nativeGetHistory(limit: Int): String
    @JvmStatic external fun nativeClearHistory(): String
    @JvmStatic external fun nativeDeleteHistory(uuid: String): String
    @JvmStatic external fun nativeDefaultCleanupPrompt(): String
    @JvmStatic external fun nativePurgeHistory(days: Int): String
    @JvmStatic external fun nativeUsageStats(): String
    @JvmStatic external fun nativeDailyActivity(days: Int): String
    @JvmStatic external fun nativeSyncStart(): String
    @JvmStatic external fun nativeSyncStop(): String
    @JvmStatic external fun nativeSyncStatus(): String
    @JvmStatic external fun nativeSyncPeers(): String
    @JvmStatic external fun nativeSyncTrust(nodeId: String, accept: Boolean): String
    @JvmStatic external fun nativeSyncNow(nodeId: String): String
    @JvmStatic external fun nativeSyncAllNow(): String
    @JvmStatic external fun nativeDisplayName(): String
    @JvmStatic external fun nativeSyncAdd(nodeId: String, address: String, name: String): String
    @JvmStatic external fun nativeGetWords(): String
    @JvmStatic external fun nativeAddWord(word: String): String
    @JvmStatic external fun nativeDeleteWord(word: String): String
    @JvmStatic external fun nativeGetSnippets(): String
    @JvmStatic external fun nativePutSnippet(trigger: String, replacement: String): String
    @JvmStatic external fun nativeDeleteSnippet(trigger: String): String
    @JvmStatic external fun nativeLocalLlmStatus(): String
    @JvmStatic external fun nativeLocalLlmPrompt(system: String, transcript: String): String

    /** Parse `{ok, data?, error?}`. Returns data or throws with error text. */
    fun dataOf(raw: String): Any? {
        val o = JSONObject(raw)
        if (!o.optBoolean("ok", false)) {
            throw IllegalStateException(o.optString("error", "unknown core error"))
        }
        if (o.isNull("data")) return null
        return o.get("data")
    }

    fun open(filesDir: String, dbKeyHex: String): Result<String> = runCatching {
        dataOf(nativeOpen(filesDir, dbKeyHex)) as? JSONObject
            ?: throw IllegalStateException("bad open reply")
        "ok"
    }

    fun getSettings(): Result<JSONObject> = runCatching {
        JSONObject(nativeGetSettings())
    }

    fun putSettings(s: JSONObject): Result<Unit> = runCatching {
        dataOf(nativePutSettings(s.toString()))
        Unit
    }

    fun startUtterance(rate: Int) {
        nativeStartUtterance(rate)
    }

    fun pushPcm(data: ShortArray, n: Int) {
        nativePushPcm(data, n)
    }

    /** Blocks on the server chain. Returns (text, engine) or throws. */
    fun finishUtterance(): Pair<String, String> = finishReply(nativeFinishUtterance())

    /**
     * On-device STT hit: run the same LLM cleanup chain + history
     * write as [finishUtterance]. Blocks on cleanup — worker thread
     * only. Returns (text, engine) or throws.
     */
    fun finishLocalUtterance(raw: String, text: String, engine: String, durationSecs: Int): Pair<String, String> =
        finishReply(nativeFinishLocalUtterance(raw, text, engine, durationSecs))

    /** Wire the on-device LLM into the dictation cleanup chain (once per process). */
    fun setLocalLlmRunner(runner: LocalLlmCleanupRunner?) {
        dataOf(nativeSetLocalLlmRunner(runner))
    }

    private fun finishReply(raw: String): Pair<String, String> {
        val d = dataOf(raw) as JSONObject
        // Per-entry cleanup outcome ("Ollama: llm post: ..."): the only
        // trace of why cleanup did or did not apply.
        d.optJSONArray("cleanup")?.let { notes ->
            try {
                android.util.Log.i("MwCleanup", (0 until notes.length()).joinToString(" | ") { notes.optString(it) })
            } catch (_: Throwable) {
            }
        }
        return d.getString("text") to d.optString("engine", "")
    }

    fun cancelUtterance() {
        nativeCancelUtterance()
    }

    fun defaultCleanupPrompt(): Result<String> = runCatching {
        dataOf(nativeDefaultCleanupPrompt()) as String
    }

    fun purgeHistory(days: Int): Result<Int> = runCatching {
        (dataOf(nativePurgeHistory(days)) as JSONObject).getInt("removed")
    }

    fun usageStats(): Result<JSONObject> = runCatching {
        dataOf(nativeUsageStats()) as JSONObject
    }

    fun dailyActivity(days: Int): Result<JSONArray> = runCatching {
        dataOf(nativeDailyActivity(days)) as JSONArray
    }

    fun syncStart(): Result<String> = runCatching {
        dataOf(nativeSyncStart()) as String
    }

    fun syncStop() {
        nativeSyncStop()
    }

    fun syncStatus(): Result<JSONObject> = runCatching {
        JSONObject(syncStatusRaw())
    }

    fun syncStatusRaw(): String = nativeSyncStatus()

    fun syncPeersRaw(): String = nativeSyncPeers()

    fun syncTrust(nodeId: String, accept: Boolean): Result<String> = runCatching {
        dataOf(nativeSyncTrust(nodeId, accept)) as String
    }

    fun displayName(): String {
        return try {
            dataOf(nativeDisplayName()) as String
        } catch (_: Exception) {
            ""
        }
    }

    fun syncAdd(nodeId: String, address: String, name: String): Result<String> = runCatching {
        dataOf(nativeSyncAdd(nodeId, address, name)) as String
    }

    /** Store-backed dictionary (desktop app_dictionary parity). */
    fun getWords(): List<String> {
        return try {
            val arr = JSONArray(nativeGetWords())
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addWord(word: String): Result<Unit> = runCatching {
        dataOf(nativeAddWord(word))
        Unit
    }

    fun deleteWord(word: String): Result<Unit> = runCatching {
        dataOf(nativeDeleteWord(word))
        Unit
    }

    fun getSnippets(): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(nativeGetSnippets())
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                o.getString("trigger") to o.getString("replacement")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun putSnippet(trigger: String, replacement: String): Result<Unit> = runCatching {
        dataOf(nativePutSnippet(trigger, replacement))
        Unit
    }

    fun deleteSnippet(trigger: String): Result<Unit> = runCatching {
        dataOf(nativeDeleteSnippet(trigger))
        Unit
    }

    /** On-device LLM status (cleanup path only): raw JSON from core. */
    fun localLlmStatusRaw(): String = nativeLocalLlmStatus()

    /** Byte-stable cleanup prompt packing (core-mirrored). */
    fun localLlmPrompt(system: String, transcript: String): String =
        nativeLocalLlmPrompt(system, transcript)

    fun syncNow(nodeId: String): Result<String> = runCatching {
        dataOf(nativeSyncNow(nodeId)) as String
    }

    fun syncAllNow(): Result<List<String>> = runCatching {
        @Suppress("UNCHECKED_CAST")
        (dataOf(nativeSyncAllNow()) as JSONArray).let { arr ->
            List(arr.length()) { arr.getString(it) }
        }
    }

    /**
     * Generation-aware cancellable job plumbing (desktop settings-page
     * jobs parity: `ServerProbeTask` / `ModelLoad` / `CHECK_EPOCH`).
     *
     * Screens claim a generation before running a worker; the worker
     * commits its result on the UI thread only when its generation is
     * still current. Config changes, navigation or a newer job bump
     * the generation, so stale results are dropped instead of
     * clobbering newer committed state.
     *
     * Everything here is pure JVM (no JNI) for unit tests. Keep it
     * that way: members must never touch `native*` nor trigger the
     * outer object's `loadLibrary` init.
     */
    object SettingsJobs {
        private val epoch = AtomicLong(0)

        /** Committed-store warning meaning "reset left a retry behind". */
        const val RESET_INCOMPLETE_WARNING = "settings.reset.incomplete"

        /** Claim the next generation for a new job. */
        @JvmStatic
        fun claim(): Long = epoch.incrementAndGet()

        /** True when [gen] is still the latest claimed generation. */
        @JvmStatic
        fun isCurrent(gen: Long): Boolean = gen != 0L && gen == epoch.get()

        /** Cancel all in-flight jobs: stale workers drop their results. */
        @JvmStatic
        fun invalidate() {
            epoch.incrementAndGet()
        }

        /**
         * Per-screen generation guard (desktop `Rc::ptr_eq` parity):
         * a worker commits only when its generation still equals the
         * screen's current one.
         */
        @JvmStatic
        fun jobApplies(currentGen: Long, jobGen: Long): Boolean = currentGen == jobGen

        /** Next per-screen generation (wraps past Long.MAX_VALUE, never 0). */
        @JvmStatic
        fun nextJobGen(currentGen: Long): Long =
            if (currentGen == Long.MAX_VALUE || currentGen + 1L == 0L) 1L else currentGen + 1L

        /** True when the committed warning leaves a retryable reset behind. */
        @JvmStatic
        fun resetNeedsRetry(warning: String?): Boolean = warning == RESET_INCOMPLETE_WARNING

        /** Test-only reset of the shared epoch. */
        @JvmStatic
        fun resetForTests() {
            epoch.set(0)
        }
    }
}
