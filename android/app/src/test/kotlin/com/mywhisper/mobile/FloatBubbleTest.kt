package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM rules for the floating-bubble redesign (see FloatState):
 * auto-show visibility, edge snap, safe-bounds clamp, move arming
 * (tap never destroys a recording), overlay-denial latch.
 */
class FloatBubbleTest {

    // -- Auto-show rule --------------------------------------------

    @Test
    fun show_requiresEnabled_noIme_focusOrNoA11y() {
        // a11y off: shows without focus.
        assertTrue(FloatState.shouldShowBubble(true, false, false, false))
        // a11y on: needs focus.
        assertTrue(FloatState.shouldShowBubble(true, false, true, true))
        assertFalse(FloatState.shouldShowBubble(true, false, true, false))
    }

    @Test
    fun show_hiddenWhenDisabledOrImeUp() {
        assertFalse(FloatState.shouldShowBubble(false, false, false, true))
        // Our own keyboard up: bubble stands down.
        assertFalse(FloatState.shouldShowBubble(true, true, false, true))
        assertFalse(FloatState.shouldShowBubble(true, true, true, true))
    }

    // -- Edge snap -------------------------------------------------

    @Test
    fun snap_nearestEdge() {
        assertEquals(0, FloatState.snapToEdge(10, 0, 1000))
        assertEquals(1000, FloatState.snapToEdge(990, 0, 1000))
        assertEquals(0, FloatState.snapToEdge(0, 0, 1000))
        assertEquals(1000, FloatState.snapToEdge(1000, 0, 1000))
    }

    @Test
    fun snap_tieGoesRight_respectsInsets() {
        assertEquals(1000, FloatState.snapToEdge(500, 0, 1000))
        assertEquals(40, FloatState.snapToEdge(41, 40, 900))
        assertEquals(900, FloatState.snapToEdge(899, 40, 900))
    }

    // -- Clamp -----------------------------------------------------

    @Test
    fun clamp_insideUnchanged_outsideCoerced() {
        assertEquals(Pair(100, 200), FloatState.clampBubble(100, 200, 0, 0, 1000, 2000))
        assertEquals(Pair(0, 0), FloatState.clampBubble(-50, -50, 0, 0, 1000, 2000))
        assertEquals(Pair(1000, 2000), FloatState.clampBubble(5000, 9000, 0, 0, 1000, 2000))
    }

    @Test
    fun clamp_degenerateRangeCollapsesOntoMin() {
        // Fold mid-transition reporting max < min: never off-screen.
        assertEquals(Pair(0, 0), FloatState.clampBubble(500, 500, 0, 0, -100, -100))
    }

    // -- Move arming (tap never destroys) --------------------------

    @Test
    fun arm_handleDragArms_tapNeverArms() {
        // Grip past slop: move.
        assertTrue(FloatState.shouldArmMove(11f, 10f, longPressFired = false, onHandle = true))
        // Grip inside slop: no move yet.
        assertFalse(FloatState.shouldArmMove(5f, 10f, longPressFired = false, onHandle = true))
        // Mic tap (no long-press, inside slop): pure Dictation input.
        assertFalse(FloatState.shouldArmMove(5f, 10f, longPressFired = false, onHandle = false))
        // Mic wiggle WITHOUT long-press: still not a move — recording survives.
        assertFalse(FloatState.shouldArmMove(50f, 10f, longPressFired = false, onHandle = false))
    }

    @Test
    fun arm_micLongPressPlusDragArms() {
        assertTrue(FloatState.shouldArmMove(11f, 10f, longPressFired = true, onHandle = false))
        // Long-press alone (no movement): no move yet.
        assertFalse(FloatState.shouldArmMove(0f, 10f, longPressFired = true, onHandle = false))
    }

    // -- Overlay-denial latch --------------------------------------

    @Test
    fun overlayDenial_latchRoundTrip() {
        FloatState.clearOverlayDenied()
        assertFalse(FloatState.overlayDenied)
        assertTrue(FloatState.recordOverlayDenial())
        assertTrue(FloatState.overlayDenied)
        FloatState.clearOverlayDenied()
        assertFalse(FloatState.overlayDenied)
    }
}
