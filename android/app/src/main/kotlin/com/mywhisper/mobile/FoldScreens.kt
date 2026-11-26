package com.mywhisper.mobile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Foldables (#113): optional separate floating button settings for the cover and inner screen.
 * Unsuffixed keys hold the shared (and cover) values; the inner screen uses `<key>_inner`
 * while [SPLIT] is on. Turning [SPLIT] off ignores the inner keys but keeps them.
 */
object FoldScreens {
    enum class Screen { COVER, INNER }

    const val SPLIT = "float_split_screens"
    private const val SEEDED = "float_split_seeded"
    private const val PREFS = "mw_state"

    // Each panel's shape (long/short side), recorded when the default display switches panels.
    private const val COVER_ASPECT = "fold_cover_aspect"
    private const val INNER_ASPECT = "fold_inner_aspect"
    private const val LAST_SHORT = "fold_last_short"
    private const val LAST_LONG = "fold_last_long"

    /** Settings that follow the active screen while [SPLIT] is on. Everything else is always shared. */
    val PER_SCREEN = listOf(
        "float_grab", "float_size", "float_extra_small", "float_idle_style",
        "float_idle_delay", "float_shrink_search", "float_x", "float_y",
    )

    /** The one key resolver for every reader and writer (service, settings, preview). */
    fun key(base: String, screen: Screen, split: Boolean): String =
        if (split && screen == Screen.INNER && base in PER_SCREEN) "${base}_inner" else base

    fun other(screen: Screen) = if (screen == Screen.COVER) Screen.INNER else Screen.COVER

    fun aspect(short: Int, long: Int): Float = maxOf(short, long).toFloat() / minOf(short, long).coerceAtLeast(1)

    /**
     * A different panel, not a rotation or a resolution change: its long/short ratio moves by over 15%.
     * Returns the (cover, inner) aspects with the larger panel as inner, or null when nothing switched.
     */
    fun panelSwitch(fromW: Int, fromH: Int, toW: Int, toH: Int): Pair<Float, Float>? {
        if (minOf(fromW, fromH, toW, toH) <= 0) return null
        val from = aspect(fromW, fromH)
        val to = aspect(toW, toH)
        if (abs(from - to) / minOf(from, to) <= 0.15f) return null
        return if (toW.toLong() * toH > fromW.toLong() * fromH) from to to else to to from
    }

    /**
     * The panel in use is the recorded shape it is closest to. Px ratios ignore Screen zoom and
     * resolution, and a half-open fold (tabletop/book) keeps the inner panel lit, so it reads INNER.
     * Unknown panels resolve to COVER, i.e. the shared keys.
     */
    fun classify(aspect: Float, coverAspect: Float, innerAspect: Float): Screen = when {
        coverAspect <= 0f || innerAspect <= 0f -> Screen.COVER
        abs(aspect - innerAspect) < abs(aspect - coverAspect) -> Screen.INNER
        else -> Screen.COVER
    }

    /** Turning on seeds the inner keys from the shared values once, so nothing changes until edited. */
    fun setSplit(prefs: SharedPreferences, on: Boolean) {
        val all = prefs.all
        prefs.edit {
            putBoolean(SPLIT, on)
            if (on && all[SEEDED] != true) {
                putBoolean(SEEDED, true)
                for (base in PER_SCREEN) {
                    val inner = key(base, Screen.INNER, true)
                    when (val value = all[base]) {
                        is Boolean -> putBoolean(inner, value)
                        is Int -> putInt(inner, value)
                        is String -> putString(inner, value)
                        else -> remove(inner)
                    }
                }
            }
        }
    }

    fun split(prefs: SharedPreferences) = prefs.getBoolean(SPLIT, false)

    /**
     * The switch shows only after the default display was seen switching panels, i.e. the
     * overlay can live on both. Flip phones whose cover screen is a separate display never switch.
     * shortcut: a foldable shows the switch only after one fold seen while the app or button runs;
     * upgrade to androidx.window FoldingFeature if users miss it on first launch.
     */
    fun supported(prefs: SharedPreferences) = prefs.getFloat(INNER_ASPECT, 0f) > 0f

    /** Records the current panel; call on start and on every configuration change. */
    fun observe(context: Context) {
        val (short, long) = panelSize(context) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShort = prefs.getInt(LAST_SHORT, 0)
        val lastLong = prefs.getInt(LAST_LONG, 0)
        if (lastShort == short && lastLong == long) return
        val panels = panelSwitch(lastShort, lastLong, short, long)
        prefs.edit {
            putInt(LAST_SHORT, short)
            putInt(LAST_LONG, long)
            if (panels != null) {
                putFloat(COVER_ASPECT, panels.first)
                putFloat(INNER_ASPECT, panels.second)
            }
        }
    }

    /** The physical panel in use, whether or not [SPLIT] is on. */
    fun current(context: Context): Screen {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (short, long) = panelSize(context) ?: return Screen.COVER
        return classify(aspect(short, long), prefs.getFloat(COVER_ASPECT, 0f), prefs.getFloat(INNER_ASPECT, 0f))
    }

    /** The screen whose keys apply right now: always COVER (shared) while [SPLIT] is off. */
    fun active(context: Context): Screen =
        if (split(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))) current(context) else Screen.COVER

    fun activeKey(context: Context, base: String): String = key(base, active(context), true)

    /** Default display size in px as (short, long): rotation-free, Screen zoom-free. */
    private fun panelSize(context: Context): Pair<Int, Int>? = try {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        if (size.x > 0 && size.y > 0) minOf(size.x, size.y) to maxOf(size.x, size.y) else null
    } catch (_: Exception) {
        null
    }
}
