package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Generation-aware cancellable job plumbing (desktop settings-page
 * jobs parity: `ServerProbeTask` / `ModelLoad` cancel-on-config-change,
 * `CHECK_EPOCH` stale-drop, reset `incomplete` retry).
 *
 * Pure JVM (no Robolectric): no Android framework or native core is
 * touched — [CoreBridge.SettingsJobs] and the pure helpers never call
 * JNI.
 */
class SettingsJobsTest {

    @Before
    fun resetEpoch() {
        CoreBridge.SettingsJobs.resetForTests()
    }

    // -- Shared epoch (CHECK_EPOCH parity) ---------------------------

    @Test
    fun claim_isCurrent_latestOnly() {
        val first = CoreBridge.SettingsJobs.claim()
        assertTrue(CoreBridge.SettingsJobs.isCurrent(first))
        val second = CoreBridge.SettingsJobs.claim()
        assertTrue(CoreBridge.SettingsJobs.isCurrent(second))
        assertFalse("older check cannot replace a newer one", CoreBridge.SettingsJobs.isCurrent(first))
    }

    @Test
    fun invalidate_dropsInflight() {
        val gen = CoreBridge.SettingsJobs.claim()
        CoreBridge.SettingsJobs.invalidate()
        assertFalse(CoreBridge.SettingsJobs.isCurrent(gen))
    }

    @Test
    fun zeroGeneration_neverCurrent() {
        CoreBridge.SettingsJobs.claim()
        assertFalse(CoreBridge.SettingsJobs.isCurrent(0L))
    }

    // -- Per-screen generation guard (ptr_eq parity) -----------------

    @Test
    fun jobApplies_onlyEqualGenerations() {
        assertTrue(CoreBridge.SettingsJobs.jobApplies(3L, 3L))
        assertFalse(CoreBridge.SettingsJobs.jobApplies(3L, 2L))
        assertFalse(CoreBridge.SettingsJobs.jobApplies(2L, 3L))
    }

    @Test
    fun nextJobGen_incrementsAndSkipsZero() {
        assertEquals(1L, CoreBridge.SettingsJobs.nextJobGen(0L))
        assertEquals(4L, CoreBridge.SettingsJobs.nextJobGen(3L))
        assertEquals(1L, CoreBridge.SettingsJobs.nextJobGen(-1L))
        assertEquals(1L, CoreBridge.SettingsJobs.nextJobGen(Long.MAX_VALUE))
    }

    @Test
    fun staleWorker_dropsCommit_newerWins() {
        // Rapid successive saves: first worker's commit is stale.
        var current = 0L
        current = CoreBridge.SettingsJobs.nextJobGen(current)
        val first = current
        current = CoreBridge.SettingsJobs.nextJobGen(current)
        val second = current
        assertFalse(CoreBridge.SettingsJobs.jobApplies(current, first))
        assertTrue(CoreBridge.SettingsJobs.jobApplies(current, second))
    }

    // -- Committed-state retry (reset.incomplete parity) --------------

    @Test
    fun resetNeedsRetry_onlyIncompleteWarning() {
        assertTrue(CoreBridge.SettingsJobs.resetNeedsRetry("settings.reset.incomplete"))
        assertFalse(CoreBridge.SettingsJobs.resetNeedsRetry(null))
        assertFalse(CoreBridge.SettingsJobs.resetNeedsRetry(""))
        assertFalse(CoreBridge.SettingsJobs.resetNeedsRetry("settings.reset.failed"))
    }

    // -- Activity-level wrappers --------------------------------------

    @Test
    fun mainActivity_jobHelpers_delegate() {
        assertTrue(MainActivity.jobApplies(7L, 7L))
        assertFalse(MainActivity.jobApplies(7L, 6L))
        assertEquals(8L, MainActivity.nextJobGen(7L))
        assertTrue(MainActivity.resetNeedsRetry("settings.reset.incomplete"))
        assertFalse(MainActivity.resetNeedsRetry(null))
    }
}
