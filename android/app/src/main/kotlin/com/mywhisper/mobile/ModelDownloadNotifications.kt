package com.mywhisper.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager

/**
 * Own notification channel + builders for background model downloads.
 *
 * Separate from the unified `mw-status` row on purpose (owned-scope
 * rule): the bubble/sync foreground services keep their FGS semantics
 * untouched; downloads paint on `mw-downloads` with per-model progress
 * + a Cancel action. Completion clears the row; the model cards pick
 * up PRESENT/MISMATCH from the existing SHA-verified pack derivation
 * on the next recomposition/resume (no separate state bus).
 */
object ModelDownloadNotifications {
    const val CHANNEL = "mw-downloads"
    const val EXTRA_WORK_NAME = "mw_dl_work_name"
    const val EXTRA_KIND = "mw_dl_kind"
    const val EXTRA_MODEL_ID = "mw_dl_model_id"

    fun ensureChannel(ctx: Context) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    ctx.getString(R.string.download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        } catch (_: Exception) {
        }
    }

    fun cancelIntent(
        ctx: Context,
        workName: String,
        notifId: Int,
        kind: String = "",
        modelId: String = "",
    ): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            notifId,
            Intent(ctx, ModelDownloadCancelReceiver::class.java)
                .putExtra(EXTRA_WORK_NAME, workName)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_MODEL_ID, modelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun openIntent(ctx: Context, target: String = ModelDownloadSchedule.TARGET_STT): PendingIntent =
        PendingIntent.getActivity(
            ctx, target.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(ModelDownloadSchedule.EXTRA_TARGET, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Determinate when [totalBytes] is known, indeterminate otherwise. */
    fun buildProgress(
        ctx: Context,
        label: String,
        receivedBytes: Long,
        totalBytes: Long?,
        workName: String,
        notifId: Int,
        target: String = ModelDownloadSchedule.TARGET_STT,
        kind: String = "",
        modelId: String = "",
    ): Notification {
        val pct = ModelDownloadSchedule.progressPercent(receivedBytes, totalBytes)
        val text = if (totalBytes != null && totalBytes > 0) {
            ctx.getString(
                R.string.local_downloading_mb,
                label,
                receivedBytes / 1_000_000,
                totalBytes / 1_000_000,
            )
        } else {
            ctx.getString(R.string.local_downloading, label)
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(ctx.getString(R.string.download_title, label))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent(ctx, target))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                ctx.getString(R.string.download_cancel),
                cancelIntent(ctx, workName, notifId, kind, modelId),
            )
        if (pct != null) b.setProgress(100, pct, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    fun buildComplete(
        ctx: Context,
        label: String,
        target: String = ModelDownloadSchedule.TARGET_STT,
    ): Notification =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(ctx.getString(R.string.download_complete_title))
            .setContentText(ctx.getString(R.string.local_downloaded, label))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent(ctx, target))
            .setAutoCancel(true)
            .build()

    fun buildFailed(
        ctx: Context,
        label: String,
        detail: String,
        target: String = ModelDownloadSchedule.TARGET_STT,
    ): Notification =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(ctx.getString(R.string.download_failed_title))
            .setContentText(ctx.getString(R.string.local_download_failed, detail.ifBlank { label }))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent(ctx, target))
            .setAutoCancel(true)
            .build()

    fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: Exception) {
        }
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
        } catch (_: Exception) {
        }
    }
}

/**
 * Notification Cancel action: routes through
 * [ModelDownloadManager.cancel] (cancels the unique work AND wipes
 * its partials, so cancel truly resets). Kind/id ride explicit
 * intent extras; the work-name parse below is only a fallback for
 * notifications posted by older builds (lossy on dotted ids).
 */
class ModelDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra(ModelDownloadNotifications.EXTRA_KIND).orEmpty()
        val id = intent.getStringExtra(ModelDownloadNotifications.EXTRA_MODEL_ID).orEmpty()
        if ((kind == ModelDownloadSchedule.KIND_STT || kind == ModelDownloadSchedule.KIND_LLM) &&
            id.isNotEmpty()
        ) {
            try {
                ModelDownloadManager.cancel(ctx, kind, id)
            } catch (_: Exception) {
            }
            return
        }
        val workName = intent.getStringExtra(ModelDownloadNotifications.EXTRA_WORK_NAME)
            ?: return
        val parsed = ModelDownloadSchedule.kindIdFromWorkName(workName)
        if (parsed != null) {
            try {
                ModelDownloadManager.cancel(ctx, parsed.first, parsed.second)
            } catch (_: Exception) {
            }
            return
        }
        try {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName)
        } catch (_: Exception) {
        }
        try {
            ModelDownloadNotifications.cancel(
                ctx,
                ModelDownloadSchedule.notifIdFor(workName),
            )
        } catch (_: Exception) {
        }
    }
}
