package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mic-button UX rules (owner verdict): explicit cancel affordance,
 * first-hold slide discoverability, state-driven mic visuals.
 *
 * Pure JVM: exercises MyWhisperIME companion helpers only, so no
 * Robolectric / framework needed. Dictation.State stays the single
 * source of truth — visuals derive, never drive.
 */
class MyWhisperIMEMicUxTest {

    // -- Explicit cancel button ------------------------------------

    @Test
    fun cancel_hiddenWhenIdle() {
        assertFalse(MyWhisperIME.cancelVisible(Dictation.State.IDLE))
    }

    @Test
    fun cancel_visibleWhileRecordingOrWorking() {
        assertTrue(MyWhisperIME.cancelVisible(Dictation.State.RECORDING))
        assertTrue(MyWhisperIME.cancelVisible(Dictation.State.WORKING))
    }

    @Test
    fun cancelSlot_reservedWhileIdle_neverGrowsBar() {
        // Reserved slot (INVISIBLE, not GONE): the bar height is
        // identical idle vs active — appearing never grows layout.
        assertEquals(
            android.view.View.VISIBLE,
            MyWhisperIME.cancelVisibility(Dictation.State.RECORDING),
        )
        assertEquals(
            android.view.View.VISIBLE,
            MyWhisperIME.cancelVisibility(Dictation.State.WORKING),
        )
        assertEquals(
            android.view.View.INVISIBLE,
            MyWhisperIME.cancelVisibility(Dictation.State.IDLE),
        )
    }

    @Test
    fun pill_goneWhileIdle_visibleWhileActive() {
        // No dead transparent strip idle; pill appears for RECORDING
        // (live levels) and persists through WORKING (frozen).
        assertEquals(
            android.view.View.GONE,
            MyWhisperIME.pillVisibility(Dictation.State.IDLE, enabled = true),
        )
        assertEquals(
            android.view.View.VISIBLE,
            MyWhisperIME.pillVisibility(Dictation.State.RECORDING, enabled = true),
        )
        assertEquals(
            android.view.View.VISIBLE,
            MyWhisperIME.pillVisibility(Dictation.State.WORKING, enabled = true),
        )
        assertEquals(
            android.view.View.GONE,
            MyWhisperIME.pillVisibility(Dictation.State.RECORDING, enabled = false),
        )
    }

    @Test
    fun commitGate_blankNeverCommits() {
        assertFalse(MyWhisperIME.shouldCommitResult(""))
        assertFalse(MyWhisperIME.shouldCommitResult("   "))
        assertTrue(MyWhisperIME.shouldCommitResult("hello world"))
    }

    @Test
    fun routineDrop_silenceIsEmptyState_notError() {
        assertTrue(MyWhisperIME.isRoutineDrop("silence"))
        assertTrue(MyWhisperIME.isRoutineDrop("audio too short, ignored"))
        assertFalse(MyWhisperIME.isRoutineDrop(""))
        assertFalse(
            MyWhisperIME.isRoutineDrop("no server STT configured: add a Self-hosted entry"),
        )
    }

    @Test
    fun fixedRow_fitsScaledMic_glyphFillsButton() {
        // Fixed row fits the mic at max press scale: never grows,
        // never clips top/bottom.
        assertTrue(
            MyWhisperIME.MIC_DP * MyWhisperIME.MIC_MAX_SCALE <= MyWhisperIME.ROW_HEIGHT_DP,
        )
        // Glyph fills the button (was 84 - 2*20 = 44dp).
        val glyph = MyWhisperIME.MIC_DP - 2 * MyWhisperIME.MIC_PAD_DP
        assertTrue("glyph ${glyph}dp fills more of the button", glyph >= 56)
        assertTrue(MyWhisperIME.STATUS_DP > 0)
    }

    // -- First-hold slide hint -------------------------------------

    @Test
    fun slideHint_firstUntoggledHold_shows() {
        assertTrue(
            MyWhisperIME.shouldShowSlideHint(
                Dictation.State.RECORDING,
                toggled = false,
                alreadyShown = false,
            ),
        )
    }

    @Test
    fun slideHint_skipsToggleRepeatAndIdle() {
        // Toggled (toggle-mode) sessions never nudge.
        assertFalse(
            MyWhisperIME.shouldShowSlideHint(
                Dictation.State.RECORDING,
                toggled = true,
                alreadyShown = false,
            ),
        )
        // Once per process.
        assertFalse(
            MyWhisperIME.shouldShowSlideHint(
                Dictation.State.RECORDING,
                toggled = false,
                alreadyShown = true,
            ),
        )
        // Only while recording.
        assertFalse(
            MyWhisperIME.shouldShowSlideHint(
                Dictation.State.IDLE,
                toggled = false,
                alreadyShown = false,
            ),
        )
        assertFalse(
            MyWhisperIME.shouldShowSlideHint(
                Dictation.State.WORKING,
                toggled = false,
                alreadyShown = false,
            ),
        )
    }

    // -- State-driven mic visuals ----------------------------------

    @Test
    fun micVisuals_distinctPerState() {
        val idle = MyWhisperIME.micVisualFor(Dictation.State.IDLE)
        val rec = MyWhisperIME.micVisualFor(Dictation.State.RECORDING)
        val work = MyWhisperIME.micVisualFor(Dictation.State.WORKING)
        assertNotEquals(idle.color, rec.color)
        assertNotEquals(idle.color, work.color)
        assertNotEquals(rec.color, work.color)
        assertNotEquals(idle.scale, rec.scale)
        assertNotEquals(idle.scale, work.scale)
    }

    @Test
    fun micAnim_withinOwnerWindow() {
        assertTrue(MyWhisperIME.MIC_ANIM_MS in 150L..250L)
    }

    @Test
    fun micTalkBack_distinctPerState() {
        val idle = MyWhisperIME.micDescResFor(Dictation.State.IDLE)
        val rec = MyWhisperIME.micDescResFor(Dictation.State.RECORDING)
        val work = MyWhisperIME.micDescResFor(Dictation.State.WORKING)
        assertNotEquals(idle, rec)
        assertNotEquals(idle, work)
        assertNotEquals(rec, work)
        assertEquals(R.string.ime_mic, idle)
        assertEquals(R.string.ime_listening, rec)
        assertEquals(R.string.ime_working, work)
    }
}
