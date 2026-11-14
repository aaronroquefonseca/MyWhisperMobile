package com.mywhisper.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** User can hide idle service status without losing explicit floating-button controls. */
internal object AppNotificationChannels {
    const val FLOAT_CONTROLS = "mw-float-controls"
    const val SNOOZE_NOTIFICATION_ID = 45

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(NotifPolicy.UNIFIED_CHANNEL, context.getString(R.string.unified_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.ux_background_status_help)
            },
            NotificationChannel(FLOAT_CONTROLS, context.getString(R.string.ux_float_controls_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.ux_float_controls_help)
            },
        ))
    }
}
