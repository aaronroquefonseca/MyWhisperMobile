package com.mywhisper.mobile

import org.junit.Assert.*
import org.junit.Test

class BubbleAppearanceTest {
    private fun shape(behavior: BubbleAppearance.IdleBehavior = BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY,
                      elapsed: Boolean = false, search: Boolean = false, shrinkSearch: Boolean = false,
                      grip: Boolean = false, extraSmall: Boolean = false, busy: Boolean = false) =
        BubbleAppearance.shape(behavior, elapsed, search, shrinkSearch, grip, extraSmall, busy)

    @Test fun defaultsAndCorruptSettings() {
        assertEquals(5000, BubbleAppearance.idleDelayMs(0))
        assertEquals(5000, BubbleAppearance.idleDelayMs(1234))
        // Old sub-2 s choices fall back to the new default.
        assertEquals(5000, BubbleAppearance.idleDelayMs(1000))
        assertEquals(5000, BubbleAppearance.idleDelayMs(1500))
        assertEquals(2000, BubbleAppearance.idleDelayOptionsMs.first())
        assertEquals(10000, BubbleAppearance.idleDelayOptionsMs.last())
        BubbleAppearance.idleDelayOptionsMs.forEach { assertEquals(it, BubbleAppearance.idleDelayMs(it)) }
        assertEquals(50, BubbleAppearance.size(2))
        assertEquals(50, BubbleAppearance.size(-1))
        assertEquals(50, BubbleAppearance.size(99))
        assertEquals(listOf(38, 44, 50, 58, 66), BubbleAppearance.sizes)
        assertEquals(BubbleAppearance.IdleBehavior.FULL, BubbleAppearance.idleBehavior(null))
        assertEquals(BubbleAppearance.IdleBehavior.FULL, BubbleAppearance.idleBehavior("invalid"))
        BubbleAppearance.IdleBehavior.entries.forEach { assertEquals(it, BubbleAppearance.idleBehavior(it.key)) }
    }
    @Test fun delayedReductionRequiresTimeoutOrSearchOverride() {
        assertEquals(BubbleAppearance.Shape.FULL, shape())
        assertEquals(BubbleAppearance.Shape.DOT, shape(elapsed = true))
        assertEquals(BubbleAppearance.Shape.FULL, shape(search = true))
        assertEquals(BubbleAppearance.Shape.DOT, shape(search = true, shrinkSearch = true))
        assertEquals(BubbleAppearance.Shape.SMALL, shape(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, elapsed = true))
        assertEquals(BubbleAppearance.Shape.SMALL, shape(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, search = true, shrinkSearch = true))
        assertEquals(BubbleAppearance.Shape.FULL, shape(BubbleAppearance.IdleBehavior.FULL, elapsed = true, search = true, shrinkSearch = true))
    }
    @Test fun alwaysDotIsDistinctFromDelayedShrinking() {
        val always = BubbleAppearance.IdleBehavior.ALWAYS_DOT
        assertEquals(BubbleAppearance.Shape.DOT, shape(always))
        assertEquals(BubbleAppearance.Shape.DOT, shape(always, elapsed = true))
        assertEquals(BubbleAppearance.Shape.FULL, shape(always, busy = true))
        assertFalse(always.usesDelay)
        assertTrue(always.usesDot)
        assertTrue(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY.usesDelay)
        assertFalse(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY.usesDot)
    }
    @Test fun gripPausesBothDotModesWithoutSilentlyChoosingSmaller() {
        for (mode in listOf(BubbleAppearance.IdleBehavior.ALWAYS_DOT, BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY)) {
            assertEquals(BubbleAppearance.Shape.FULL, shape(mode, elapsed = true, grip = true))
            assertEquals(BubbleAppearance.Shape.DOT, shape(mode, elapsed = true, grip = false))
        }
        assertEquals(BubbleAppearance.Shape.SMALL, shape(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, elapsed = true, grip = true))
    }
    @Test fun gripAndExtraSmallPauseOnlyTheirConflictingChoices() {
        for (mode in BubbleAppearance.IdleBehavior.entries) {
            assertEquals(if (mode.usesDot) BubbleAppearance.IdleBehavior.FULL else mode, BubbleAppearance.effective(mode, true, false))
            val small = mode == BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY
            assertEquals(if (small) BubbleAppearance.IdleBehavior.FULL else mode, BubbleAppearance.effective(mode, false, true))
            assertEquals(mode, BubbleAppearance.effective(mode, false, false))
        }
    }
    @Test fun extraSmallRestsAsSmallCircleAndStillAllowsDots() {
        val small = BubbleAppearance.Shape.SMALL
        assertEquals(small, shape(BubbleAppearance.IdleBehavior.FULL, extraSmall = true))
        // Shrink smaller is paused: never shrinks below the resting size.
        assertEquals(small, shape(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, elapsed = true, extraSmall = true))
        assertEquals(small, shape(BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY, extraSmall = true))
        assertEquals(BubbleAppearance.Shape.DOT, shape(BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY, elapsed = true, extraSmall = true))
        assertEquals(BubbleAppearance.Shape.DOT, shape(BubbleAppearance.IdleBehavior.ALWAYS_DOT, extraSmall = true))
        assertEquals(small, shape(BubbleAppearance.IdleBehavior.ALWAYS_DOT, grip = true, extraSmall = true))
        assertEquals(BubbleAppearance.Shape.FULL, shape(BubbleAppearance.IdleBehavior.FULL, extraSmall = true, busy = true))
        assertEquals(38, BubbleAppearance.buttonSize(4, extraSmall = true))
        assertEquals(66, BubbleAppearance.buttonSize(4, extraSmall = false))
        assertEquals(0f, BubbleAppearance.roundness(BubbleAppearance.Shape.FULL))
        assertEquals(1f, BubbleAppearance.roundness(small))
        assertEquals(1f, BubbleAppearance.roundness(BubbleAppearance.Shape.DOT))
    }
    @Test fun adaptiveCornersStayFixedWhileUniformScales() {
        // Adaptive is the default: unknown or missing values never pick uniform.
        assertFalse(BubbleAppearance.uniformShape(null))
        assertFalse(BubbleAppearance.uniformShape("adaptive"))
        assertTrue(BubbleAppearance.uniformShape("uniform"))
        assertEquals(16f, BubbleAppearance.cornerRadius(50f, 1f, uniform = false), 0.001f)
        assertEquals(16f, BubbleAppearance.cornerRadius(66f, 1f, uniform = false), 0.001f)
        assertEquals(16f, BubbleAppearance.cornerRadius(32f, 1f, uniform = false), 0.001f)
        assertEquals(12f, BubbleAppearance.cornerRadius(24f, 1f, uniform = false), 0.001f)
        assertEquals(16f, BubbleAppearance.cornerRadius(50f, 1f, uniform = true), 0.001f)
        assertEquals(66 * 0.32f, BubbleAppearance.cornerRadius(66f, 1f, uniform = true), 0.001f)
        assertEquals(48f, BubbleAppearance.cornerRadius(150f, 3f, uniform = false), 0.001f)
    }
    @Test fun activeStatesAlwaysExpand() {
        BubbleAppearance.IdleBehavior.entries.forEach {
            assertEquals(BubbleAppearance.Shape.FULL, shape(it, elapsed = true, search = true, shrinkSearch = true, busy = true))
        }
    }
    @Test fun searchDetectionUsesEditorMetadata() {
        assertTrue(BubbleAppearance.isSearch(0xb1, null, null))
        assertTrue(BubbleAppearance.isSearch(1, "com.example:id/search_input", null))
        assertTrue(BubbleAppearance.isSearch(1, null, "Buscar…"))
        assertFalse(BubbleAppearance.isSearch(1, "com.example:id/message", "Message"))
        assertFalse(BubbleAppearance.isSearch(1, null, null))
    }
}
