package com.bikeguard.app.tracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.data.eventTitle

object Notifier {
    fun local(ctx: Context, id: Int, title: String, text: String?) {
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, BikeGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** From a pushed FCM data message (Viewer phone). */
    fun event(ctx: Context, d: Map<String, String>) {
        val type = d["type"] ?: return
        local(ctx, (type + d["device_id"] + System.currentTimeMillis()).hashCode(), eventTitle(ctx, type, d["data"]), d["device_name"])
    }
}
