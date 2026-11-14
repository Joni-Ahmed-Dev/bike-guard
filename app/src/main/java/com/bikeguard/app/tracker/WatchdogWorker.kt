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
import com.bikeguard.app.data.isoToMs

/** Runs about every 15 minutes: uploads the queue and revives tracking if Android killed it. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.bikeApp
        app.sync.flush()
        // Heartbeat to the diagnostics Worker, so Muse can see the tracker
        // is alive even when Supabase sync is failing.
        app.diag.report("heartbeat")
        // Remote alerts without FCM: (re)start the app-level Realtime listener
        // (covers a sign-in or role pick that happened after app start) and poll
        // the events table, so alerts still arrive when the process was killed.
        app.startAlertListener()
        if (app.prefs.isLoggedIn) checkNewEvents(app)
        // A stopped tracker never polls Remote commands or settings, so a Remote arm or
        // tracking_start would otherwise never arrive (audit #1). Revive it from the server:
        // a fresh start/arm command, or a guardArmed settings row.
        if (app.prefs.isTracker && app.prefs.isLoggedIn && !TrackerState.running.value && Perms.hasFine(app)) {
            val revive = runCatching {
                val cmds = app.api.pendingCommands(app.prefs.deviceId).orEmpty()
                val freshStart = cmds.any { c ->
                    (c.type == "tracking_start" || c.type == "arm") &&
                        System.currentTimeMillis() - isoToMs(c.createdAt.orEmpty()) < 10 * 60_000L
                }
                val guardArmed = runCatching { app.api.getDeviceSettings(app.prefs.deviceId)?.guardArmed }.getOrNull() == true
                freshStart || guardArmed
            }.getOrNull() == true
            if (revive && !app.tryStartTracking()) notifyResume(app)
        }
        if (app.prefs.shouldTrack && !TrackerState.running.value && Perms.hasFine(app)) {
            if (!app.tryStartTracking()) notifyResume(app)
        }
        return Result.success()
    }

    /** Background delivery for the Remote phone: poll the events table and notify for
     * rows newer than the watermark. The first run only seeds the watermark, so old
     * history never buzzes. Notifier dedupes against the same watermark, so a Realtime
     * alert and this poll can never double-notify. Runs at most every ~15 min
     * (WorkManager's minimum periodic interval); the tracker phone skips it, since
     * its own events are filtered out by Notifier anyway. */
    private suspend fun checkNewEvents(app: BikeGuardApp) {
        if (app.prefs.isTracker) return
        val list = runCatching { app.api.listEvents(20) }.getOrNull() ?: return
        if (list.isEmpty()) return
        val newestId = list.mapNotNull { it.id }.maxOrNull()
        if (app.prefs.lastNotifiedEventId == 0L) {
            if (newestId != null) app.prefs.lastNotifiedEventId = newestId
            app.prefs.lastNotifiedEventMs = System.currentTimeMillis()
            return
        }
        // Newest first from the server; notify oldest first so the newest ends up on top.
        list.asReversed().forEach { Notifier.notifyEvent(applicationContext, it) }
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
