package com.mywhisper.mobile

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strip-pill symmetry (owner phone photo: more margin right than left).
 *
 * The pill itself is symmetric (equal padding, fixed bars); the row
 * gave it implicit placement (no child gravity, no pinned side
 * margins). These pin the contract: explicit center gravity +
 * equal start/end margins, LTR/RTL-safe. Pure JVM, no views.
 */
class MyWhisperIMEStripSymmetryTest {

    @Test
    fun pill_gravity_isCenter_directionAgnostic() {
        assertEquals(Gravity.CENTER_HORIZONTAL, MyWhisperIME.pillLayoutGravity())
        // Direction-agnostic: horizontal mask is CENTER, no LEFT/RIGHT
        // bias (START/END share low bits with LEFT/RIGHT, so mask on
        // the axis instead of bit-testing them).
        val g = MyWhisperIME.pillLayoutGravity()
        assertEquals(
            Gravity.CENTER_HORIZONTAL,
            g and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
    }

    @Test
    fun pill_sideMargins_zeroBias_symmetric() {
        assertEquals(0, MyWhisperIME.PILL_SIDE_MARGIN_DP)
        assertTrue(
            MyWhisperIME.sideMarginsSymmetric(
                MyWhisperIME.PILL_SIDE_MARGIN_DP,
                MyWhisperIME.PILL_SIDE_MARGIN_DP,
            ),
        )
        assertFalse(MyWhisperIME.sideMarginsSymmetric(0, 8))
        assertFalse(MyWhisperIME.sideMarginsSymmetric(8, 0))
    }

    @Test
    fun stripShell_sideMargins_equal() {
        // Shell insets stay symmetric so the centered pill reads
        // centered on screen, not just in its parent.
        assertTrue(MyWhisperIME.STRIP_SIDE_MARGIN_DP > 0)
        assertTrue(
            MyWhisperIME.sideMarginsSymmetric(
                MyWhisperIME.STRIP_SIDE_MARGIN_DP,
                MyWhisperIME.STRIP_SIDE_MARGIN_DP,
            ),
        )
    }

    @Test
    fun pill_bottomGap_pinned() {
        assertEquals(10, MyWhisperIME.PILL_BOTTOM_MARGIN_DP)
    }
}
