package com.mywhisper.mobile

import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageInstaller
import android.provider.Settings
import com.mywhisper.mobile.SettingsGuide.Step
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SettingsGuideTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun pipOnlyWhereTheUserNavigatesInsideSettings() {
        assertEquals(setOf(Step.ACCESSIBILITY, Step.RESTRICTED), Step.entries.filter(SettingsGuide::usesPip).toSet())
    }

    @Test fun eachStepOpensItsSettingsPage() {
        val pkg = app.packageName
        SettingsGuide.intent(app, Step.OVERLAY).let {
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, it.action)
            assertEquals("package:$pkg", it.dataString)
        }
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, SettingsGuide.intent(app, Step.ACCESSIBILITY).action)
        SettingsGuide.intent(app, Step.RESTRICTED).let {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it.action)
            assertEquals("package:$pkg", it.dataString)
        }
        SettingsGuide.intent(app, Step.STATUS_CHANNEL).let {
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, it.action)
            assertEquals(pkg, it.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(NotifPolicy.UNIFIED_CHANNEL, it.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
    }

    @Test fun overlayGrantedFollowsCanDrawOverlays() {
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(SettingsGuide.granted(app, Step.OVERLAY))
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(SettingsGuide.granted(app, Step.OVERLAY))
    }

    @Test fun accessibilityGrantedWhenOurServiceIsEnabled() {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        Settings.Secure.putString(app.contentResolver, key, "other.app/.Service")
        assertFalse(SettingsGuide.granted(app, Step.ACCESSIBILITY))
        Settings.Secure.putString(app.contentResolver, key, "other.app/.Service:${app.packageName}/${MyWhisperAccessibility::class.java.name}")
        assertTrue(SettingsGuide.granted(app, Step.ACCESSIBILITY))
    }

    @Test fun channelGrantedOnlyOnceTheStatusChannelIsHidden() {
        val nm = app.getSystemService(NotificationManager::class.java)
        assertFalse("missing channel", SettingsGuide.granted(app, Step.STATUS_CHANNEL))
        nm.createNotificationChannel(NotificationChannel(NotifPolicy.UNIFIED_CHANNEL, "status", NotificationManager.IMPORTANCE_LOW))
        assertFalse(SettingsGuide.granted(app, Step.STATUS_CHANNEL))
        nm.getNotificationChannel(NotifPolicy.UNIFIED_CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        assertTrue(SettingsGuide.granted(app, Step.STATUS_CHANNEL))
    }

    @Test fun restrictedNeverAutoReturns() {
        assertFalse(SettingsGuide.granted(app, Step.RESTRICTED))
    }

    @Test fun manufacturerPicksPathWithGenericFallback() {
        assertEquals(R.string.guide_a11y_path_samsung, SettingsGuide.a11yPathRes("samsung"))
        assertEquals(R.string.guide_a11y_path_samsung, SettingsGuide.a11yPathRes("Samsung"))
        for (m in listOf("Xiaomi", "Redmi", "POCO")) assertEquals(m, R.string.guide_a11y_path_xiaomi, SettingsGuide.a11yPathRes(m))
        for (m in listOf("Google", "OnePlus", "unknown", "")) assertEquals(m, R.string.guide_a11y_path_generic, SettingsGuide.a11yPathRes(m))
    }

    @Test fun pipNeedsFeatureAndAppOp() {
        assertTrue(SettingsGuide.pipAllowed(hasFeature = true, appOpMode = AppOpsManager.MODE_ALLOWED))
        assertTrue(SettingsGuide.pipAllowed(hasFeature = true, appOpMode = AppOpsManager.MODE_DEFAULT))
        assertFalse("user turned PiP off", SettingsGuide.pipAllowed(hasFeature = true, appOpMode = AppOpsManager.MODE_IGNORED))
        assertFalse(SettingsGuide.pipAllowed(hasFeature = true, appOpMode = AppOpsManager.MODE_ERRORED))
        assertFalse("no PiP on device", SettingsGuide.pipAllowed(hasFeature = false, appOpMode = AppOpsManager.MODE_ALLOWED))
    }

    @Test fun restrictedSettingLikelyOnlyForSideloadsOnAndroid13Plus() {
        val sideloads = listOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        for (src in sideloads) {
            assertTrue(SettingsGuide.restrictedLikely(33, src))
            assertTrue(SettingsGuide.restrictedLikely(36, src))
            assertFalse(SettingsGuide.restrictedLikely(32, src))
        }
        for (src in listOf(PackageInstaller.PACKAGE_SOURCE_STORE, PackageInstaller.PACKAGE_SOURCE_OTHER, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)) {
            assertFalse(SettingsGuide.restrictedLikely(33, src))
        }
    }
}
