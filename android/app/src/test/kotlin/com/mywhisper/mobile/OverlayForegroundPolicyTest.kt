package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayForegroundPolicyTest {
    @Test fun idleReadinessNeverRequestsMicrophone() {
        assertEquals(0, OverlayForegroundPolicy.types(29, false))
        assertEquals(0, OverlayForegroundPolicy.types(33, false))
        assertEquals(0x40000000, OverlayForegroundPolicy.types(34, false))
        assertEquals(0x40000000, OverlayForegroundPolicy.types(36, false))
    }

    @Test fun userCaptureAddsMicrophoneOnlyWhereSupported() {
        assertEquals(0, OverlayForegroundPolicy.types(29, true))
        assertEquals(0x80, OverlayForegroundPolicy.types(30, true))
        assertEquals(0x80, OverlayForegroundPolicy.types(33, true))
        assertEquals(0x40000080, OverlayForegroundPolicy.types(34, true))
        assertEquals(0x40000080, OverlayForegroundPolicy.types(36, true))
    }
}
