package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM rules for the per-mode notification policy (see NotifPolicy):
 * idle/recording/processing/sync each resolve their own
 * ongoing/progress flags from prefs; toggles apply without restart.
 */
class NotifPolicyTest {

    // -- Defaults (current behavior preserved) -----------------------

    @Test
    fun defaults_matchLegacyBehavior() {
        val f = NotifPolicy.floatPrefs(emptyMap())
        assertTrue(f.idleOngoing)
        assertTrue(f.recOngoing)
        assertTrue(f.workProgress)
        val s = NotifPolicy.syncPrefs(emptyMap())
        assertTrue(s.ongoing)
        assertTrue(s.showProgress)
    }

    @Test
    fun nullPrefs_yieldDefaults() {
        val f = NotifPolicy.floatPrefs(null)
        assertEquals(NotifPolicy.FloatPrefs(), f)
        val s = NotifPolicy.syncPrefs(null)
        assertEquals(NotifPolicy.SyncPrefs(), s)
    }

    // -- Per-mode resolution -----------------------------------------

    @Test
    fun floatModes_resolveDistinctSpecs() {
        val p = NotifPolicy.FloatPrefs()
        val idle = NotifPolicy.forFloatState(Dictation.State.IDLE, p)
        val rec = NotifPolicy.forFloatState(Dictation.State.RECORDING, p)
        val work = NotifPolicy.forFloatState(Dictation.State.WORKING, p)
        // No stale mode: progress only while processing.
        assertFalse(idle.showProgress)
        assertFalse(rec.showProgress)
        assertTrue(work.showProgress)
        assertTrue(idle.ongoing)
        assertTrue(rec.ongoing)
        assertTrue(work.ongoing)
    }

    @Test
    fun floatToggles_immediatelyReflected() {
        val off = NotifPolicy.floatPrefs(
            mapOf(
                NotifPolicy.KEY_IDLE_ONGOING to false,
                NotifPolicy.KEY_REC_ONGOING to false,
                NotifPolicy.KEY_WORK_PROGRESS to false,
            ),
        )
        assertFalse(NotifPolicy.forFloatState(Dictation.State.IDLE, off).ongoing)
        assertFalse(NotifPolicy.forFloatState(Dictation.State.RECORDING, off).ongoing)
        assertFalse(NotifPolicy.forFloatState(Dictation.State.WORKING, off).showProgress)
        // Still ongoing while processing (FGS must stay foreground).
        assertTrue(NotifPolicy.forFloatState(Dictation.State.WORKING, off).ongoing)
    }

    @Test
    fun floatToggles_independentPerMode() {
        val p = NotifPolicy.floatPrefs(mapOf(NotifPolicy.KEY_IDLE_ONGOING to false))
        assertFalse(NotifPolicy.forFloatState(Dictation.State.IDLE, p).ongoing)
        assertTrue(NotifPolicy.forFloatState(Dictation.State.RECORDING, p).ongoing)
    }

    // -- Sync ----------------------------------------------------------

    @Test
    fun sync_progressOnlyWhileSyncing() {
        val p = NotifPolicy.SyncPrefs()
        assertFalse(NotifPolicy.forSync(false, p).showProgress)
        assertTrue(NotifPolicy.forSync(true, p).showProgress)
        assertTrue(NotifPolicy.forSync(false, p).ongoing)
        assertTrue(NotifPolicy.forSync(true, p).ongoing)
    }

    @Test
    fun syncToggles_immediatelyReflected() {
        val off = NotifPolicy.syncPrefs(
            mapOf(
                NotifPolicy.KEY_SYNC_ONGOING to false,
                NotifPolicy.KEY_SYNC_PROGRESS to false,
            ),
        )
        assertFalse(NotifPolicy.forSync(true, off).ongoing)
        assertFalse(NotifPolicy.forSync(true, off).showProgress)
        assertFalse(NotifPolicy.forSync(false, off).ongoing)
    }

    // -- Unified row (one row for bubble + sync) -----------------------

    @Test
    fun unified_neitherActive_showsNothing() {
        assertEquals(
            null,
            NotifPolicy.forUnified(
                floatActive = false,
                floatState = Dictation.State.IDLE,
                floatPrefs = NotifPolicy.FloatPrefs(),
                syncActive = false,
                syncing = false,
                syncPrefs = NotifPolicy.SyncPrefs(),
            ),
        )
    }

    @Test
    fun unified_floatOnly_paintsFloat() {
        val f = NotifPolicy.FloatPrefs()
        val s = NotifPolicy.SyncPrefs()
        assertEquals(
            NotifPolicy.UnifiedChrome.FLOAT_IDLE,
            NotifPolicy.forUnified(true, Dictation.State.IDLE, f, false, false, s)!!.chrome,
        )
        val rec = NotifPolicy.forUnified(true, Dictation.State.RECORDING, f, false, false, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.FLOAT_RECORDING, rec.chrome)
        assertFalse(rec.showSyncNow)
        val work = NotifPolicy.forUnified(true, Dictation.State.WORKING, f, false, true, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.FLOAT_WORKING, work.chrome)
        assertTrue(work.showProgress)
        assertFalse(work.showSyncNow)
    }

