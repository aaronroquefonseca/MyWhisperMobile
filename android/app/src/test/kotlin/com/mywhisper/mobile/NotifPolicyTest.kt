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
}
