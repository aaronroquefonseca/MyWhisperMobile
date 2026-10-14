package com.mywhisper.mobile

import org.junit.Assert.assertFalse
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
}
