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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 60-second "I'm OK" countdown after a suspected crash, then an SMS with the location to the emergency contact. */
object SosController {
    private const val NOTIF_ID = 1003
    const val ACTION_CANCEL = "com.bikeguard.app.SOS_CANCEL"
    private var job: Job? = null

    fun startCountdown(app: BikeGuardApp, lat: Double?, lng: Double?) {
        if (!app.prefs.sosSms || app.prefs.emergencyNumber.isBlank()) return
        cancel(app)
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
        job = app.appScope.launch {
            delay(60_000)
            sendNow(app, lat, lng)
            app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    fun cancel(ctx: Context) {
        job?.cancel(); job = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun sendNow(app: BikeGuardApp, lat: Double?, lng: Double?) {
        val number = app.prefs.emergencyNumber
        if (!app.prefs.sosSms || number.isBlank()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val link = if (lat != null && lng != null) "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=17/$lat/$lng" else "-"
        runCatching {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendTextMessage(number, null, app.getString(R.string.sos_sms_text, link), null, null)
        }
    }
}

class SosCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SosController.ACTION_CANCEL) SosController.cancel(context.bikeApp)
    }
}
