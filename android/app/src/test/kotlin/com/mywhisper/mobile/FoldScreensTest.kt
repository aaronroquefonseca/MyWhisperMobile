package com.mywhisper.mobile

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mywhisper.mobile.FoldScreens.Screen.COVER
import com.mywhisper.mobile.FoldScreens.Screen.INNER
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class FoldScreensTest {
    private lateinit var prefs: SharedPreferences
    private val shared = listOf("float_opacity", "float_shape", "float_lock_enabled", "float_snooze_minutes",
        "float_press_delay", "float_follow_keyboard", "float_keep_attached", "float_move_hint_seen")

    @Before fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mw_state", Context.MODE_PRIVATE)
        prefs.edit { clear() }
    }

    private fun key(base: String, screen: FoldScreens.Screen) = FoldScreens.key(base, screen, FoldScreens.split(prefs))

    @Test fun resolverSplitsOnlyPerScreenSettingsAndOnlyWhileOn() {
        assertEquals(listOf("float_grab", "float_size", "float_extra_small", "float_idle_style",
            "float_idle_delay", "float_shrink_search", "float_x", "float_y"), FoldScreens.PER_SCREEN)
        for (base in FoldScreens.PER_SCREEN + shared) for (screen in FoldScreens.Screen.entries) {
            assertEquals(base, FoldScreens.key(base, screen, split = false))
            val expected = if (screen == INNER && base in FoldScreens.PER_SCREEN) "${base}_inner" else base
            assertEquals(expected, FoldScreens.key(base, screen, split = true))
        }
    }

    @Test fun firstSplitSeedsInnerFromSharedSoNothingChanges() {
        prefs.edit {
            putBoolean("float_grab", true); putInt("float_size", 4); putString("float_idle_style", "dot")
            putInt("float_x", 900); putInt("float_y", 1200)
        }
        FoldScreens.setSplit(prefs, true)
        for (base in FoldScreens.PER_SCREEN) assertEquals(base, prefs.all[base], prefs.all[key(base, INNER)])
        assertEquals("float_size_inner", key("float_size", INNER))
        // Unset shared values stay unset on the inner screen: same defaults on both.
        assertFalse(prefs.contains("float_extra_small_inner"))
    }

    @Test fun offIgnoresInnerKeysAndOnRestoresThem() {
        prefs.edit { putInt("float_size", 1) }
        FoldScreens.setSplit(prefs, true)
        prefs.edit { putInt(key("float_size", INNER), 3); putInt(key("float_x", INNER), 40) }
        FoldScreens.setSplit(prefs, false)
        // Off: both screens read the cover (shared) value.
        assertEquals(1, prefs.getInt(key("float_size", INNER), -1))
        assertEquals(1, prefs.getInt(key("float_size", COVER), -1))
        // Changed shared values do not leak into the kept inner setup on the way back.
        prefs.edit { putInt("float_size", 0) }
        FoldScreens.setSplit(prefs, true)
        assertEquals(3, prefs.getInt(key("float_size", INNER), -1))
        assertEquals(40, prefs.getInt(key("float_x", INNER), -1))
        assertEquals(0, prefs.getInt(key("float_size", COVER), -1))
    }

    @Test fun writesOnOneScreenNeverTouchTheOther() {
        FoldScreens.setSplit(prefs, true)
        prefs.edit { putInt(key("float_size", COVER), 0); putInt(key("float_size", INNER), 4) }
        prefs.edit { putInt(key("float_opacity", INNER), 80) }
        assertEquals(0, prefs.getInt(key("float_size", COVER), -1))
        assertEquals(4, prefs.getInt(key("float_size", INNER), -1))
        assertEquals(80, prefs.getInt(key("float_opacity", COVER), -1))
    }

    @Test fun panelSwitchIgnoresRotationAndResolutionAndKeepsLargerAsInner() {
        // Pixel Fold px: cover 1080x2092, inner 1840x2208.
        assertEquals(FoldScreens.aspect(1080, 2092) to FoldScreens.aspect(1840, 2208), FoldScreens.panelSwitch(1080, 2092, 1840, 2208))
        assertEquals(FoldScreens.aspect(1080, 2092) to FoldScreens.aspect(1840, 2208), FoldScreens.panelSwitch(2208, 1840, 2092, 1080))
        assertNull(FoldScreens.panelSwitch(1080, 2092, 2092, 1080)) // rotation
        assertNull(FoldScreens.panelSwitch(1440, 3200, 1080, 2400)) // resolution change, same panel
        assertNull(FoldScreens.panelSwitch(0, 0, 1840, 2208)) // first sighting / mid-transition
        // Flip: the larger inner panel is the longer one.
        assertEquals(FoldScreens.aspect(720, 748) to FoldScreens.aspect(1080, 2640), FoldScreens.panelSwitch(1080, 2640, 720, 748))
    }

    @Test fun classifierFollowsPanelShapeAcrossZoomAndPostures() {
        val cover = FoldScreens.aspect(1080, 2424)
        val inner = FoldScreens.aspect(2076, 2152)
        assertEquals(COVER, FoldScreens.classify(FoldScreens.aspect(1080, 2424), cover, inner))
        assertEquals(INNER, FoldScreens.classify(FoldScreens.aspect(2152, 2076), cover, inner))
        // Screen zoom changes dp, not the panel: the same ratio in dp reads the same.
        for (density in listOf(2.2f, 2.625f, 3f)) {
            assertEquals(COVER, FoldScreens.classify(FoldScreens.aspect((1080 / density).toInt(), (2424 / density).toInt()), cover, inner))
            assertEquals(INNER, FoldScreens.classify(FoldScreens.aspect((2076 / density).toInt(), (2152 / density).toInt()), cover, inner))
        }
        // Tabletop/book keep the inner panel lit (same display size): inner.
        assertEquals(INNER, FoldScreens.classify(FoldScreens.aspect(2076, 2152), cover, inner))
        // Unknown panels use the shared keys.
        assertEquals(COVER, FoldScreens.classify(inner, 0f, 0f))
        assertEquals(COVER, FoldScreens.classify(inner, cover, 0f))
    }

    @Test fun positionsArePerScreenAndClampOnScreen() {
        FoldScreens.setSplit(prefs, true)
        prefs.edit { putInt(key("float_x", INNER), 1700); putInt(key("float_y", INNER), 2000) }
        prefs.edit { putInt(key("float_x", COVER), 40); putInt(key("float_y", COVER), 300) }
        // Cover bounds (1080 wide): an inner-screen spot would land off-screen; the cover keeps its own.
        assertEquals(40 to 300, FloatState.clampBubble(prefs.getInt(key("float_x", COVER), 60), prefs.getInt(key("float_y", COVER), 400), 32, 0, 900, 1900))
        assertEquals(900 to 1900, FloatState.clampBubble(prefs.getInt("float_x_inner", 60), prefs.getInt("float_y_inner", 400), 32, 0, 900, 1900))
        // Reset: no saved spot falls back to the default, still clamped.
        prefs.edit { remove(key("float_x", INNER)); remove(key("float_y", INNER)) }
        assertEquals(60 to 400, FloatState.clampBubble(prefs.getInt(key("float_x", INNER), 60), prefs.getInt(key("float_y", INNER), 400), 32, 0, 1600, 2000))
        assertEquals(40, prefs.getInt(key("float_x", COVER), -1))
    }

    @Test fun grabAndExtraSmallPausesApplyPerScreen() {
        FoldScreens.setSplit(prefs, true)
        prefs.edit {
            putString(key("float_idle_style", COVER), "dot"); putBoolean(key("float_grab", COVER), true)
            putString(key("float_idle_style", INNER), "small"); putBoolean(key("float_extra_small", INNER), true)
        }
        fun effective(screen: FoldScreens.Screen) = BubbleAppearance.effective(
            BubbleAppearance.idleBehavior(prefs.getString(key("float_idle_style", screen), "full")),
            prefs.getBoolean(key("float_grab", screen), false), prefs.getBoolean(key("float_extra_small", screen), false))
        // Cover: Grab point pauses the dot choice; inner: Extra small pauses Shrink smaller.
        assertEquals(BubbleAppearance.IdleBehavior.FULL, effective(COVER))
        assertEquals(BubbleAppearance.IdleBehavior.FULL, effective(INNER))
        // The other screen's pausing setting never applies here.
        prefs.edit { putBoolean(key("float_grab", COVER), false) }
        assertEquals(BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY, effective(COVER))
        assertEquals(BubbleAppearance.IdleBehavior.FULL, effective(INNER))
        prefs.edit { putBoolean(key("float_extra_small", INNER), false) }
        assertEquals(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, effective(INNER))
        assertEquals(50, BubbleAppearance.buttonSize(prefs.getInt(key("float_size", INNER), 2), prefs.getBoolean(key("float_extra_small", INNER), false)))
    }
}
