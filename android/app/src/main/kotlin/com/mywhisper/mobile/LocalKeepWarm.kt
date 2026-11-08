package com.mywhisper.mobile

/**
 * Keep-warm policies for on-device models (STT recognizers + LLM
 * engines). Every runner holds ONE resident slot (memory bound —
 * a 1–3GB engine per warm model would OOM phones); the loaded
 * entry's per-model policy governs it:
 *
 * - `IMMEDIATE` (0): release right after each use (LLM default —
 *   today's MediaPipe/LiteRT behavior: load per call).
 * - N seconds: stay loaded, evicted lazily once idle longer than N
 *   (no polling timers — every use sweeps, so an entry can linger
 *   past its deadline until the next use; harmless, documented).
 * - `INDEFINITE` (-1): never idle-evict (STT default — today's
 *   cached-recognizer behavior).
 *
 * Policies live in `mw_state` SharedPreferences
 * (`keep_stt_<id>` / `keep_llm_<id>`, seconds) so they survive
 * process death; thinking budgets (`think_llm_<id>`, tokens,
 * 0 = off) ride alongside. Pure math + prefs helpers here (unit
 * tested); runners add three vars (slot/last-used) and a sweep.
 */
object LocalKeepWarm {
    /** Release right after each use. */
    const val IMMEDIATE = 0L

    /** Never idle-evict (explicit drops still apply). */
    const val INDEFINITE = -1L

    const val KIND_STT = "stt"
    const val KIND_LLM = "llm"

    /** Preset menu values, seconds ([IMMEDIATE]/[INDEFINITE] incl). */
    fun presets(): List<Long> = listOf(0L, 60L, 600L, 1800L, -1L)

    /** Thinking token budgets, 0 = off (default). */
    fun thinkBudgets(): List<Int> = listOf(0, 512, 1024, 2048)

    fun keepKey(kind: String, id: String): String = "keep_${kind}_$id"

    fun thinkKey(id: String): String = "think_llm_$id"

    /** True when an entry used at [lastUsedMs] is past its keep window. Pure. */
    @JvmStatic
    fun isExpired(lastUsedMs: Long, keepSecs: Long, nowMs: Long): Boolean =
        keepSecs >= 0 && nowMs - lastUsedMs > keepSecs * 1000L

    @JvmStatic
    fun readKeepSecs(
        prefs: android.content.SharedPreferences,
        kind: String,
        id: String,
        default: Long,
    ): Long {
        return try {
            val k = keepKey(kind, id)
            if (prefs.contains(k)) prefs.getLong(k, default) else default
        } catch (_: Exception) {
            default
        }
    }

    @JvmStatic
    fun writeKeepSecs(
        prefs: android.content.SharedPreferences,
        kind: String,
        id: String,
        secs: Long,
    ) {
        try {
            prefs.edit().putLong(keepKey(kind, id), secs).apply()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun readThinkBudget(prefs: android.content.SharedPreferences, id: String): Int {
        return try {
            val k = thinkKey(id)
            if (prefs.contains(k)) prefs.getInt(k, 0) else 0
        } catch (_: Exception) {
            0
        }
    }

    @JvmStatic
    fun writeThinkBudget(prefs: android.content.SharedPreferences, id: String, budget: Int) {
        try {
            prefs.edit().putInt(thinkKey(id), budget).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Monotonic clock for idle math (uptime, not wall time — sleeps
     * don't burn keep windows). Thin wrapper so tests inject time.
     */
    @JvmStatic
    fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}
