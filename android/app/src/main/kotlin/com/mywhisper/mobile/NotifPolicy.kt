package com.mywhisper.mobile

import org.json.JSONObject

/**
 * Per-mode notification policy (unit-testable, no Android deps).
 *
 * Modes: float IDLE / RECORDING / WORKING (processing) + sync.
 * Each mode resolves its own ongoing flag + progress visibility from
 * `preferences` booleans, so a prefs toggle applies on the next
 * [buildNotif]/refresh without a process restart. Every mode keeps
 * STOP + contentIntent (see OverlayService/SyncService builders) —
 * only the title/text/progress/ongoing bits vary here.
 *
 * The Map-based entry points are the unit-tested core
 * (`org.json` is an Android stub under JVM tests, so tests pass
 * plain maps); the `*Json` overloads adapt the live settings object.
 */
object NotifPolicy {
    const val KEY_IDLE_ONGOING = "notif_idle_ongoing"
    const val KEY_REC_ONGOING = "notif_rec_ongoing"
    const val KEY_WORK_PROGRESS = "notif_work_progress"
    const val KEY_SYNC_ONGOING = "notif_sync_ongoing"
    const val KEY_SYNC_PROGRESS = "notif_sync_progress"

    /** Parsed float prefs (defaults keep current behavior). */
    data class FloatPrefs(
        val idleOngoing: Boolean = true,
        val recOngoing: Boolean = true,
        val workProgress: Boolean = true,
    )

    /** Parsed sync prefs (defaults keep current behavior). */
    data class SyncPrefs(
        val ongoing: Boolean = true,
        val showProgress: Boolean = true,
    )

    /** Resolved float spec for one [Dictation.State]. */
    data class FloatSpec(val ongoing: Boolean, val showProgress: Boolean)

    /** Resolved sync spec for idle vs syncing. */
    data class SyncSpec(val ongoing: Boolean, val showProgress: Boolean)

    private fun boolOf(prefs: Map<String, Boolean?>?, key: String): Boolean =
        prefs?.get(key) ?: true

    @JvmStatic
    fun floatPrefs(prefs: Map<String, Boolean?>?): FloatPrefs {
        if (prefs == null) return FloatPrefs()
        return FloatPrefs(
            idleOngoing = boolOf(prefs, KEY_IDLE_ONGOING),
            recOngoing = boolOf(prefs, KEY_REC_ONGOING),
            workProgress = boolOf(prefs, KEY_WORK_PROGRESS),
        )
    }

    @JvmStatic
    fun syncPrefs(prefs: Map<String, Boolean?>?): SyncPrefs {
        if (prefs == null) return SyncPrefs()
        return SyncPrefs(
            ongoing = boolOf(prefs, KEY_SYNC_ONGOING),
            showProgress = boolOf(prefs, KEY_SYNC_PROGRESS),
        )
    }

    /** Production adapter: live settings `preferences` object. */
    @JvmStatic
    fun floatPrefsJson(prefs: JSONObject?): FloatPrefs {
        if (prefs == null) return FloatPrefs()
        return FloatPrefs(
            idleOngoing = prefs.optBoolean(KEY_IDLE_ONGOING, true),
            recOngoing = prefs.optBoolean(KEY_REC_ONGOING, true),
            workProgress = prefs.optBoolean(KEY_WORK_PROGRESS, true),
        )
    }

    /** Production adapter: live settings `preferences` object. */
    @JvmStatic
    fun syncPrefsJson(prefs: JSONObject?): SyncPrefs {
        if (prefs == null) return SyncPrefs()
        return SyncPrefs(
            ongoing = prefs.optBoolean(KEY_SYNC_ONGOING, true),
            showProgress = prefs.optBoolean(KEY_SYNC_PROGRESS, true),
        )
    }

