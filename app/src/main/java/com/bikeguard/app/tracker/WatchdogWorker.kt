package com.bikeguard.app.tracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.Perms

/** Runs about every 15 minutes: uploads the queue and revives tracking if Android killed it. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.bikeApp
        app.sync.flush()
        if (app.prefs.shouldTrack && !TrackerState.running.value && Perms.hasFine(app)) {
            if (!app.tryStartTracking()) notifyResume(app)
        }
        return Result.success()
    }

    /** Android sometimes refuses to start a foreground service from the background; ask the user to tap instead. */
    private fun notifyResume(ctx: Context) {
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, BikeGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(ctx.getString(R.string.notif_resume_title))
            .setContentText(ctx.getString(R.string.notif_resume_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1002, n)
    }
}
