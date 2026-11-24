package com.mywhisper.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 33])
class LauncherIconManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun newInstallUsesDefaultAndMainActivityRemainsLaunchable() {
        val manager = LauncherIconManager(context)
        assertEquals(LauncherIconStyle.DEFAULT, manager.selected())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java)))
        assertEquals(listOf("$ALIAS_NAMESPACE.LauncherDefault"), launcherActivities())
    }

    @Test
    fun everySelectionSurvivesNewManagerAndLeavesExactlyOneLauncher() {
        val manager = LauncherIconManager(context)
        for (style in LauncherIconStyle.entries.reversed()) {
            manager.select(style)
            assertEquals(style, LauncherIconManager(context).selected())
            assertEquals(listOf("$ALIAS_NAMESPACE.${style.alias}"), launcherActivities())
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java)))
        }
    }

    @Test
    fun explicitNavyIsDistinctFromChannelDefault() {
        val manager = LauncherIconManager(context)
        manager.select(LauncherIconStyle.NAVY)
        assertEquals(LauncherIconStyle.NAVY, manager.selected())
        manager.select(LauncherIconStyle.DEFAULT)
        assertEquals(LauncherIconStyle.DEFAULT, manager.selected())
        assertEquals(LauncherIconStyle.NAVY, LauncherIconStyle.defaultPalette(false))
        assertEquals(LauncherIconStyle.COBALT, LauncherIconStyle.defaultPalette(true))
    }

    @Test
    fun paletteAliasesAlwaysPointAtExistingAdaptiveArtwork() {
        for (style in LauncherIconStyle.entries) {
            val info = context.packageManager.getActivityInfo(
                ComponentName(context.packageName, "$ALIAS_NAMESPACE.${style.alias}"),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(LauncherActivity::class.java.name, info.targetActivity)
            assertTrue(info.icon != 0)
            assertTrue(context.resources.getDrawable(info.icon, context.theme) is android.graphics.drawable.AdaptiveIconDrawable)
        }
    }

    @Test
    fun defaultArtworkMatchesInstalledChannel() {
        val palette = LauncherIconStyle.defaultPalette(context.resources.getBoolean(R.bool.launcher_default_cobalt))
        val icon = context.resources.getDrawable(R.mipmap.ic_launcher, context.theme)
            as android.graphics.drawable.AdaptiveIconDrawable
        val background = icon.background
        val color = when (background) {
            is android.graphics.drawable.ColorDrawable -> background.color
            is android.graphics.drawable.BitmapDrawable -> background.bitmap.getPixel(0, 0)
            else -> error("Unexpected launcher background")
        }
        assertEquals(palette.background.toInt(), color)
        val foreground = (icon.foreground as android.graphics.drawable.BitmapDrawable).bitmap
        assertEquals(palette.foreground.toInt(), foreground.getPixel(foreground.width / 2, foreground.height / 2))
    }

    private fun launcherActivities() = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0,
    ).map { it.activityInfo.name }
}