    /**
     * Resolve one float state. IDLE/RECORDING never show progress;
     * WORKING shows indeterminate progress only when the pref allows.
     * Ongoing is per-mode so each toggle is independently observable.
     */
    @JvmStatic
    fun forFloatState(state: Dictation.State, prefs: FloatPrefs): FloatSpec =
        when (state) {
            Dictation.State.IDLE -> FloatSpec(ongoing = prefs.idleOngoing, showProgress = false)
            Dictation.State.RECORDING -> FloatSpec(ongoing = prefs.recOngoing, showProgress = false)
            Dictation.State.WORKING -> FloatSpec(ongoing = true, showProgress = prefs.workProgress)
        }

    /** Resolve sync idle vs syncing. Progress only while syncing. */
    @JvmStatic
    fun forSync(syncing: Boolean, prefs: SyncPrefs): SyncSpec =
        if (syncing) SyncSpec(ongoing = prefs.ongoing, showProgress = prefs.showProgress)
        else SyncSpec(ongoing = prefs.ongoing, showProgress = false)

    // -- Unified foreground row (one row for bubble + sync) --------------

    /**
     * Shared notification identity: BOTH services call startForeground
     * with this ID so the shade shows one row. Each service still holds
     * its own foreground state (independent FGS types); the visible row
     * is last-writer-wins, always composed from [UnifiedState].
     */
    const val UNIFIED_CHANNEL = "mw-status"
    const val UNIFIED_NOTIF_ID = 41

    /** Legacy bubble row retired by the unification (cancelled on start). */
    const val LEGACY_FLOAT_NOTIF_ID = 42

    /**
     * Process-wide live state (both services run in the same process,
     * so a singleton suffices — no IPC). Writers: OverlayService sets
     * float*, SyncService sets sync*. Readers: both builders compose
     * from the full snapshot so either row refresh reflects both.
     */
    object UnifiedState {
        @Volatile var floatActive: Boolean = false
        @Volatile var floatState: Dictation.State = Dictation.State.IDLE
        @Volatile var syncActive: Boolean = false
        @Volatile var syncing: Boolean = false

        fun reset() {
            floatActive = false
            floatState = Dictation.State.IDLE
            syncActive = false
            syncing = false
        }
    }

    /** Which mode paints the unified row (single chrome, no stacking). */
    enum class UnifiedChrome { FLOAT_RECORDING, FLOAT_WORKING, FLOAT_IDLE, SYNCING, SYNC_IDLE }

    /**
     * Resolved unified spec: exactly one chrome + its ongoing/progress
     * + whether the Sync-now action applies (sync chrome only). A
     * disabled/hidden mode contributes nothing — only the shown mode's
     * prefs resolve here.
     */
    data class UnifiedSpec(
        val chrome: UnifiedChrome,
        val ongoing: Boolean,
        val showProgress: Boolean,
        val showSyncNow: Boolean,
    )

    /**
     * Compose one row from both states. Bubble wins while active
     * (RECORDING/WORKING → its title/text/actions); otherwise sync
     * paints when alive, else the lone idle bubble. Null when neither
     * lives (last one out removes the row).
     */
    @JvmStatic
    fun forUnified(
        floatActive: Boolean,
        floatState: Dictation.State,
        floatPrefs: FloatPrefs,
        syncActive: Boolean,
        syncing: Boolean,
        syncPrefs: SyncPrefs,
    ): UnifiedSpec? {
        if (!floatActive && !syncActive) return null
        if (floatActive && floatState != Dictation.State.IDLE) {
            val spec = forFloatState(floatState, floatPrefs)
            val chrome = if (floatState == Dictation.State.RECORDING) {
                UnifiedChrome.FLOAT_RECORDING
            } else {
                UnifiedChrome.FLOAT_WORKING
            }
            return UnifiedSpec(chrome, spec.ongoing, spec.showProgress, showSyncNow = false)
        }
        if (syncActive) {
            val spec = forSync(syncing, syncPrefs)
            val chrome = if (syncing) UnifiedChrome.SYNCING else UnifiedChrome.SYNC_IDLE
            return UnifiedSpec(chrome, spec.ongoing, spec.showProgress, showSyncNow = true)
        }
        val spec = forFloatState(Dictation.State.IDLE, floatPrefs)
        return UnifiedSpec(UnifiedChrome.FLOAT_IDLE, spec.ongoing, spec.showProgress, showSyncNow = false)
    }
}
