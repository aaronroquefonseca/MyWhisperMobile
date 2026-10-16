package com.mywhisper.mobile

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test-button layout: ONE shared [ProviderTestButtonStyle] (min
 * height + content padding identical to the sibling Add buttons,
 * which use the M3 [ButtonDefaults] implicitly). Text-only buttons
 * get no M3 minimum of their own, so the shared floor is what keeps
 * every provider Test pill (chain rows, dialogs, self-test) at full
 * Add-button height instead of rendering squished.
 */
class ProviderTestButtonStyleTest {

    @Test
    fun minHeight_matchesAddButtonDefaults() {
        assertEquals(ButtonDefaults.MinHeight, ProviderTestButtonStyle.MinHeight)
    }

    @Test
    fun minHeight_isFullButtonHeight() {
        // M3 1.3.x button floor (verified against the 1.3.2 AAR):
        // icon+text Add buttons measure 24dp icon + 2x8dp padding.
        assertEquals(40.dp, ProviderTestButtonStyle.MinHeight)
    }

    @Test
    fun contentPadding_matchesAddButtonDefaults() {
        assertEquals(ButtonDefaults.ContentPadding, ProviderTestButtonStyle.ContentPadding)
    }

    @Test
    fun contentPadding_isRoomyNotCramped() {
        // M3 1.3.x button padding (verified against the 1.3.2 AAR):
        // 24dp sides, 8dp top/bottom — same air as the Add buttons.
        val padding = ProviderTestButtonStyle.ContentPadding
        assertEquals(24.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(24.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(8.dp, padding.calculateTopPadding())
        assertEquals(8.dp, padding.calculateBottomPadding())
    }
}
