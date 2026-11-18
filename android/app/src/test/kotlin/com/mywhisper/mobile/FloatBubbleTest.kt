package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
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
    @Test
    fun surfaceOpacity_preservesConfiguredTranslucency() {
        assertEquals(0.65f, FloatState.overlayWindowOpacity(65), 0f)
        assertEquals(1f, FloatState.overlayWindowOpacity(100), 0f)
        assertEquals(0.3f, FloatState.overlayWindowOpacity(0), 0f)
        assertEquals(1f, FloatState.overlayWindowOpacity(200), 0f)
    }

    @Test
    fun idlePosition_leavesRoomForHoldControlsAtBottomEdge() {
        for (grab in listOf(false, true)) {
            val fullHeight = FloatState.recordingWindowHeightDp(grab)
            val bottom = 1000 - fullHeight
            val clamped = FloatState.clampBubble(100, 999, 0, 0, 500, bottom)
            assertEquals(1000, clamped.second + fullHeight)
            assertTrue(clamped.second < 1000 - 58)
        }
    }

    @Test
    fun recordingStages_expandFromSymbolToWaveformThenCancel() {
        assertEquals(139, recordingCommonWidthDp())
        assertEquals(172, togglePillWidthDp())
        assertEquals(33, togglePillWidthDp() - recordingCommonWidthDp())
        assertTrue(recordingCommonWidthDp() > TOGGLE_CONTROL_DP)
    }

    @Test
    fun screenStaysOnThroughProcessing() {
        assertTrue(FloatState.keepsScreenOn(Dictation.State.RECORDING))
        assertTrue(FloatState.keepsScreenOn(Dictation.State.WORKING))
        assertFalse(FloatState.keepsScreenOn(Dictation.State.IDLE))
    }

    @Test
    fun queuedPasteKeepsProcessingVisibleAndAwakeUntilCommitCompletes() {
        val delivering = FloatState.displayState(Dictation.State.IDLE, 1)
        assertEquals(Dictation.State.WORKING, delivering)
        assertTrue(FloatState.keepsScreenOn(delivering))
        val delivered = FloatState.displayState(Dictation.State.IDLE, 0)
        assertEquals(Dictation.State.IDLE, delivered)
        assertFalse(FloatState.keepsScreenOn(delivered))
        assertEquals(Dictation.State.RECORDING, FloatState.displayState(Dictation.State.RECORDING, 1))
    }

    @Test
    fun toggleWaveform_balancesCancelGlyphAgainstAcceptBox_onBothEdges() {
        // Cancel path + stroke ends at roughly 33dp in its 52dp touch cell.
        val rightDockWave = toggleWaveformLeftDp(false)
        assertEquals(12, rightDockWave - 33)
        assertEquals(12, togglePillWidthDp() - 52 - rightDockWave - vizBarsContentWidthDp())
        val leftDockWave = toggleWaveformLeftDp(true)
        assertEquals(12, leftDockWave - 52)
        assertEquals(12, togglePillWidthDp() - 33 - leftDockWave - vizBarsContentWidthDp())
        assertEquals(172, togglePillWidthDp())
    }

    @Test
    fun smartHoldControls_shareTheReleaseCeiling() {
        assertEquals(150L, holdControlsDelayMs("Smart", false, 0, 150))
        assertEquals(1L, holdControlsDelayMs("Smart", false, 149, 150))
        assertEquals(0L, holdControlsDelayMs("Smart", false, 150, 150))
        assertEquals(0L, holdControlsDelayMs("Smart", false, 151, 150))
        assertEquals(350L, holdControlsDelayMs("Smart", false, 50, 400))
        assertEquals(null, holdControlsDelayMs("Smart", true, 50, 150))
        assertEquals(null, holdControlsDelayMs("Tap", false, 1000, 150))
        assertEquals(0L, holdControlsDelayMs("Push", false, 0, 150))
    }

    @Test
    fun disabledLock_upwardMotionNeverBecomesCancelTravel() {
        assertEquals(0f, FloatState.holdGestureDistance(-100f, false), 0f)
        assertEquals(0f, FloatState.holdGestureDistance(0f, false), 0f)
        assertEquals(60f, FloatState.holdGestureDistance(60f, false), 0f)
        assertEquals(60f, FloatState.holdGestureDistance(-60f, true), 0f)
    }

    @Test
    fun lockRecording_keepsCaptureRunningAfterRelease() {
        val (dictation, results, executor) = dictationForStopTap("Push")
        try {
            dictation.lockRecording() // Idle is a no-op.
            assertEquals(Dictation.State.IDLE, dictation.current)
            dictation.pressDown()
            dictation.lockRecording()
            dictation.lockRecording() // Idempotent.
            dictation.pressUp()
            assertEquals(Dictation.State.RECORDING, dictation.current)
            assertTrue(results.isEmpty())
            dictation.cancel()
            assertEquals(Dictation.State.IDLE, dictation.current)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun keyboardVisibility_doesNotWaitForStaleTextFocus() {
        assertFalse(FloatState.shouldShowBubble(true, true, true, true, false))
        assertTrue(FloatState.shouldShowBubble(true, true, false, true, true))
        assertFalse(FloatState.shouldShowBubble(true, false, true, true, true))
        assertTrue(FloatState.shouldShowBubble(true, true, true, false, false))
    }

    @Test
    fun magnets_haveSeparateCaptureAndReleaseBoundaries() {
        assertFalse(FloatState.magnetReleased(18f, false, 18f, 10f))
        assertTrue(FloatState.magnetReleased(19f, false, 18f, 10f))
        assertTrue(FloatState.magnetReleased(12f, true, 18f, 10f))
        assertFalse(FloatState.magnetReleased(10f, true, 18f, 10f))
        assertTrue(FloatState.magnetCaptured(20f, false, 20f, 34f))
        assertFalse(FloatState.magnetCaptured(21f, false, 20f, 34f))
        assertTrue(FloatState.magnetCaptured(30f, true, 20f, 34f))
        assertFalse(FloatState.magnetCaptured(35f, true, 20f, 34f))
        assertTrue(FloatState.magnetCaptured(60f, false, 64f, 96f))
        assertTrue(FloatState.magnetCaptured(90f, true, 64f, 96f))
        assertFalse(FloatState.magnetCaptured(97f, true, 64f, 96f))
    }

    @Test
    fun holdCancel_tracksDownwardTravel_andDisarmsOnReturn() {
        assertEquals(0f, FloatState.holdCancelTravel(-30f, 89f), 0f)
        assertEquals(40f, FloatState.holdCancelTravel(40f, 89f), 0f)
        assertEquals(89f, FloatState.holdCancelTravel(130f, 89f), 0f)
        assertFalse(FloatState.holdCancelArmed(71f, 72f))
        assertTrue(FloatState.holdCancelArmed(72f, 72f))
        assertFalse(FloatState.holdCancelArmed(20f, 72f))
    }


    // -- Auto-show rule --------------------------------------------

    @Test
    fun show_requiresEnabled_a11yAndFocus() {
        // a11y off: the bubble cannot land text anywhere — never show it.
        assertFalse(FloatState.shouldShowBubble(true, false, false))
        assertFalse(FloatState.shouldShowBubble(true, false, true))
        // a11y on: needs a focused editable field.
        assertTrue(FloatState.shouldShowBubble(true, true, true))
        assertFalse(FloatState.shouldShowBubble(true, true, false))
    }

    // -- Free drag + hold delay (grip off) --------------------------

    @Test
    fun freeMove_requiresPastTheDeadzone() {
        // Inside the margin: involuntary jitter, never a drag.
        assertFalse(FloatState.shouldArmFreeMove(0f, 10f))
        assertFalse(FloatState.shouldArmFreeMove(4f, 10f))
        assertFalse(FloatState.shouldArmFreeMove(10f, 10f))
        // Past it: the bubble follows the finger.
        assertTrue(FloatState.shouldArmFreeMove(10.1f, 10f))
        assertTrue(FloatState.shouldArmFreeMove(40f, 10f))
        // The grip keeps its own rule (handle only).
        assertFalse(FloatState.shouldArmMove(40f, 10f, onHandle = false))
        assertTrue(FloatState.shouldArmMove(40f, 10f, onHandle = true))
    }

    @Test
    fun pressDelay_presetsAndFallback() {
        assertEquals(listOf(50, 65, 80, 100, 125, 150), FloatState.PRESS_DELAY_OPTIONS_MS)
        assertEquals(80, FloatState.PRESS_DELAY_DEFAULT_MS)
        for (ms in FloatState.PRESS_DELAY_OPTIONS_MS) {
            assertEquals(ms, FloatState.pressDelayMs(ms))
        }
        // Unknown / corrupt stored values fall back to the default.
        assertEquals(80, FloatState.pressDelayMs(20))
        assertEquals(80, FloatState.pressDelayMs(35))
        assertEquals(80, FloatState.pressDelayMs(0))
        assertEquals(80, FloatState.pressDelayMs(999))
        assertEquals(80, FloatState.pressDelayMs(-5))
    }

    @Test
    fun show_hiddenWhenDisabled() {
        assertFalse(FloatState.shouldShowBubble(false, true, true))
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
        assertFalse(FloatState.isStopVisible(Dictation.State.WORKING))
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
        // Owner pill round-2: the X is pure cancel — RECORDING
        // discards (never finish/commit), WORKING discards, IDLE no-ops.
        assertEquals(FloatState.StopAction.CANCEL, FloatState.stopAction(Dictation.State.RECORDING))
        assertEquals(FloatState.StopAction.CANCEL, FloatState.stopAction(Dictation.State.WORKING))
        assertEquals(FloatState.StopAction.NONE, FloatState.stopAction(Dictation.State.IDLE))
    }

    @Test
    fun moveHint_onceOnly() {
        assertTrue(FloatState.shouldShowMoveHint(hasSeenHint = false))
        assertFalse(FloatState.shouldShowMoveHint(hasSeenHint = true))
    }

    // -- Move-hint one-shot coachmark -------------------------------

    @Test
    fun moveHint_showOnceEver_neverReplays() {
        // Round-trip: unseen -> show; the service persists the seen
        // flag BEFORE addView, so every later gate read stays shut —
        // even if showing, tapping, or teardown fails mid-flight.
        var seenHint = false
        assertTrue(FloatState.shouldShowMoveHint(seenHint))
        seenHint = true // persisted at show time
        assertFalse(FloatState.shouldShowMoveHint(seenHint))
        assertFalse(FloatState.shouldShowMoveHint(seenHint))
    }

    @Test
    fun moveHint_belowBubbleWhenFits_elseAbove() {
        // Fits below: bubble bottom + margin.
        assertEquals(476, moveHintY(400, 60, 48, 16, 1000))
        // No room below: above the bubble.
        assertEquals(836, moveHintY(900, 60, 48, 16, 1000))
        // No room either side: clamped to the top edge, never negative.
        assertEquals(0, moveHintY(10, 200, 300, 16, 400))
    }

    @Test
    fun moveHint_xCenteredOnBubble_clampedToScreen() {
        // Centered: bubble center minus half the pill width.
        assertEquals(69, moveHintX(100, 62, 0, 200))
        // Past the left edge: clamped to min.
        assertEquals(0, moveHintX(10, 62, 0, 200))
        // Past the right edge: clamped so the pill stays on-screen.
        assertEquals(138, moveHintX(190, 62, 0, 200))
        // Degenerate (pill wider than the range): collapse onto min.
        assertEquals(0, moveHintX(500, 62, 0, 40))
    }

    @Test
    fun moveHint_timeoutPinned() {
        assertEquals(6000L, HINT_TIMEOUT_MS)
        assertEquals(16, HINT_MARGIN_DP)
    }

    // -- Bubble chrome geometry (review round 2) ---------------------

    @Test
    fun spinner_drawsAboveMic() {
        // Z-order assumption: the ProgressBar default elevation is 0,
        // which renders UNDER the mic glyph (6dp). The service sets the
        // spinner to 8dp — this pins that ordering so a future edit
        // cannot silently bury the processing indicator again.
        assertEquals(6, BUBBLE_MIC_ELEVATION_DP)
        assertEquals(8, BUBBLE_SPINNER_ELEVATION_DP)
        assertTrue(
            "spinner must sit above the mic glyph",
            BUBBLE_SPINNER_ELEVATION_DP > BUBBLE_MIC_ELEVATION_DP,
        )
    }

    @Test
    fun stop_hitTargetMatchesGrip() {
        // Visual stays 32dp; the TouchDelegate expands to the 48dp
        // minimum (grip parity). Per-side pad = (48 - 32) / 2 = 8.
        assertEquals(32, STOP_VISUAL_DP)
        assertEquals(48, STOP_TARGET_DP)
        assertEquals(8, touchExpandPx(STOP_VISUAL_DP, STOP_TARGET_DP))
    }

    @Test
    fun stop_touchDelegateExpandsTo48() {
        // Mirrors the service's TouchDelegate math (hit.inset(-pad)):
        // a 32px visual rect with an 8px outward inset becomes 48x48.
        val pad = touchExpandPx(32, 48)
        val expanded = expandHitRect(HitRect(0, 0, 32, 32), -pad)
        assertEquals(HitRect(-8, -8, 40, 40), expanded)
        assertEquals(48, expanded.width)
        assertEquals(48, expanded.height)
    }

    // -- Stop-tap cancel/discard per mode ----------------------------

    /**
     * Mirrors OverlayService.onStopTap without Android: any active
     * state cancels (never finish/commit). Every mic mode must leave
     * RECORDING with no result committed.
     */
    private fun stopTap(d: Dictation) {
        if (FloatState.stopAction(d.current) != FloatState.StopAction.NONE) {
            d.cancel()
        }
    }

    private fun dictationForStopTap(
        mode: String,
        finishTranscription: () -> Pair<String, String> = { "hello world" to "test-engine" },
    ): Triple<Dictation, MutableList<String>, java.util.concurrent.ExecutorService> {
        val ex = Executors.newSingleThreadExecutor()
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val d = Dictation(
            mode = { mode },
            startCapture = { _, _, _ -> object : Dictation.Capture { override fun stop() {} } },
            finishTranscription = finishTranscription,
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
    fun stopTap_discardsRecording_everyMode() {
        for (mode in listOf("Push", "Tap", "Smart")) {
            val (d, results, ex) = dictationForStopTap(mode)
            try {
                d.pressDown()
                assertEquals(Dictation.State.RECORDING, d.current)
                if (mode == "Push") {
                    // Hold still down: explicit cancel discards mid-hold.
                    stopTap(d)
                } else {
                    d.pressUp() // Tap/Smart-quick toggles (still RECORDING)
                    assertEquals(Dictation.State.RECORDING, d.current)
                    stopTap(d) // explicit cancel affordance
                }
                assertEquals(
                    "cancel must return to IDLE in $mode",
                    Dictation.State.IDLE, d.current,
                )
                ex.submit {}.get(10, TimeUnit.SECONDS)
                assertTrue("cancel must never commit in $mode", results.isEmpty())
                assertEquals(Dictation.State.IDLE, d.current)
            } finally {
                ex.shutdownNow()
            }
        }
    }

    @Test
    fun stopTap_cancelsWorking_discards() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (d, results, ex) = dictationForStopTap("Push") {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "transcription was not released" }
            "hello world" to "test-engine"
        }
        try {
            d.pressDown()
            d.pressUp() // WORKING (Push finishes on release)
            // Hold transcription in flight: an immediate fake result can win the race
            // and return to IDLE before this test has a chance to cancel it.
            assertTrue("transcription must start", entered.await(10, TimeUnit.SECONDS))
            assertEquals(Dictation.State.WORKING, d.current)
            stopTap(d) // explicit stop while transcribing: discard
            release.countDown()
            ex.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue("WORKING stop must discard", results.isEmpty())
            assertEquals(Dictation.State.IDLE, d.current)
        } finally {
            release.countDown()
            ex.shutdownNow()
        }
    }

    // -- Branded mic chrome (pill round-2 identity) ------------------

    @Test
    fun micChrome_neverGenericDot() {
        for (s in Dictation.State.entries) {
            val c = FloatState.micChrome(s)
            assertTrue(
                "brand bg must differ from the legacy generic fill in $s",
                c.bgColor != FloatState.LEGACY_GENERIC_MIC_FILL,
            )
            assertTrue(
                "brand gradient must differ from the legacy generic fill in $s",
                c.bgEndColor != FloatState.LEGACY_GENERIC_MIC_FILL,
            )
        }
    }

    @Test
    fun micChrome_recordingVsProcessingVsIdle() {
        val rec = FloatState.micChrome(Dictation.State.RECORDING)
        assertEquals(1f, rec.alpha, 0f)
        val work = FloatState.micChrome(Dictation.State.WORKING)
        assertEquals(0.6f, work.alpha, 0f)
        assertTrue("processing dims vs recording", work.alpha < rec.alpha)
        val idle = FloatState.micChrome(Dictation.State.IDLE)
        assertEquals(1f, idle.alpha, 0f)
        assertTrue("recording reads red-brand", rec.bgColor != idle.bgColor)
        assertTrue("processing reads deep-brand", work.bgColor != idle.bgColor)
    }

    // -- Grip drag-down + drop-on-X cancel ---------------------------

    @Test
    fun gripDragDown_cancelsOnlyPastThresholdWhileActive() {
        assertTrue(FloatState.shouldCancelOnGripDragDown(48f, 48f, active = true))
        assertTrue(FloatState.shouldCancelOnGripDragDown(200f, 48f, active = true))
        assertFalse(FloatState.shouldCancelOnGripDragDown(47f, 48f, active = true))
        // Up / sideways drags are plain moves, never cancel.
        assertFalse(FloatState.shouldCancelOnGripDragDown(-60f, 48f, active = true))
        assertFalse(FloatState.shouldCancelOnGripDragDown(0f, 48f, active = true))
        // Idle grip drags never cancel (nothing to discard).
        assertFalse(FloatState.shouldCancelOnGripDragDown(200f, 48f, active = false))
    }

    @Test
    fun dropOnCancel_hitRect() {
        val stop = FloatState.HitRectF(100f, 200f, 148f, 248f)
        assertTrue(FloatState.isDropOnCancel(124f, 224f, stop))
        assertTrue(FloatState.isDropOnCancel(100f, 200f, stop))
        assertFalse(FloatState.isDropOnCancel(99f, 224f, stop))
        assertFalse(FloatState.isDropOnCancel(124f, 199f, stop))
        assertFalse(FloatState.isDropOnCancel(0f, 0f, stop))
    }

    @Test
    fun gripCancelTravel_pinned() {
        assertEquals(48, GRIP_CANCEL_DY_DP)
    }

    // -- Visualizer pill geometry ------------------------------------

    @Test
    fun vizPill_sitsAboveImeElseNavBarPlusMargin() {
        // Keyboard up: above the IME.
        assertEquals(516, FloatState.vizPillYOffset(imeBottomPx = 500, navBottomPx = 48, marginPx = 16))
        // Keyboard down: above the nav bar.
        assertEquals(64, FloatState.vizPillYOffset(imeBottomPx = 0, navBottomPx = 48, marginPx = 16))
        // Gesture nav (no bar, no IME): margin above the edge.
        assertEquals(16, FloatState.vizPillYOffset(imeBottomPx = 0, navBottomPx = 0, marginPx = 16))
    }

    @Test
    fun vizPill_sizePinned() {
        assertEquals(108, VIZ_W_DP)
        assertEquals(36, VIZ_H_DP)
        assertEquals(16, VIZ_MARGIN_DP)
    }

    // -- Visualizer pill symmetry (floating-bubble pill) --------------

    @Test
    fun vizStrip_contentWidthPinned() {
        // 11 bars x 3dp + 10 inner gaps x 3dp = 63dp, no trailing gap
        // (mirrors BarsView.onDraw: last bar ends at count*(w+gap)-gap).
        assertEquals(11, VIZ_BAR_COUNT)
        assertEquals(3, VIZ_BAR_W_DP)
        assertEquals(3, VIZ_BAR_GAP_DP)
        assertEquals(63, vizBarsContentWidthDp())
        assertEquals(24, VIZ_BARS_H_DP)
    }

    @Test
    fun vizStrip_centeredEqualMarginsBothEdges() {
        // (108 - 63) / 2 = 22.5dp per edge: left margin == right
        // margin, so the strip reads centered with no margin-right bias.
        val margin = vizBarsSideMarginDp()
        assertEquals(22.5f, margin, 0.001f)
        val content = vizBarsContentWidthDp()
        val left = (VIZ_W_DP - content) / 2f
        val right = VIZ_W_DP - content - left
        assertEquals(left, right, 0.001f)
        assertEquals(margin, left, 0.001f)
    }

    @Test
    fun vizWindow_dockEdgeIndependent() {
        // The viz pill is its own centered window: x = 0 whether the
        // bubble docks left or right — never an edge-side offset.
        assertEquals(0, vizWindowXForDock(dockedLeft = true))
        assertEquals(0, vizWindowXForDock(dockedLeft = false))
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

    // -- Death-path teardown matrix --------------------------------

    @Test
    fun teardown_alwaysFull_onlyRecomposeVaries() {
        // Every death path (STOP, overlay-denied START, addView denial,
        // FGS denial) runs the same full teardown; only the survivor
        // recompose depends on sync liveness.
        for (syncActive in listOf(false, true)) {
            val plan = floatTeardownPlan(syncActive)
            assertTrue(plan.detachListener)
            assertTrue(plan.removeBubble)
            assertTrue(plan.clearState)
            assertTrue(plan.resetUnifiedState)
            assertTrue(plan.removeSharedRow)
            assertEquals(syncActive, plan.recomposeSync)
        }
    }

    @Test
    fun teardown_noSyncAlive_removesRowWithoutRecompose() {
        val plan = floatTeardownPlan(false)
        assertTrue(plan.removeSharedRow)
        assertFalse(plan.recomposeSync)
    }

    @Test
    fun teardown_syncAlive_handsRowBack() {
        val plan = floatTeardownPlan(true)
        assertTrue(plan.removeSharedRow)
        assertTrue(plan.recomposeSync)
    }
}
