package com.mywhisper.mobile

import org.junit.Assert.*
import org.junit.Test

class BubbleAppearanceTest {
    private fun shape(behavior: BubbleAppearance.IdleBehavior = BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY,
                      elapsed: Boolean = false, search: Boolean = false, shrinkSearch: Boolean = false,
                      grip: Boolean = false, busy: Boolean = false) =
        BubbleAppearance.shape(behavior, elapsed, search, shrinkSearch, grip, busy)

    @Test fun defaultsAndCorruptSettings() {
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
    @Test fun enablingGripResetsDotSelectionToDefault() {
        for (mode in BubbleAppearance.IdleBehavior.entries) {
            val enabled = BubbleAppearance.withGrip(mode, true)
            assertEquals(if (mode.usesDot) BubbleAppearance.IdleBehavior.FULL else mode, enabled)
            assertEquals(enabled, BubbleAppearance.withGrip(enabled, false))
            assertEquals(mode, BubbleAppearance.withGrip(mode, false))
        }
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
