package com.bikeguard.app.tracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.data.PendingLocation
import com.bikeguard.app.data.Perms
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service: GPS fix -> Room queue -> upload.
 * Every fix is written to the local queue FIRST, so nothing is lost when the network drops.
 */
class TrackingService : Service() {
    private val app get() = applicationContext as BikeGuardApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updating = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (signal in flushSignal) {
                val ok = app.sync.flush()
                TrackerState.online.value = ok
                if (!ok) delay(10_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground immediately after startForegroundService().
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent means Android restarted us after killing the process.
        if (!Perms.hasFine(this) || (intent == null && !app.prefs.shouldTrack)) {
            stopSelf()
            return START_NOT_STICKY
        }
        beginUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun beginUpdates() {
        if (updating) client.removeLocationUpdates(callback) // re-request with a new interval
        val ms = app.prefs.intervalSec.coerceIn(3, 60) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms / 2)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        updating = true
        TrackerState.running.value = true
        flushSignal.trySend(Unit) // upload anything left over from before
    }

    private fun onLocation(loc: Location) {
        val (battery, charging) = batteryInfo()
        TrackerState.lastFix.value = FixInfo(
            loc.latitude, loc.longitude,
            if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
            if (loc.hasAccuracy()) loc.accuracy else 0f,
            loc.time
        )
        scope.launch {
            val dao = app.db.pending()
            dao.insert(
                PendingLocation(
                    lat = loc.latitude, lng = loc.longitude,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                    bearing = if (loc.hasBearing()) loc.bearing else null,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                    battery = battery, charging = charging, recordedAt = loc.time
                )
            )
            dao.trim(MAX_QUEUE)
            flushSignal.trySend(Unit)
        }
    }

    private fun batteryInfo(): Pair<Int?, Boolean?> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        return pct to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, BikeGuardApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        TrackerState.running.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE = 50_000

        /** Safe to call repeatedly; also re-applies a changed interval. Throws if Android forbids a background start. */
        fun start(ctx: Context) {
            if (!Perms.hasFine(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }
}

/** Convenience used by receivers/workers that must never crash on a refused background start. */
fun Context.tryStartTracking(): Boolean = try {
    TrackingService.start(this); true
} catch (e: Exception) { false }

