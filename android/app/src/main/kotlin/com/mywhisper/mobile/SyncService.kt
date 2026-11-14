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
import androidx.core.content.edit
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
        /** Notification STOP: stops BOTH services (single STOP stops one). */
        const val ACTION_STOP_ALL = "com.mywhisper.mobile.sync.STOP_ALL"
        const val ACTION_SYNC_NOW = "com.mywhisper.mobile.sync.NOW"
        const val ACTION_REFRESH = "com.mywhisper.mobile.sync.REFRESH_NOTIF"
        // Shared row: same channel + ID as OverlayService (see
        // NotifPolicy.UNIFIED_*); one visible row, two FGS owners.
        private const val CHANNEL = "mw-status"
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

        /**
         * First-foreground promote: BOOT_COMPLETED on API 31+ can never
         * start a dataSync FGS while backgrounded
         * (ForegroundServiceStartNotAllowedException, no bg exemption),
         * so the receiver parks a pending flag and the sync tab calls
         * this on first composition (activity visible = foreground
         * exemption holds, start is allowed). Returns true when a start
         * was issued.
         */
        fun promotePendingOnForeground(ctx: Context): Boolean {
            val app = ctx.applicationContext
            if (!BootReceiver.isSyncBootPending(app)) return false
            val s = try {
                CoreBridge.getSettings().getOrThrow()
            } catch (_: Exception) {
                return false
            }
            val sync = s.optJSONObject("sync")
            val enabled = sync?.optBoolean("enabled", true) == true
            val onBoot = sync?.optBoolean("start_on_boot", true) == true
            if (!BootPolicy.shouldPromoteOnForeground(enabled, onBoot, pending = true, running = NotifPolicy.UnifiedState.syncActive)) return false
            BootReceiver.clearSyncBootPending(app)
            start(app)
            return true
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
            NotificationChannel(CHANNEL, getString(R.string.unified_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // Drop the retired solo channels (pre-unification installs).
        try {
            for (id in NotifPolicy.LEGACY_CHANNEL_IDS) mgr?.deleteNotificationChannel(id)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("MwSync", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                android.util.Log.i("MwSync", "stopping")
                CoreBridge.syncStop()
                foregrounded = false
                NotifPolicy.UnifiedState.syncActive = false
                NotifPolicy.UnifiedState.syncing = false
                syncing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                // A manual stop cancels any parked boot-pending intent.
                try {
                    BootReceiver.clearSyncBootPending(this)
                } catch (_: Exception) {
                }
                // Retired solo bubble row (pre-unification installs):
                // single-STOP must never leave it behind either.
                try {
                    getSystemService(NotificationManager::class.java)?.cancel(NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
                } catch (_: Exception) {
                }
                // Shared row: we just removed it — if the bubble still
                // lives, hand it back immediately (recompose, not orphan).
                try {
                    if (NotifPolicy.UnifiedState.floatActive) OverlayService.refreshNotif(this)
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                // Notification STOP: both rows are one row, so one tap
                // stops both (single STOP from a tab toggle stops one).
                android.util.Log.i("MwSync", "stopping all")
                CoreBridge.syncStop()
                foregrounded = false
                NotifPolicy.UnifiedState.syncActive = false
                NotifPolicy.UnifiedState.syncing = false
                syncing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                // A manual stop-all cancels any parked boot-pending intent.
                try {
                    BootReceiver.clearSyncBootPending(this)
                } catch (_: Exception) {
                }
                try {
                    OverlayService.stop(this)
                } catch (_: Exception) {
                }
                try {
                    getSystemService(NotificationManager::class.java)?.cancel(NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                // Notify only while foregrounded: a stopped service
                // must not mint an orphan notification it cannot own.
                syncing = true
                NotifPolicy.UnifiedState.syncing = true
                if (foregrounded) refreshSyncNotif()
                workers.execute {
                    try {
                        CoreBridge.syncAllNow()
                    } catch (_: Exception) {
                    } finally {
                        syncing = false
                        NotifPolicy.UnifiedState.syncing = false
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
                // Publish before foregrounding so the shared builder sees us.
                NotifPolicy.UnifiedState.syncActive = true
                NotifPolicy.UnifiedState.syncing = syncing
                try {
                    startForegroundWithType()
                } catch (e: Exception) {
                    android.util.Log.i("MwSync", "fg denied, stopping: ${e.message}")
                    try {
                        CoreBridge.syncStop()
                    } catch (_: Exception) {
                    }
                    NotifPolicy.UnifiedState.syncActive = false
                    NotifPolicy.UnifiedState.syncing = false
                    BootPolicy.nextBootRetryAttempt(bootAttempt, BootReceiver.MAX_RETRIES)?.let { next ->
                        android.util.Log.i("MwSync", "boot start denied, retrying (attempt=$next)")
                        try {
                            BootReceiver.markSyncBootPending(this, next)
                        } catch (_: Exception) {
                        }
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
                // Boot-pending fulfilled: the service is up, drop the flag
                // so the sync tab stops showing "waiting for retry/unlock".
                try {
                    BootReceiver.clearSyncBootPending(this)
                } catch (_: Exception) {
                }
                return START_STICKY
            }
        }
    }

    /**
     * SHARED unified-row builder (same channel + ID + spec as
     * OverlayService): bubble wins while active (RECORDING/WORKING), else
     * sync paints when alive, else the lone idle bubble. Self counts as
     * alive (the builder runs foregrounded); the bubble comes from
     * [NotifPolicy.UnifiedState]. STOP stops both (ACTION_STOP_ALL), tap
     * opens Setup (MainActivity lands on Setup), BigTextStyle keeps the
     * actions expanded, per-mode prefs via [NotifPolicy].
     */
    internal fun buildSyncNotif(syncing: Boolean): Notification {
        val prefsObj = try {
            CoreBridge.getSettings().getOrThrow().optJSONObject("preferences")
        } catch (_: Exception) {
            null
        }
        val floatPrefs = NotifPolicy.floatPrefsJson(prefsObj)
        val syncPrefs = NotifPolicy.syncPrefsJson(prefsObj)
        val spec = NotifPolicy.forUnified(
            floatActive = NotifPolicy.UnifiedState.floatActive,
            floatState = NotifPolicy.UnifiedState.floatState,
            floatPrefs = floatPrefs,
            syncActive = true,
            syncing = syncing,
            syncPrefs = syncPrefs,
        ) ?: NotifPolicy.UnifiedSpec(
            NotifPolicy.UnifiedChrome.SYNC_IDLE,
            ongoing = syncPrefs.ongoing,
            showProgress = false,
            showSyncNow = true,
        )
        val stopIntent = PendingIntent.getService(
            this, 10,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP_ALL),
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
        val text = when (spec.chrome) {
            NotifPolicy.UnifiedChrome.FLOAT_RECORDING -> getString(R.string.float_notif_rec_text)
            NotifPolicy.UnifiedChrome.FLOAT_WORKING -> getString(R.string.ime_working)
            NotifPolicy.UnifiedChrome.FLOAT_IDLE -> getString(R.string.float_notif_text)
            NotifPolicy.UnifiedChrome.SYNCING -> getString(R.string.notif_sync_working_text)
            NotifPolicy.UnifiedChrome.SYNC_IDLE -> getString(R.string.notif_sync_text)
        }
        val title = when (spec.chrome) {
            NotifPolicy.UnifiedChrome.FLOAT_RECORDING,
            NotifPolicy.UnifiedChrome.FLOAT_WORKING,
            NotifPolicy.UnifiedChrome.FLOAT_IDLE -> getString(R.string.float_notif_title)
            NotifPolicy.UnifiedChrome.SYNCING,
            NotifPolicy.UnifiedChrome.SYNC_IDLE -> getString(R.string.notif_sync_title)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent)
            .setOngoing(spec.ongoing)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
        if (spec.showSyncNow) {
            b.addAction(android.R.drawable.ic_popup_sync, getString(R.string.notif_sync_now), nowIntent)
        }
        if (spec.showProgress) b.setProgress(0, 0, true) else b.setProgress(0, 0, false)
        return b.build()
    }

    /** Refresh the shared foreground row for the live sync state (no stale mode). */
    private fun refreshSyncNotif() {
        try {
            NotifPolicy.UnifiedState.syncing = syncing
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildSyncNotif(syncing))
        } catch (_: Exception) {
        }
    }

    private fun startForegroundWithType() {
        val notif: Notification = buildSyncNotif(syncing)
        // Drop the retired solo bubble row (pre-unification installs).
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
        } catch (_: Exception) {
        }
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
        NotifPolicy.UnifiedState.syncActive = false
        NotifPolicy.UnifiedState.syncing = false
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

    /**
     * Direct FGS start from a BOOT_COMPLETED receiver is allowed only
     * below API 31. On 31+ a dataSync start while backgrounded always
     * throws ForegroundServiceStartNotAllowedException (no exemption),
     * so boot skips the doomed start and goes straight to
     * pending + bounded retry + first-foreground promote. Literal 31
     * (not Build.VERSION_CODES.S) keeps this JVM-testable without an
     * android.os.Build class load.
     */
    fun directStartAllowedAtBoot(sdkInt: Int): Boolean = sdkInt < 31

    /**
     * First-foreground promote gate: parked boot-pending intent turns
     * into a real start only when sync is still wanted and nothing is
     * running. Pure for unit tests.
     */
    fun shouldPromoteOnForeground(
        syncEnabled: Boolean,
        startOnBoot: Boolean,
        pending: Boolean,
        running: Boolean,
    ): Boolean = pending && syncEnabled && startOnBoot && !running

    /**
     * Honest sync-tab copy for a stopped service: null when nothing is
     * pending (UI shows "Sync stopped."), otherwise the waiting line so
     * boot never looks silently dead. Pure for unit tests.
     */
    fun bootPendingCopy(
        syncEnabled: Boolean,
        startOnBoot: Boolean,
        pending: Boolean,
        attempt: Int,
        maxRetries: Int,
    ): String? {
        if (!pending || !syncEnabled || !startOnBoot) return null
        return "Sync waiting for retry/unlock (attempt $attempt/$maxRetries)."
    }
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
        runBootAttempt(
            ctx,
            intent.getIntExtra(EXTRA_ATTEMPT, 0),
            intent.action == Intent.ACTION_BOOT_COMPLETED,
        )
    }

    companion object {
        const val ACTION_RETRY = "com.mywhisper.mobile.sync.BOOT_RETRY"
        const val EXTRA_ATTEMPT = "attempt"
        const val MAX_RETRIES = 4
        private const val RETRY_REQ_CODE = 900
        /**
         * Unique WorkManager name for the boot-retry chain: REPLACE
         * collapses duplicate alarm/worker firings onto one node, and
         * the shared attempt counter below still bounds the chain.
         */
        private const val BOOT_WORK_NAME = "mw-sync-boot-retry"
        private const val PENDING_PREFS = "mw_boot"
        private const val KEY_PENDING = "sync_boot_pending"
        private const val KEY_PENDING_ATTEMPT = "sync_boot_attempt"
        /** First retry fires fast so power-on→running is seconds, not minutes. */
        private const val BOOT_FAST_RETRY_MS = 15_000L
        /**
         * Parked boot intent: sync wants to run but the boot-time start
         * was deferred or denied. The sync tab reads this for the honest
         * interim copy ("waiting…") and the first-foreground promote.
         */
        internal fun markSyncBootPending(ctx: Context, attempt: Int) {
            try {
                ctx.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_PENDING, true)
                    putInt(KEY_PENDING_ATTEMPT, attempt)
                }
            } catch (_: Exception) {
            }
        }

        internal fun clearSyncBootPending(ctx: Context) {
            try {
                ctx.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_PENDING, false)
                }
            } catch (_: Exception) {
            }
        }

        internal fun isSyncBootPending(ctx: Context): Boolean = try {
            ctx.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING, false)
        } catch (_: Exception) {
            false
        }

        internal fun pendingAttempt(ctx: Context): Int = try {
            ctx.applicationContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PENDING_ATTEMPT, 0)
        } catch (_: Exception) {
            0
        }

        /**
         * Shared boot/update/unlock/retry entry. [isBoot] skips the
         * doomed direct start on API 31+ (see
         * [BootPolicy.directStartAllowedAtBoot]): the denial is certain
         * there, so boot parks pending and goes straight to the fast
         * retry (15s alarm + expedited work) instead of burning a
         * start/stop cycle. Unlock/retry always attempt the direct
         * start (exemption may hold when the user is already
         * foregrounded) and park pending only on denial.
         */
        internal fun runBootAttempt(ctx: Context, attempt: Int, isBoot: Boolean) {
            val app = ctx.applicationContext
            try {
                val s = CoreBridge.getSettings().getOrThrow()
                val sync = s.optJSONObject("sync")
                val syncEnabled = sync?.optBoolean("enabled", true) == true
                val startOnBoot = sync?.optBoolean("start_on_boot", true) == true
                if (BootPolicy.shouldStartSync(syncEnabled, startOnBoot)) {
                    if (isBoot && !BootPolicy.directStartAllowedAtBoot(android.os.Build.VERSION.SDK_INT)) {
                        android.util.Log.i(
                            "MwSync",
                            "boot sync deferred to retry/promote (api=${android.os.Build.VERSION.SDK_INT})",
                        )
                        markSyncBootPending(app, 0)
                        scheduleRetry(app, 0)
                        enqueueBootWork(app, 0, expedited = true)
                    } else {
                        // The BOOT_COMPLETED temp allowlist lets start()
                        // through, but startForeground(dataSync) is still
                        // denied from a boot/update source on newer APIs —
                        // the service self-stops and feeds back into the
                        // bounded retry chain via EXTRA_BOOT_ATTEMPT. A
                        // bare start() throw (no allowlist, e.g. alarm
                        // retry while backgrounded) retries here. Either
                        // way: never swallow, always bounded.
                        try {
                            SyncService.start(app, attempt)
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "MwSync",
                                "boot sync start failed (attempt=$attempt fgsDenied=${BootPolicy.isFgsDenied(e)}): ${e.message}",
                            )
                            if (BootPolicy.shouldRetryBootStart(e, attempt, MAX_RETRIES)) {
                                markSyncBootPending(ctx, attempt + 1)
                                scheduleRetry(ctx, attempt + 1)
                            }
                        }
                    }
                }
                val floatEnabled =
                    s.optJSONObject("preferences")?.optBoolean("float_enabled", true) == true
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
                if (HardeningGuards.bootAttemptWithinLimit(attempt, MAX_RETRIES)) {
                    markSyncBootPending(ctx, attempt + 1)
                    scheduleRetry(ctx, attempt + 1)
                }
            }
        }

        /**
         * Bounded boot/update retry, two Doze-aware legs on the same
         * [nextAttempt] counter: the alarm fires in Doze via
         * setAndAllowWhileIdle (inexact, ~9-min floor under deep Doze)
         * and the WorkManager one-time survives app kill. Duplicate
         * firings collapse (alarm UPDATE_CURRENT, work REPLACE) and the
         * shared counter still bounds the chain at MAX_RETRIES — never
         * infinite.
         */
        internal fun scheduleRetry(ctx: Context, nextAttempt: Int) {
            // Work leg first: it survives even if the alarm leg below
            // cannot be armed (and survives app kill where alarms don't).
            enqueueBootWork(ctx, nextAttempt, expedited = false)
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
         * WorkManager leg of the retry chain. [expedited] is only for
         * the immediate boot-deferral attempt (no delay — expedited
         * requests cannot carry one): it runs ASAP post-boot, which is
         * what shrinks power-on→running from minutes to seconds.
         * Delayed retries ride regular one-time work. The worker itself
         * re-enters [runBootAttempt], so the MAX_RETRIES bound holds no
         * matter which leg fires.
         */
        internal fun enqueueBootWork(ctx: Context, nextAttempt: Int, expedited: Boolean) {
            try {
                val builder = androidx.work.OneTimeWorkRequestBuilder<SyncBootWorker>()
                    .setInputData(androidx.work.workDataOf(SyncBootWorker.KEY_ATTEMPT to nextAttempt))
                if (expedited) {
                    builder.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                } else {
                    builder.setInitialDelay(nextRetryDelayMs(nextAttempt), java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                androidx.work.WorkManager.getInstance(ctx.applicationContext)
                    .enqueueUniqueWork(BOOT_WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, builder.build())
            } catch (_: Exception) {
            }
        }

        /**
         * Actions routed to [runBootAttempt]: boot, unlock, app-update
         * replace (same path restarts sync + float after update) and
         * the internal retry alarm. Unit-testable without Robolectric
         * (action strings only, no Context needed).
         */
        fun handlesAction(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_RETRY

        /**
         * Backoff for retry N: attempt 0 fires FAST (15s — the boot
         * path parks here, so power-on→running shrinks from minutes to
         * seconds), then 1m, 2m, 4m, 8m, capped at 15m. Delegates to
         * [HardeningGuards] for JVM tests.
         */
        fun nextRetryDelayMs(retryNumber: Int): Long =
            if (retryNumber <= 0) BOOT_FAST_RETRY_MS else HardeningGuards.bootRetryDelayMs(retryNumber)

        /**
         * True when the failure chain smells like a locked credential
         * keystore. Delegates to [HardeningGuards] for JVM tests.
         */
        fun isKeystoreLocked(e: Throwable): Boolean =
            HardeningGuards.isKeystoreLocked(e)
    }
}

/**
 * Expedited boot worker (same file as the receiver by scope): runs the
 * boot attempt as a JobScheduler job instead of an FGS start, which is
 * allowed while backgrounded. Never foregrounds the service itself —
 * [BootReceiver.runBootAttempt] owns the start-or-defer decision, and
 * the attempt counter in the input data bounds the chain (attempt >=
 * MAX_RETRIES pins FAILURE, never an infinite WorkManager retry).
 */
class SyncBootWorker(
    ctx: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_ATTEMPT = "boot_attempt"
    }

    override suspend fun doWork(): Result {
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        if (attempt >= BootReceiver.MAX_RETRIES) {
            try {
                BootReceiver.clearSyncBootPending(applicationContext)
            } catch (_: Exception) {
            }
            return Result.failure()
        }
        return try {
            BootReceiver.runBootAttempt(applicationContext, attempt, isBoot = false)
            Result.success()
        } catch (e: Exception) {
            // runBootAttempt already swallows its own failures into the
            // bounded chain; this is belt-and-braces for anything that
            // escapes (e.g. a Worker-lifecycle throw): transient causes
            // re-enter the shared chain, anything else fails fast.
            if (BootPolicy.shouldRetryBootStart(e, attempt, BootReceiver.MAX_RETRIES)) {
                try {
                    BootReceiver.scheduleRetry(applicationContext, attempt + 1)
                } catch (_: Exception) {
                }
                Result.success()
            } else {
                Result.failure()
            }
        }
    }
}
