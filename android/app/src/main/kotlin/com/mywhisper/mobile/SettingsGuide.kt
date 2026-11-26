package com.mywhisper.mobile

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Setup steps that send the user into Android Settings (#125): what each one
 * opens, how its change is detected, and the auto-return poll. Picture-in-picture
 * ([SetupGuideActivity]) is used only where the user has to navigate inside Settings.
 */
internal object SettingsGuide {
    enum class Step { OVERLAY, ACCESSIBILITY, RESTRICTED, STATUS_CHANNEL }

    /** Overlay and channel pages open on the right toggle; the rest need a path. */
    fun usesPip(step: Step) = step == Step.ACCESSIBILITY || step == Step.RESTRICTED

    // There is no public deep link to one accessibility service: ACTION_ACCESSIBILITY_DETAILS_SETTINGS
    // is @SystemApi through compileSdk 36, so the PiP shows the path from the list instead.
    fun intent(ctx: Context, step: Step): Intent = when (step) {
        Step.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        Step.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // The apps list, not App info: Android hides "Allow restricted settings" when the
        // restricted app itself opens its App info page (checked on API 33).
        Step.RESTRICTED -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        Step.STATUS_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, NotifPolicy.UNIFIED_CHANNEL)
    }

    /** True once the step's target state is reached. */
    fun granted(ctx: Context, step: Step): Boolean = try {
        when (step) {
            Step.OVERLAY -> Settings.canDrawOverlays(ctx)
            Step.ACCESSIBILITY -> MyWhisperAccessibility.isOn(ctx)
            // The channel step asks the user to hide the always-on status notification.
            Step.STATUS_CHANNEL -> ctx.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(NotifPolicy.UNIFIED_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
            // shortcut: "Allow restricted settings" cannot be read by apps (the app-op is restricted-read and
            // Android 15+ moved it behind a system API), so this step never auto-returns; the user comes
            // back by hand. Upgrade if a public check appears.
            Step.RESTRICTED -> false
        }
    } catch (_: Exception) {
        false
    }

    /** PiP needs the system feature and the user not having switched it off for this app. */
    fun pipAllowed(hasFeature: Boolean, appOpMode: Int) =
        hasFeature && (appOpMode == AppOpsManager.MODE_ALLOWED || appOpMode == AppOpsManager.MODE_DEFAULT)

    fun pipAllowed(ctx: Context): Boolean {
        val mode = try {
            ctx.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), ctx.packageName)
        } catch (_: Exception) {
            AppOpsManager.MODE_IGNORED
        }
        return pipAllowed(ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE), mode)
    }

    /**
     * Android 13+ blocks accessibility for apps installed from a downloaded or local APK
     * ("Restricted setting") until the user allows it in App info.
     */
    fun restrictedLikely(sdk: Int, packageSource: Int) = sdk >= 33 &&
        (packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

    // shortcut: the install source only says the block is likely, not that it is active (that state is
    // not readable by apps), so it surfaces as a help link instead of a forced step. Upgrade if Android
    // exposes the restriction state publicly.
    fun restrictedLikely(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val source = try {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource
        } catch (_: Exception) {
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
        }
        return restrictedLikely(Build.VERSION.SDK_INT, source)
    }

    /** Accessibility path keyed by manufacturer. Strings need confirming on real devices. */
    fun a11yPathRes(manufacturer: String): Int = when (manufacturer.lowercase()) {
        "samsung" -> R.string.guide_a11y_path_samsung
        "xiaomi", "redmi", "poco" -> R.string.guide_a11y_path_xiaomi
        else -> R.string.guide_a11y_path_generic
    }

    private val handler = Handler(Looper.getMainLooper())
    const val POLL_MS = 500L
    const val DEADLINE_MS = 180_000L

    /**
     * Brings MyWhisper back as soon as [granted] turns true, so setup continues
     * without hunting for the app. Polls every 500 ms for up to 3 minutes; the
     * guide closes on success and on the deadline. Starting from the background
     * is allowed here: by then the overlay permission is granted.
     */
    fun watch(ctx: Context, granted: () -> Boolean) {
        val app = ctx.applicationContext
        handler.removeCallbacksAndMessages(null)
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        val check = object : Runnable {
            override fun run() {
                if (try { granted() } catch (_: Exception) { false }) {
                    SetupGuideActivity.close()
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } else if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, POLL_MS)
                } else {
                    SetupGuideActivity.close()
                }
            }
        }
        handler.postDelayed(check, POLL_MS)
    }

    /** The user is back by hand: stop polling and remove any guide window. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        SetupGuideActivity.close()
    }
}
