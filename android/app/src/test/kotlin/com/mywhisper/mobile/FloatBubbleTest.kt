package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure JVM rules for the floating-bubble redesign (see FloatState):
 * auto-show visibility, edge snap, safe-bounds clamp, grip-only move
 * arming (mic never moves/cancels), stop affordance + calm processing
 * treatment, stop-tap matrix, first-run hint, overlay-denial latch.
 */

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

    @Test
    fun snap_degenerateRangeCollapsesOntoMin() {
        // Fold mid-transition (maxX < minX): never persists off-screen.
        assertEquals(0, FloatState.snapToEdge(0, 0, -100))
        assertEquals(40, FloatState.snapToEdge(500, 40, 40))
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

    // -- Move arming (grip is the ONLY affordance) -------------------

    @Test
    fun arm_handleDragArms_tapNeverArms() {
        // Grip past slop: move.
        assertTrue(FloatState.shouldArmMove(11f, 10f, onHandle = true))
        // Grip inside slop: no move yet.
        assertFalse(FloatState.shouldArmMove(5f, 10f, onHandle = true))
        // Mic tap (inside slop): pure Dictation input.
        assertFalse(FloatState.shouldArmMove(5f, 10f, onHandle = false))
        // Mic wiggle: still not a move — recording survives.
        assertFalse(FloatState.shouldArmMove(50f, 10f, onHandle = false))
    }

    @Test
    fun arm_micNeverArms_evenLongPressPlusDrag() {
        // Owner UX: mic long-press + drag must never move or cancel —
        // the grip is the only move affordance.
        assertFalse(FloatState.shouldArmMove(11f, 10f, onHandle = false))
        assertFalse(FloatState.shouldArmMove(0f, 10f, onHandle = false))
        assertFalse(FloatState.shouldArmMove(500f, 10f, onHandle = false))
    }

    // -- Stop affordance + processing treatment ----------------------

    @Test
    fun stop_visibleWhileActive_hiddenIdle() {
        assertTrue(FloatState.isStopVisible(Dictation.State.RECORDING))
        assertTrue(FloatState.isStopVisible(Dictation.State.WORKING))
        assertFalse(FloatState.isStopVisible(Dictation.State.IDLE))
    }

    @Test
    fun recordingVsProcessing_readDifferently() {
        assertTrue(FloatState.isRecording(Dictation.State.RECORDING))
        assertFalse(FloatState.isRecording(Dictation.State.WORKING))
        assertFalse(FloatState.isRecording(Dictation.State.IDLE))
        assertTrue(FloatState.isProcessing(Dictation.State.WORKING))
        assertFalse(FloatState.isProcessing(Dictation.State.RECORDING))
        assertFalse(FloatState.isProcessing(Dictation.State.IDLE))
    }

    @Test
    fun stopAction_matrix() {
        assertEquals(FloatState.StopAction.FINISH, FloatState.stopAction(Dictation.State.RECORDING))
        assertEquals(FloatState.StopAction.CANCEL, FloatState.stopAction(Dictation.State.WORKING))
        assertEquals(FloatState.StopAction.NONE, FloatState.stopAction(Dictation.State.IDLE))
    }

    @Test
    fun moveHint_onceOnly() {
        assertTrue(FloatState.shouldShowMoveHint(hasSeenHint = false))
        assertFalse(FloatState.shouldShowMoveHint(hasSeenHint = true))
    }

    // -- Stop-tap commit/discard per mode ----------------------------

    /**
     * Mirrors OverlayService.onStopTap without Android: FINISH =
     * pressDown+pressUp, repeated once while still RECORDING (a Tap
     * first-hold only toggles); CANCEL = cancel. Every mic mode must
     * leave RECORDING and commit exactly once.
     */
    private fun stopTap(d: Dictation) {
        when (FloatState.stopAction(d.current)) {
            FloatState.StopAction.FINISH -> {
                d.pressDown()
                d.pressUp()
                if (d.current == Dictation.State.RECORDING) {
                    d.pressDown()
                    d.pressUp()
                }
            }
            FloatState.StopAction.CANCEL -> d.cancel()
            FloatState.StopAction.NONE -> Unit
        }
    }

    private fun dictationForStopTap(mode: String): Triple<Dictation, MutableList<String>, java.util.concurrent.ExecutorService> {
        val ex = Executors.newSingleThreadExecutor()
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val d = Dictation(
            mode = { mode },
            startCapture = { _, _, _ -> object : Dictation.Capture { override fun stop() {} } },
            finishTranscription = { "hello world" to "test-engine" },
            cancelTranscription = {},
            log = {},
            workers = ex,
        )
        d.listener = object : Dictation.Listener {
            override fun onState(s: Dictation.State, toggled: Boolean) = Unit
            override fun onResult(text: String) {
                results.add(text)
            }
            override fun onError(msg: String) = Unit
        }
        return Triple(d, results, ex)
    }

    @Test
    fun stopTap_finishesRecording_everyMode() {
        for (mode in listOf("Push", "Tap", "Smart")) {
            val (d, results, ex) = dictationForStopTap(mode)
            try {
                d.pressDown()
                assertEquals(Dictation.State.RECORDING, d.current)
                if (mode == "Push") {
                    // Hold still down: explicit stop finishes mid-hold.
                    stopTap(d)
                } else {
                    d.pressUp() // Tap/Smart-quick toggles (still RECORDING)
                    assertEquals(Dictation.State.RECORDING, d.current)
                    stopTap(d) // explicit stop affordance
                }
                assertTrue(
                    "stop must leave RECORDING in $mode",
                    d.current != Dictation.State.RECORDING,
                )
                ex.submit {}.get(10, TimeUnit.SECONDS)
                assertEquals("stop must commit once in $mode", listOf("hello world"), results)
                assertEquals(Dictation.State.IDLE, d.current)
            } finally {
                ex.shutdownNow()
            }
        }
    }

    @Test
    fun stopTap_cancelsWorking_discards() {
        val (d, results, ex) = dictationForStopTap("Push")
        try {
            d.pressDown()
            d.pressUp() // WORKING (Push finishes on release)
            assertEquals(Dictation.State.WORKING, d.current)
            stopTap(d) // explicit stop while transcribing: discard
            ex.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue("WORKING stop must discard", results.isEmpty())
            assertEquals(Dictation.State.IDLE, d.current)
        } finally {
            ex.shutdownNow()
        }
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
