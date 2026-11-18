package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM guards for the hardening leftovers (see HardeningGuards):
 * pill end-action identity, error announcements, visualizer clamping,
 * IME-state re-sync, boot-retry backoff and keystore-lock detection.
 */
class HardeningGuardsTest {

    // -- Pill end action -------------------------------------------

    @Test
    fun pillEndAction_sameInstance_applies() {
        val v = Any()
        assertTrue(HardeningGuards.pillEndActionApplies(v, v))
    }

    @Test
    fun pillEndAction_rebuiltView_skips() {
        assertFalse(HardeningGuards.pillEndActionApplies(Any(), Any()))
        assertFalse(HardeningGuards.pillEndActionApplies(null, Any()))
        assertFalse(HardeningGuards.pillEndActionApplies(Any(), null))
    }

    // -- Error announcements ----------------------------------------

    @Test
    fun announce_newNote_announcesOnce() {
        assertTrue(HardeningGuards.shouldAnnounceError("mic failed", ""))
        assertFalse(HardeningGuards.shouldAnnounceError("mic failed", "mic failed"))
        assertFalse(HardeningGuards.shouldAnnounceError("", ""))
    }

    // -- Visualizer clamping -----------------------------------------

    @Test
    fun barHalfHeight_zeroHeight_isZero() {
        assertEquals(0f, HardeningGuards.barHalfHeight(0f, 1f, 1f), 0f)
        // height=4px: cap = mid - 1 = 1px, rest height clamps into it.
        assertEquals(1f, HardeningGuards.barHalfHeight(4f, 0f, 1f), 0f)
    }

    @Test
    fun barHalfHeight_normalRange() {
        // density=1, height=80px: rest=2px, peak=18px capped by mid-1=39.
        assertEquals(2f, HardeningGuards.barHalfHeight(80f, 0f, 1f), 0f)
        assertEquals(18f, HardeningGuards.barHalfHeight(80f, 1f, 1f), 0f)
        assertEquals(10f, HardeningGuards.barHalfHeight(80f, 0.5f, 1f), 0f)
    }

    @Test
    fun barHalfHeight_clampsPeakAndCap() {
        // Peak overflow clamps to lane max.
        assertEquals(18f, HardeningGuards.barHalfHeight(80f, 9f, 1f), 0f)
        // Tiny view: cap wins over rest height, never negative.
        assertEquals(1f, HardeningGuards.barHalfHeight(4f, 0f, 1f), 0f)
        assertTrue(HardeningGuards.barHalfHeight(2f, 1f, 3f) >= 0f)
    }

    // -- Boot retry ----------------------------------------------------

    @Test
    fun bootRetry_backoffDoublesToCap() {
        assertEquals(60_000L, HardeningGuards.bootRetryDelayMs(1))
        assertEquals(120_000L, HardeningGuards.bootRetryDelayMs(2))
        assertEquals(240_000L, HardeningGuards.bootRetryDelayMs(3))
        assertEquals(480_000L, HardeningGuards.bootRetryDelayMs(4))
        assertEquals(900_000L, HardeningGuards.bootRetryDelayMs(99))
        assertEquals(60_000L, HardeningGuards.bootRetryDelayMs(0))
    }

    @Test
    fun bootRetry_limitGate() {
        assertTrue(HardeningGuards.bootAttemptWithinLimit(0, 4))
        assertTrue(HardeningGuards.bootAttemptWithinLimit(3, 4))
        assertFalse(HardeningGuards.bootAttemptWithinLimit(4, 4))
    }

    private class FakeKeyStoreException : Exception()
    private class FakeUserNotAuthenticated : Exception()

    @Test
    fun keystoreLocked_matchesChainByName() {
        assertTrue(HardeningGuards.isKeystoreLocked(FakeKeyStoreException()))
        assertTrue(
            HardeningGuards.isKeystoreLocked(
                RuntimeException("wrap", FakeUserNotAuthenticated()),
            ),
        )
        assertFalse(HardeningGuards.isKeystoreLocked(RuntimeException("plain")))
    }
}
