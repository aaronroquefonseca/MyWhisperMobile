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
