package com.mywhisper.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Keeps the sync engine alive with a running notification: the
 * listener, mDNS discovery and the periodic rounds all live in this
 * process, and Android kills background processes without a
 * foreground service. Start from the Sync tab (or at boot when sync
 * is enabled); stop from the tab or the notification action.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_START = "com.mywhisper.mobile.sync.START"
        const val ACTION_STOP = "com.mywhisper.mobile.sync.STOP"
        const val ACTION_SYNC_NOW = "com.mywhisper.mobile.sync.NOW"
        const val ACTION_REFRESH = "com.mywhisper.mobile.sync.REFRESH_NOTIF"
        private const val CHANNEL = "mw-sync"
        private const val NOTIF_ID = 41

        /** Boot/update attempt carried for bounded retry (default -1 = interactive, no retry). */
        const val EXTRA_BOOT_ATTEMPT = "boot_attempt"

        fun start(ctx: Context, bootAttempt: Int = -1) {
            val i = Intent(ctx, SyncService::class.java).setAction(ACTION_START)
            if (bootAttempt >= 0) i.putExtra(EXTRA_BOOT_ATTEMPT, bootAttempt)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }

        fun syncNow(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_SYNC_NOW))
        }

        /** Rebuild the foreground notification from current prefs (no restart). */
        fun refreshNotif(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_REFRESH))
            } catch (_: Exception) {
            }
        }
    }

    private val workers = Executors.newSingleThreadExecutor()
    @Volatile private var syncing = false
    /**
     * True once [startForegroundWithType] succeeds, false on STOP /
     * destroy. REFRESH must never recreate a stopped service
     * un-foregrounded (PR #2 pattern): an unguarded notify() would
     * leave an orphan ongoing notification STOP cannot remove.
     */
    @Volatile private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Device sync", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("MwSync", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                android.util.Log.i("MwSync", "stopping")
                CoreBridge.syncStop()
                foregrounded = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                // Notify only while foregrounded: a stopped service
                // must not mint an orphan notification it cannot own.
                syncing = true
                if (foregrounded) refreshSyncNotif()
                workers.execute {
                    try {
                        CoreBridge.syncAllNow()
                    } catch (_: Exception) {
                    } finally {
                        syncing = false
                        if (foregrounded) refreshSyncNotif()
                    }
                }
                return START_STICKY
            }
            ACTION_REFRESH -> {
                // Prefs toggle: rebuild from live state, no restart —
                // but never promote a stopped service. Self-terminate
                // instead of refreshing un-foregrounded.
                if (!foregrounded) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                refreshSyncNotif()
                return START_STICKY
            }
            else -> {
                // Foreground is mandatory: a background start denied
                // here must not run un-foregrounded — the system kills
                // us ~10s later. Stop instead; a boot/update start
                // feeds back into the bounded alarm retry (succeeds on
                // a later alarm once the app is foregrounded), an
                // interactive start never denies and never retries.
                val bootAttempt = intent?.getIntExtra(EXTRA_BOOT_ATTEMPT, -1) ?: -1
                try {
                    startForegroundWithType()
                } catch (e: Exception) {
                    android.util.Log.i("MwSync", "fg denied, stopping: ${e.message}")
                    try {
                        CoreBridge.syncStop()
                    } catch (_: Exception) {
                    }
                    BootPolicy.nextBootRetryAttempt(bootAttempt, BootReceiver.MAX_RETRIES)?.let { next ->
                        android.util.Log.i("MwSync", "boot start denied, retrying (attempt=$next)")
                        BootReceiver.scheduleRetry(this, next)
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                workers.execute {
                    try {
                        CoreBridge.syncStart()
                    } catch (_: Exception) {
                    }
                }
                foregrounded = true
                return START_STICKY
            }
        }
    }

    /**
     * Per-mode sync notification driven by [NotifPolicy]: idle =
     * base text, syncing = working text + indeterminate progress
     * when the pref allows. STOP + contentIntent on every mode;
     * Sync-now action + BigTextStyle keep the actions expanded.
     */
    internal fun buildSyncNotif(syncing: Boolean): Notification {
        val prefs = try {
            NotifPolicy.syncPrefsJson(CoreBridge.getSettings().getOrThrow().optJSONObject("preferences"))
        } catch (_: Exception) {
            NotifPolicy.SyncPrefs()
        }
        val spec = NotifPolicy.forSync(syncing, prefs)
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nowIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SyncService::class.java).setAction(ACTION_SYNC_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (syncing) getString(R.string.notif_sync_working_text)
        else getString(R.string.notif_sync_text)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_sync_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent)
            .setOngoing(spec.ongoing)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.notif_sync_now), nowIntent)
        if (spec.showProgress) b.setProgress(0, 0, true) else b.setProgress(0, 0, false)
        return b.build()
    }

    /** Refresh the foreground notification for the live sync state (no stale mode). */
    private fun refreshSyncNotif() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildSyncNotif(syncing))
        } catch (_: Exception) {
        }
    }

    private fun startForegroundWithType() {
        val notif: Notification = buildSyncNotif(syncing)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        foregrounded = false
        try {
            CoreBridge.syncStop()
        } catch (_: Exception) {
        }
        workers.shutdownNow()
        super.onDestroy()
    }
}

/**
 * Pure boot-start policy (unit-testable): each service decides
 * independently so one bad branch never blocks the other, and the
 * bubble never starts at boot without overlay permission (its
 * foreground start would be denied and immediately stopped).
 */
internal object BootPolicy {
    fun shouldStartSync(syncEnabled: Boolean, startOnBoot: Boolean): Boolean =
        syncEnabled && startOnBoot

