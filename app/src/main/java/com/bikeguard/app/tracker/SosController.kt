package com.bikeguard.app.tracker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 60-second "I'm OK" countdown after a suspected crash, then an SMS with the
 * location to the emergency contact.
 *
 * The countdown used to live only in an appScope coroutine (v1.2.19, review
 * item #21): if the process died during the 60 s, the SMS never went. The
 * deadline and location are now persisted in Prefs when the countdown starts
 * and cleared when the SMS is sent or the countdown is cancelled, so
 * [resumePending] can re-arm it (or send at once, if the deadline already
 * passed) on the next app / service / Watchdog start. The send itself is
 * retried a few times and a failure is surfaced as an alert event + diag
 * report instead of being swallowed by runCatching.
 */
object SosController {
    private const val NOTIF_ID = 1003
    const val ACTION_CANCEL = "com.bikeguard.app.SOS_CANCEL"
    private const val COUNTDOWN_MS = 60_000L
    private const val SEND_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 10_000L
    private var job: Job? = null

    /**
     * An explicit SOS or a crash countdown needs SMS enabled and a number. When it
     * does not have them it used to return silently while the button still said
     * "sent" (audit M5). Now the failure is raised as a critical sos_failed alert
     * with the reason, so the Remote phone and Telegram show it at once.
     * [explicit] is true for a held SOS button or a Remote SOS.
     */
    private fun sosReady(app: BikeGuardApp, explicit: Boolean): Boolean {
        val prefs = app.prefs
        // A held SOS button always expects a text. A crash countdown with SMS
        // switched off on purpose stays quiet (the crash alert itself still
        // goes out); SMS on with no number is a mistake worth reporting.
        if (prefs.emergencyNumber.isBlank()) {
            if (explicit || prefs.sosSms) fail(app, "no emergency number")
            return false
        }
        if (!prefs.sosSms) {
            if (explicit) fail(app, "sms switched off")
            return false
        }
        return true
    }

    fun startCountdown(app: BikeGuardApp, lat: Double?, lng: Double?) {
        if (!sosReady(app, explicit = false)) return
        cancel(app)
        // Persist BEFORE the coroutine starts: a death at any point after
        // this line still leaves a pending SOS for resumePending to finish.
        app.prefs.sosPendingUntilMs = System.currentTimeMillis() + COUNTDOWN_MS
        app.prefs.sosPendingLat = lat?.toString().orEmpty()
        app.prefs.sosPendingLng = lng?.toString().orEmpty()
        postCountdownNotification(app)
        job = app.appScope.launch {
            try {
                delay(COUNTDOWN_MS)
                attemptSend(app, lat, lng)
            } finally {
                app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            }
        }
    }

    /** Re-arm a countdown that outlived the process (or send at once when the
     *  deadline already passed). Safe to call from every start path; a live
     *  countdown job is never doubled. Called from BikeGuardApp.onCreate,
     *  TrackingService.onCreate and WatchdogWorker, so a pending SOS is
     *  finished even when the service itself is not running. */
    fun resumePending(app: BikeGuardApp) {
        val until = app.prefs.sosPendingUntilMs
        if (until <= 0L || job?.isActive == true) return
        val lat = app.prefs.sosPendingLat.toDoubleOrNull()
        val lng = app.prefs.sosPendingLng.toDoubleOrNull()
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) {
            job = app.appScope.launch {
                try { attemptSend(app, lat, lng) }
                finally { app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
            }
            return
        }
        postCountdownNotification(app)
        job = app.appScope.launch {
            try {
                delay(remaining)
                attemptSend(app, lat, lng)
            } finally {
                app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            }
        }
    }

    fun cancel(ctx: Context) {
        job?.cancel(); job = null
        clearPending(ctx.bikeApp.prefs)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Immediate SOS (hold button, Remote command): any running countdown is
     *  dropped first, so the contact gets one SMS, not two. */
    fun sendNow(app: BikeGuardApp, lat: Double?, lng: Double?) {
        cancel(app)
        if (!sosReady(app, explicit = true)) return
        app.appScope.launch { attemptSend(app, lat, lng) }
    }

    /**
     * Sends the SOS SMS, retrying a few times on failure. Returns true when a
     * send went out. A failure is surfaced — a critical "sos_failed" alert
     * event (visible in-app and over Telegram) plus a diag report — instead of
     * being swallowed. The pending state is cleared on every outcome, so a
     * dead SMS is never retried hours later as a stale alarm; the owner sees
     * the failure at once and can act on it. Retries are bounded (3 attempts):
     * an SMS that cannot go out must not loop forever.
     */
    suspend fun attemptSend(app: BikeGuardApp, lat: Double?, lng: Double?): Boolean {
        val prefs = app.prefs
        val number = prefs.emergencyNumber
        // Turned off (or number cleared) after the countdown started: the
        // owner changed their mind, so there is nothing to send and nothing
        // to report.
        if (!prefs.sosSms || number.isBlank()) { clearPending(prefs); return false }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            clearPending(prefs)
            fail(app, "no sms permission")
            return false
        }
        val link = if (lat != null && lng != null) "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=17/$lat/$lng" else "-"
        val text = app.getString(R.string.sos_sms_text, link)
        repeat(SEND_ATTEMPTS) { attempt ->
            val ok = runCatching {
                @Suppress("DEPRECATION")
                val sms = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendTextMessage(number, null, text, null, null)
            }.isSuccess
            if (ok) { clearPending(prefs); return true }
            if (attempt < SEND_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        clearPending(prefs)
        fail(app, "send failed")
        return false
    }

    private fun fail(app: BikeGuardApp, reason: String) {
        GuardControl.raise(app, "sos_failed", "critical", buildJsonObject { put("reason", reason) })
        app.appScope.launch { app.diag.report("sos sms failed: $reason") }
    }

    private fun clearPending(prefs: Prefs) {
        prefs.sosPendingUntilMs = 0L
        prefs.sosPendingLat = ""
        prefs.sosPendingLng = ""
    }

    private fun postCountdownNotification(app: BikeGuardApp) {
        val cancelPi = PendingIntent.getBroadcast(
            app, 7, Intent(app, SosCancelReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(app, BikeGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(app.getString(R.string.sos_notif_title))
            .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .addAction(0, app.getString(R.string.sos_cancel), cancelPi).build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}

class SosCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SosController.ACTION_CANCEL) SosController.cancel(context.bikeApp)
    }
}
