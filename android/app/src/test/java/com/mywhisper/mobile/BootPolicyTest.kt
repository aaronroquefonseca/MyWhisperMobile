package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boot redelivery must never start an FGS that will be denied. */
class BootPolicyTest {

    @Test fun syncStartsWhenEnabledAndBootFlag() {
        assertTrue(BootPolicy.shouldStartSync(syncEnabled = true, startOnBoot = true))
    }

    @Test fun syncSkippedWhenDisabled() {
        assertFalse(BootPolicy.shouldStartSync(syncEnabled = false, startOnBoot = true))
    }

    @Test fun syncSkippedWhenBootFlagOff() {
        assertFalse(BootPolicy.shouldStartSync(syncEnabled = true, startOnBoot = false))
    }

    @Test fun floatStartsWhenEnabledWithOverlay() {
        assertTrue(BootPolicy.shouldStartFloat(floatEnabled = true, canDrawOverlays = true))
    }

    @Test fun floatSkippedWithoutOverlayPermission() {
        // No overlay perm -> startForeground path would deny -> skip at boot.
        assertFalse(BootPolicy.shouldStartFloat(floatEnabled = true, canDrawOverlays = false))
    }

    @Test fun floatSkippedWhenDisabled() {
        assertFalse(BootPolicy.shouldStartFloat(floatEnabled = false, canDrawOverlays = true))
    }

    @Test fun fgsDeniedDetectedByName() {
        val fake = ForegroundServiceStartNotAllowedException("bg FGS denied")
        assertTrue(BootPolicy.isFgsDenied(fake))
        assertTrue(BootPolicy.isFgsDenied(RuntimeException("wrap", fake)))
    }

    @Test fun fgsDeniedNotDetectedForOrdinaryErrors() {
        assertFalse(BootPolicy.isFgsDenied(RuntimeException("nope")))
        assertFalse(BootPolicy.isFgsDenied(RuntimeException("wrap", IllegalStateException("x"))))
    }

    @Test fun retryOnFgsDenyWhileAttemptsRemain() {
        assertTrue(
            BootPolicy.shouldRetryBootStart(
                ForegroundServiceStartNotAllowedException("denied"),
                0, BootReceiver.MAX_RETRIES,
            ),
        )
    }

    @Test fun noRetryOnFgsDenyAfterExhaustion() {
        assertFalse(
            BootPolicy.shouldRetryBootStart(
                ForegroundServiceStartNotAllowedException("denied"),
                BootReceiver.MAX_RETRIES, BootReceiver.MAX_RETRIES,
            ),
        )
    }

    @Test fun noRetryOnPermanentError() {
        assertFalse(
            BootPolicy.shouldRetryBootStart(
                RuntimeException("permanent config error"), 0, BootReceiver.MAX_RETRIES,
            ),
        )
    }

    @Test fun bootRetryChainAdvancesWhileAttemptsRemain() {
        assertEquals(1, BootPolicy.nextBootRetryAttempt(0, BootReceiver.MAX_RETRIES))
        assertEquals(
            BootReceiver.MAX_RETRIES,
            BootPolicy.nextBootRetryAttempt(
                BootReceiver.MAX_RETRIES - 1, BootReceiver.MAX_RETRIES,
            ),
        )
    }

    @Test fun bootRetryChainEndsAtLimit() {
        assertNull(
            BootPolicy.nextBootRetryAttempt(
                BootReceiver.MAX_RETRIES, BootReceiver.MAX_RETRIES,
            ),
        )
    }

    @Test fun interactiveStartNeverRetries() {
        assertNull(BootPolicy.nextBootRetryAttempt(-1, BootReceiver.MAX_RETRIES))
    }
}

/**
 * API-31 platform exception stand-in: the production check is
 * name-based ([BootPolicy.isFgsDenied]) precisely so minSdk 29 never
 * references the real class — this stub's class name trips it.
 */
class ForegroundServiceStartNotAllowedException(msg: String) : IllegalStateException(msg)
