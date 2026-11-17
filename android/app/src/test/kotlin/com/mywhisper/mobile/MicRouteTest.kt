package com.mywhisper.mobile

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicRouteTest {

    private val builtIn = AudioDeviceInfo.TYPE_BUILTIN_MIC
    private val usb = AudioDeviceInfo.TYPE_USB_HEADSET
    private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val tuner = AudioDeviceInfo.TYPE_FM_TUNER

    private fun pick(priority: List<String>, vararg types: Int) =
        MicRoute.pick(priority, types.toList()) { MicRoute.kindOf(it) }

    @Test
    fun firstConnectedRankedKindWins() {
        val defaults = listOf(MicRoute.WIRED, MicRoute.BUILT_IN, MicRoute.BLUETOOTH)
        assertEquals(usb, pick(defaults, tuner, builtIn, sco, usb))
        assertEquals(builtIn, pick(defaults, tuner, builtIn, sco))
        assertEquals(sco, pick(listOf(MicRoute.BLUETOOTH, MicRoute.BUILT_IN), builtIn, sco))
    }

    @Test
    fun unrankedOrUnknownInputsAreNeverPicked() {
        assertNull(pick(listOf(MicRoute.BLUETOOTH), builtIn, tuner))
        assertNull(pick(listOf("Mystery"), builtIn))
    }
}
