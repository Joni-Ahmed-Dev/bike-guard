package com.bikeguard.app.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.EventDto
import com.bikeguard.app.data.Perms
import com.bikeguard.app.data.eventTitle

object Notifier {
    /** Alert on the alerts channel (high importance, custom alert ringtone). Existing callers unchanged. */
    fun local(ctx: Context, id: Int, title: String, text: String?) {
        createChannels(ctx)
        post(ctx, BikeGuardApp.CHANNEL_ALERTS, id, title, text, NotificationCompat.PRIORITY_HIGH)
    }

    /** General notification on the general channel (default importance, its own ringtone). */
    fun general(ctx: Context, id: Int, title: String, text: String?) {
        createChannels(ctx)
        if (!Perms.hasNotifications(ctx)) return
        post(ctx, BikeGuardApp.CHANNEL_GENERAL, id, title, text, NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String?, priority: Int) {
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setPriority(priority).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /**
     * Post a real system notification for one server event row (Remote / invite phone).
     * Returns false when skipped: not logged in, the tracker phone (its own events are
     * not news for the person holding it), an event id already notified, an event the
     * user already saw in the Alerts tab, or notifications not granted.
     */
    fun notifyEvent(ctx: Context, ev: EventDto): Boolean {
        val prefs = ctx.bikeApp.prefs
        if (!prefs.isLoggedIn || prefs.isTracker) return false
        if (ev.deviceId == prefs.deviceId) return false
        val id = ev.id
        if (id != null && id <= prefs.lastNotifiedEventId) return false
        if (ev.createdMs > 0 && ev.createdMs <= prefs.lastAlertsSeenMs) return false
        if (!Perms.hasNotifications(ctx)) return false
        local(ctx, ("ev" + (id ?: ev.createdMs)).hashCode(), eventTitle(ctx, ev.type, ev.data?.toString()), null)
        if (id != null && id > prefs.lastNotifiedEventId) prefs.lastNotifiedEventId = id
        prefs.lastNotifiedEventMs = System.currentTimeMillis()
        return true
    }

    // ---- channels (issue #8: one channel per ringtone) ----

    /** Creates any missing channel; never overwrites an existing one, so a ringtone the
     * user changed in the system settings is left alone. Callers that change a ringtone
     * use setAlertRingtone / setGeneralRingtone instead, which recreate that channel. */
    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(BikeGuardApp.CHANNEL_TRACKING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(BikeGuardApp.CHANNEL_TRACKING, ctx.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
            )
        }
        ensureChannel(ctx, BikeGuardApp.CHANNEL_ALERTS, R.string.channel_alerts, NotificationManager.IMPORTANCE_HIGH, ctx.bikeApp.prefs.alertRingtoneUri)
        ensureChannel(ctx, BikeGuardApp.CHANNEL_GENERAL, R.string.channel_general, NotificationManager.IMPORTANCE_DEFAULT, ctx.bikeApp.prefs.generalRingtoneUri)
    }

    private fun ensureChannel(ctx: Context, id: String, nameRes: Int, importance: Int, ringtoneUri: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(buildChannel(ctx, id, nameRes, importance, ringtoneUri))
        }
    }

    private fun buildChannel(ctx: Context, id: String, nameRes: Int, importance: Int, ringtoneUri: String?): NotificationChannel {
        val ch = NotificationChannel(id, ctx.getString(nameRes), importance)
        val uri = ringtoneUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null) {
            ch.setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        return ch
    }

    /**
     * Android only applies a channel's sound at creation time, so changing the ringtone
     * means deleting the channel and creating it again with the new sound.
     */
    private fun recreateChannel(ctx: Context, id: String, nameRes: Int, importance: Int, ringtoneUri: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteChannel(id)
        nm.createNotificationChannel(buildChannel(ctx, id, nameRes, importance, ringtoneUri))
    }

    fun setAlertRingtone(ctx: Context, uriString: String) {
        ctx.bikeApp.prefs.alertRingtoneUri = uriString
        recreateChannel(ctx, BikeGuardApp.CHANNEL_ALERTS, R.string.channel_alerts, NotificationManager.IMPORTANCE_HIGH, uriString)
    }

    fun setGeneralRingtone(ctx: Context, uriString: String) {
        ctx.bikeApp.prefs.generalRingtoneUri = uriString
        recreateChannel(ctx, BikeGuardApp.CHANNEL_GENERAL, R.string.channel_general, NotificationManager.IMPORTANCE_DEFAULT, uriString)
    }

    // ---- system ringtone picker (issue #8) ----

    fun ringtonePickerIntent(ctx: Context, currentUri: String?, title: String): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                currentUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() },
            )
        }

    /** Null when the picker was cancelled. */
    @Suppress("DEPRECATION")
    fun pickedRingtoneUri(data: Intent?): Uri? =
        data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

    fun ringtoneName(ctx: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) return ctx.getString(R.string.ringtone_default)
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return ctx.getString(R.string.ringtone_default)
        return runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            ?: uri.lastPathSegment ?: uriString
    }

    /** From a pushed FCM data message (Viewer phone). */
    fun event(ctx: Context, d: Map<String, String>) {
        val type = d["type"] ?: return
        local(ctx, (type + d["device_id"] + System.currentTimeMillis()).hashCode(), eventTitle(ctx, type, d["data"]), d["device_name"])
    }
}