    fun shouldStartFloat(floatEnabled: Boolean, canDrawOverlays: Boolean): Boolean =
        floatEnabled && canDrawOverlays

    /**
     * True when [e] is an Android 12+ background FGS denial. Name-based
     * (no API-31 class reference) so minSdk 29 never linkage-crashes.
     */
    fun isFgsDenied(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 10) {
            if (t.javaClass.name.contains("ForegroundServiceStartNotAllowed")) return true
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * Next boot/update retry attempt after [attempt], or null when
     * exhausted (interactive starts pass -1 and never retry).
     */
    fun nextBootRetryAttempt(attempt: Int, maxRetries: Int): Int? {
        if (attempt < 0 || attempt >= maxRetries) return null
        return attempt + 1
    }

    /**
     * Retry a failed boot/update start only for transient causes (FGS
     * denial while backgrounded, locked keystore) with attempts left.
     * Anything else fails fast: no blind retry loop.
     */
    fun shouldRetryBootStart(e: Throwable, attempt: Int, maxRetries: Int): Boolean =
        (isFgsDenied(e) || HardeningGuards.isKeystoreLocked(e)) &&
            HardeningGuards.bootAttemptWithinLimit(attempt, maxRetries)
}

/**
 * Restart sync after reboot when it is enabled (desktop starts its
 * engine from systemd; this is the mobile equivalent).
 *
 * At boot the credential-encrypted keystore may still be locked, so a
 * settings read can throw: instead of failing silently, retry with
 * backoff and re-trigger on unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!handlesAction(intent.action)) return
        attemptStart(ctx, intent.getIntExtra(EXTRA_ATTEMPT, 0))
    }

    private fun attemptStart(ctx: Context, attempt: Int) {
        val app = ctx.applicationContext
        try {
            val s = CoreBridge.getSettings().getOrThrow()
            val sync = s.optJSONObject("sync")
            val syncEnabled = sync?.optBoolean("enabled", true) == true
            val startOnBoot = sync?.optBoolean("start_on_boot", true) == true
            if (BootPolicy.shouldStartSync(syncEnabled, startOnBoot)) {
                // The BOOT_COMPLETED temp allowlist lets start() through,
                // but startForeground(dataSync) is still denied from a
                // boot/update source (API 34+ FGS-type gate) — the service
                // self-stops and feeds back into the bounded alarm retry
                // via EXTRA_BOOT_ATTEMPT. A bare start() throw (no
                // allowlist, e.g. alarm retry while backgrounded) retries
                // here. Either way: never swallow, always bounded.
                try {
                    SyncService.start(app, attempt)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "MwSync",
                        "boot sync start failed (attempt=$attempt fgsDenied=${BootPolicy.isFgsDenied(e)}): ${e.message}",
                    )
                    if (BootPolicy.shouldRetryBootStart(e, attempt, MAX_RETRIES)) {
                        scheduleRetry(ctx, attempt + 1)
                    }
                }
            }
            val floatEnabled =
                s.optJSONObject("preferences")?.optBoolean("float_enabled", false) == true
            val canOverlay = try {
                android.provider.Settings.canDrawOverlays(app)
            } catch (_: Exception) {
                false
            }
            if (BootPolicy.shouldStartFloat(floatEnabled, canOverlay)) {
                try {
                    OverlayService.start(app)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "MwSync",
                        "boot float start failed (attempt=$attempt fgsDenied=${BootPolicy.isFgsDenied(e)}): ${e.message}",
                    )
                    if (BootPolicy.shouldRetryBootStart(e, attempt, MAX_RETRIES)) {
                        scheduleRetry(ctx, attempt + 1)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "MwSync",
                "boot start deferred (attempt=$attempt locked=${HardeningGuards.isKeystoreLocked(e)}): ${e.message}",
            )
            if (HardeningGuards.bootAttemptWithinLimit(attempt, MAX_RETRIES)) scheduleRetry(ctx, attempt + 1)
        }
    }

    companion object {
        const val ACTION_RETRY = "com.mywhisper.mobile.sync.BOOT_RETRY"
        const val EXTRA_ATTEMPT = "attempt"
        const val MAX_RETRIES = 4
        private const val RETRY_REQ_CODE = 900

        /**
         * Bounded boot/update retry alarm (1m, 2m, 4m, 8m backoff).
         * Companion so the denied service start can feed back into the
         * same chain from [SyncService.onStartCommand].
         */
        internal fun scheduleRetry(ctx: Context, nextAttempt: Int) {
            try {
                val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    ctx.applicationContext, RETRY_REQ_CODE + nextAttempt,
                    Intent(ctx.applicationContext, BootReceiver::class.java)
                        .setAction(ACTION_RETRY)
                        .putExtra(EXTRA_ATTEMPT, nextAttempt),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + nextRetryDelayMs(nextAttempt),
                    pi,
                )
            } catch (_: Exception) {
            }
        }

        /**
         * Actions routed to [attemptStart]: boot, unlock, app-update
         * replace (same path restarts sync + float after update) and
         * the internal retry alarm. Unit-testable without Robolectric
         * (action strings only, no Context needed).
         */
        fun handlesAction(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_RETRY

        /**
         * Backoff for retry N (1-based): 1m, 2m, 4m, 8m, capped at 15m.
         * Delegates to [HardeningGuards] for JVM tests.
         */
        fun nextRetryDelayMs(retryNumber: Int): Long =
            HardeningGuards.bootRetryDelayMs(retryNumber)

        /**
         * True when the failure chain smells like a locked credential
         * keystore. Delegates to [HardeningGuards] for JVM tests.
         */
        fun isKeystoreLocked(e: Throwable): Boolean =
            HardeningGuards.isKeystoreLocked(e)
    }
}
