package com.mywhisper.mobile

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boot/update/retry actions all route through the same attemptStart path. */
class BootReceiverTest {

    @Test fun handlesBootCompleted() {
        assertTrue(BootReceiver.handlesAction(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test fun handlesUserUnlocked() {
        assertTrue(BootReceiver.handlesAction(Intent.ACTION_USER_UNLOCKED))
    }

    @Test fun handlesPackageReplaced() {
        assertTrue(BootReceiver.handlesAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test fun handlesInternalRetry() {
        assertTrue(BootReceiver.handlesAction(BootReceiver.ACTION_RETRY))
    }

    @Test fun ignoresUnrelatedActions() {
        assertFalse(BootReceiver.handlesAction(Intent.ACTION_LOCALE_CHANGED))
        assertFalse(BootReceiver.handlesAction(null))
    }

    @Test fun firstRetryFiresFastThenBacksOff() {
        // Attempt 0 = 15s fast path (boot parks here); later attempts
        // keep the 1m/2m/4m/8m backoff, capped at 15m.
        assertEquals(15_000L, BootReceiver.nextRetryDelayMs(0))
        assertEquals(60_000L, BootReceiver.nextRetryDelayMs(1))
        assertEquals(120_000L, BootReceiver.nextRetryDelayMs(2))
        assertEquals(240_000L, BootReceiver.nextRetryDelayMs(3))
        assertEquals(480_000L, BootReceiver.nextRetryDelayMs(4))
    }
}
