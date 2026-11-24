package com.mywhisper.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes

/** Component names are persistent Android settings: keep them stable across app updates. */
enum class LauncherIconStyle(
    val alias: String,
    @get:StringRes val label: Int,
    val background: Long,
    val foreground: Long,
) {
    DEFAULT("LauncherDefault", R.string.icon_style_default, 0, 0),
    NAVY("LauncherNavy", R.string.icon_style_navy, 0xFF142234, 0xFF5CD9EC),
    COBALT("LauncherCobalt", R.string.icon_style_cobalt, 0xFF3158CA, 0xFFFFFFFF),
    CREAM("LauncherCream", R.string.icon_style_cream, 0xFFF2EBDD, 0xFF24342F),
    CHARCOAL("LauncherCharcoal", R.string.icon_style_charcoal, 0xFF24272C, 0xFFFFFFFF);

    companion object {
        fun defaultPalette(nightly: Boolean) = if (nightly) COBALT else NAVY
    }
}

/** Android persists alias states across restarts and stable/nightly/lite/full upgrades.
 * Use that state directly rather than a second preference that could disagree with the launcher.
 */
/** Manifest namespace that `.Launcher*` activity-alias names resolve against. */
internal const val ALIAS_NAMESPACE = "com.mywhisper.mobile"

class LauncherIconManager(private val context: Context) {
    private val pm get() = context.packageManager
    // Aliases keep the manifest namespace even when the build adds an
    // applicationId suffix (side-by-side ".test" installs).
    private fun component(style: LauncherIconStyle) =
        ComponentName(context.packageName, "$ALIAS_NAMESPACE.${style.alias}")

    fun selected(): LauncherIconStyle = LauncherIconStyle.entries.firstOrNull {
        pm.getComponentEnabledSetting(component(it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: LauncherIconStyle.DEFAULT

    fun select(style: LauncherIconStyle) {
        val previous = LauncherIconStyle.entries.associateWith {
            pm.getComponentEnabledSetting(component(it))
        }
        val states = LauncherIconStyle.entries.associateWith {
            if (it == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // One transaction; no moment with missing or duplicate launcher entries.
            pm.setComponentEnabledSettings(states.map { (entry, state) ->
                PackageManager.ComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
            })
        } else {
            // Older phones: enable the destination first so the app never loses its launcher.
            try {
                pm.setComponentEnabledSetting(component(style), states.getValue(style), PackageManager.DONT_KILL_APP)
                states.filterKeys { it != style }.forEach { (entry, state) ->
                    pm.setComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
                }
            } catch (failure: Exception) {
                previous.forEach { (entry, state) ->
                    try {
                        pm.setComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
                    } catch (rollback: Exception) {
                        failure.addSuppressed(rollback)
                    }
                }
                throw failure
            }
        }
    }
}
