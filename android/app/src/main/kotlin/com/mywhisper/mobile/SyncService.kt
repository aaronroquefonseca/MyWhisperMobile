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
        private const val CHANNEL = "mw-sync"
        private const val NOTIF_ID = 41

        fun start(ctx: Context) {
            val i = Intent(ctx, SyncService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }

        fun syncNow(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_SYNC_NOW))
        }
    }

    private val workers = Executors.newSingleThreadExecutor()

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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                workers.execute {
                    try {
                        CoreBridge.syncAllNow()
                    } catch (_: Exception) {
                    }
                }
                return START_STICKY
            }
            else -> {
                startForegroundWithType()
                workers.execute {
                    try {
                        CoreBridge.syncStart()
                    } catch (_: Exception) {
                    }
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundWithType() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_sync_title))
            .setContentText(getString(R.string.notif_sync_text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
            .build()
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
        try {
            CoreBridge.syncStop()
        } catch (_: Exception) {
        }
        workers.shutdownNow()
        super.onDestroy()
    }
}

/**
 * Restart sync after reboot when it is enabled (desktop starts its
 * engine from systemd; this is the mobile equivalent).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val s = CoreBridge.getSettings().getOrThrow()
            val sync = s.optJSONObject("sync")
            if (sync?.optBoolean("enabled", true) == true &&
                sync.optBoolean("start_on_boot", true)
            ) {
                SyncService.start(ctx.applicationContext)
            }
        } catch (_: Exception) {
        }
    }
}