    @Test
    fun unified_syncOnly_paintsSync() {
        val f = NotifPolicy.FloatPrefs()
        val s = NotifPolicy.SyncPrefs()
        val idle = NotifPolicy.forUnified(false, Dictation.State.IDLE, f, true, false, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.SYNC_IDLE, idle.chrome)
        assertTrue(idle.showSyncNow)
        assertFalse(idle.showProgress)
        val syncing = NotifPolicy.forUnified(false, Dictation.State.IDLE, f, true, true, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.SYNCING, syncing.chrome)
        assertTrue(syncing.showSyncNow)
        assertTrue(syncing.showProgress)
    }

    @Test
    fun unified_bubbleActive_winsOverSync() {
        val f = NotifPolicy.FloatPrefs()
        val s = NotifPolicy.SyncPrefs()
        // RECORDING beats even an active sync round: no Sync-now action.
        val rec = NotifPolicy.forUnified(true, Dictation.State.RECORDING, f, true, true, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.FLOAT_RECORDING, rec.chrome)
        assertFalse(rec.showSyncNow)
        val work = NotifPolicy.forUnified(true, Dictation.State.WORKING, f, true, true, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.FLOAT_WORKING, work.chrome)
        assertFalse(work.showSyncNow)
    }

    @Test
    fun unified_bubbleIdle_yieldsToSync() {
        val f = NotifPolicy.FloatPrefs()
        val s = NotifPolicy.SyncPrefs()
        val syncing = NotifPolicy.forUnified(true, Dictation.State.IDLE, f, true, true, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.SYNCING, syncing.chrome)
        assertTrue(syncing.showSyncNow)
        val idle = NotifPolicy.forUnified(true, Dictation.State.IDLE, f, true, false, s)!!
        assertEquals(NotifPolicy.UnifiedChrome.SYNC_IDLE, idle.chrome)
    }

    @Test
    fun unified_hiddenMode_showsNothing() {
        // Bubble idle + sync round with progress off: only sync paints,
        // with no progress bar (float contributes nothing).
        val idle = NotifPolicy.forUnified(
            true, Dictation.State.IDLE, NotifPolicy.FloatPrefs(),
            true, true, NotifPolicy.SyncPrefs(showProgress = false),
        )!!
        assertEquals(NotifPolicy.UnifiedChrome.SYNCING, idle.chrome)
        assertFalse(idle.showProgress)
        // Bubble working with progress off + sync round: bubble still
        // wins, no progress, no Sync-now (sync contributes nothing).
        val work = NotifPolicy.forUnified(
            true, Dictation.State.WORKING, NotifPolicy.FloatPrefs(workProgress = false),
            true, true, NotifPolicy.SyncPrefs(),
        )!!
        assertEquals(NotifPolicy.UnifiedChrome.FLOAT_WORKING, work.chrome)
        assertFalse(work.showProgress)
        assertFalse(work.showSyncNow)
        assertTrue(work.ongoing)
    }

    @Test
    fun unified_floatToggles_reflectedInRow() {
        val off = NotifPolicy.floatPrefs(mapOf(NotifPolicy.KEY_IDLE_ONGOING to false))
        val idle = NotifPolicy.forUnified(
            true, Dictation.State.IDLE, off,
            false, false, NotifPolicy.SyncPrefs(),
        )!!
        assertFalse(idle.ongoing)
        val rec = NotifPolicy.forUnified(
            true, Dictation.State.RECORDING, off,
            false, false, NotifPolicy.SyncPrefs(),
        )!!
        assertTrue(rec.ongoing)
    }

    @Test
    fun unified_syncToggles_reflectedInRow() {
        val off = NotifPolicy.syncPrefs(
            mapOf(
                NotifPolicy.KEY_SYNC_ONGOING to false,
                NotifPolicy.KEY_SYNC_PROGRESS to false,
            ),
        )
        val syncing = NotifPolicy.forUnified(
            false, Dictation.State.IDLE, NotifPolicy.FloatPrefs(),
            true, true, off,
        )!!
        assertFalse(syncing.ongoing)
        assertFalse(syncing.showProgress)
    }

    // -- Migration hygiene (retired solo rows/channels) ---------------

    @Test
    fun unifiedIdentity_stable() {
        assertEquals("mw-status", NotifPolicy.UNIFIED_CHANNEL)
        assertEquals(41, NotifPolicy.UNIFIED_NOTIF_ID)
    }

    @Test
    fun legacyNotifId_isRetiredSoloBubbleRow() {
        // The id both services cancel on START and on every STOP path.
        assertEquals(42, NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
        // Cancelling it on STOP must never remove the live shared row.
        assertFalse(NotifPolicy.LEGACY_FLOAT_NOTIF_ID == NotifPolicy.UNIFIED_NOTIF_ID)
    }

    @Test
    fun legacyChannels_exactlyRetiredPair() {
        // The channels both services delete on next start.
        assertEquals(listOf("mw-float", "mw-sync"), NotifPolicy.LEGACY_CHANNEL_IDS)
        assertFalse(NotifPolicy.LEGACY_CHANNEL_IDS.contains(NotifPolicy.UNIFIED_CHANNEL))
    }
}
