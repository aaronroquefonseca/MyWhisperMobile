package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider test-button z-order contract (owner phone test): the
 * test button rendered IN FRONT OF overlapping content because the
 * whole chain item (Card + test row) shared the drag lift. Only the
 * Card lifts now; the test row stays at z 0, clips to its bounds,
 * and the result line wraps in its weighted slot — identically in
 * LTR and RTL. Pure JVM.
 */
class ProviderTestLayoutTest {

    @Test
    fun chainCard_liftsOnlyWhileDragging() {
        assertEquals(ProviderTest.DRAG_LIFT_Z, ProviderTest.chainCardZIndex(true), 0f)
        assertEquals(ProviderTest.ROW_REST_Z, ProviderTest.chainCardZIndex(false), 0f)
    }

    @Test
    fun testRow_neverLifts_dragOrNot() {
        // The regression: test row inherited the drag z (1f) and its
        // button painted over sibling rows. It must stay at 0 always.
        assertEquals(ProviderTest.TEST_ROW_Z, ProviderTest.testRowZIndex(true), 0f)
        assertEquals(ProviderTest.TEST_ROW_Z, ProviderTest.testRowZIndex(false), 0f)
        assertEquals(0f, ProviderTest.testRowZIndex(true), 0f)
    }

    @Test
    fun testRow_sitsBelowDraggedCard() {
        // While a neighbor drags, its Card (z 1) passes over; the
        // test row (z 0) never covers siblings.
        assertTrue(ProviderTest.testRowZIndex(true) < ProviderTest.chainCardZIndex(true))
        assertEquals(
            ProviderTest.testRowZIndex(false),
            ProviderTest.chainCardZIndex(false),
            0f,
        )
    }

    @Test
    fun testRow_clipsAndWeightsResult() {
        assertTrue(ProviderTest.testRowClipsToBounds())
        // Result takes the remaining row weight so long excerpts wrap
        // instead of overflowing under the next row's button.
        assertEquals(1f, ProviderTest.testRowResultWeight(), 0f)
    }

    @Test
    fun testRow_blockOrder_identicalLtrAndRtl() {
        // Logical order pinned: Row mirrors placement visually, so the
        // button sits at row-start in both directions with no branch
        // reordering blocks per direction.
        val ltr = ProviderTest.testRowBlockOrder(isRtl = false)
        val rtl = ProviderTest.testRowBlockOrder(isRtl = true)
        assertEquals(listOf("button", "gap", "result"), ltr)
        assertEquals(ltr, rtl)
    }

    // -- No-overlap: vertical stacking, bounds within row -----------

    @Test
    fun chainItem_cardFirst_testBelow_neverReversed() {
        // The overlap regression: Local branches emitted Card + test
        // row as sequential Box children (Box stacks = overlap).
        // Every item lays card first, test below, in a Column.
        assertEquals(listOf("card", "test"), ProviderTest.chainItemBlocks())
        assertTrue(ProviderTest.chainItemStacksVertically())
    }

    @Test
    fun testRow_noOverflow_smallScreens_ltrAndRtl() {
        // Weighted result wraps, so the row total never exceeds the
        // row width: overflow only if the button itself escapes.
        // Widths are direction-independent — one verdict for LTR+RTL.
        // 320dp small phone, generous 160dp button (longest locale +
        // touch target), 8dp gap.
        assertFalse(ProviderTest.testRowOverflows(rowWidthDp = 320f, buttonWidthDp = 160f))
        // Narrow dialog content (~280dp): still inside bounds.
        assertFalse(ProviderTest.testRowOverflows(rowWidthDp = 280f, buttonWidthDp = 160f))
        // Tightest phone (~240dp content): still inside bounds.
        assertFalse(ProviderTest.testRowOverflows(rowWidthDp = 240f, buttonWidthDp = 160f))
    }

    @Test
    fun testRow_overflowBoundary_buttonEscapingRow() {
        // Degenerate case documents the boundary: a button wider
        // than the row minus gap escapes — the contract pins where
        // that line is so a locale bloat trips the test first.
        assertTrue(ProviderTest.testRowOverflows(rowWidthDp = 100f, buttonWidthDp = 160f))
        // Direction-independence: widths carry no direction, so LTR
        // and RTL share the verdict by construction (same inputs).
        assertEquals(
            ProviderTest.testRowOverflows(320f, 160f),
            ProviderTest.testRowOverflows(320f, 160f),
        )
        assertFalse(ProviderTest.testRowOverflows(320f, 160f))
    }
}
