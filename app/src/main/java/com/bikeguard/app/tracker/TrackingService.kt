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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.data.CommandDto
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.PendingLocation
import com.bikeguard.app.data.Perms
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.widget.TrackerWidget
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Foreground service: GPS fix -> Room queue -> upload, plus the anti-theft [Guard] and remote commands.
 * Every fix is written to the local queue FIRST, so nothing is lost when the network drops.
 */
class TrackingService : Service() {
    private val app get() = applicationContext as BikeGuardApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val pollSignal = Channel<Unit>(Channel.CONFLATED)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var guard: Guard

    private var updating = false
    private var currentIntervalMs = 0L
    private var lastMoveAt = 0L
    private var prevFix: Location? = null
    private var autoStopping = false
    private var lastBattery: Int? = null
    private var lastCharging: Boolean? = null
    private var lastStationaryFor = 0L
    private var lastWidgetAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        guard = Guard(this, app)
        guard.start()
        scope.launch {
            for (signal in flushSignal) {
                val ok = app.sync.flush()
                TrackerState.online.value = ok
                if (!ok) delay(10_000)
            }
        }
        // Remote commands: poll every 15 s, or at once when a push wakes us.
        scope.launch {
            while (isActive) {
                try { runCommands() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                withTimeoutOrNull(15_000) { pollSignal.receive() }
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
        if (intent == null) GuardControl.raise(this, "tracker_restarted", "warning")
        if (intent?.action == ACTION_POLL && updating) {
            pollSignal.trySend(Unit)
            return START_STICKY
        }
        beginUpdates()
        pollSignal.trySend(Unit)
        return START_STICKY
    }

    private fun beginUpdates() {
        lastMoveAt = SystemClock.elapsedRealtime()
        prevFix = null
        autoStopping = false
        lastStationaryFor = 0L
        TrackerState.parked.value = false
        requestUpdates(app.prefs.intervalSec.coerceIn(3, 60) * 1000L)
        TrackerState.running.value = true
        TrackerWidget.update(this)
        flushSignal.trySend(Unit) // upload anything left over from before
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(ms: Long) {
        if (updating) client.removeLocationUpdates(callback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms / 2)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        currentIntervalMs = ms
        updating = true
    }

    /** Live mode (from the Viewer, or after a theft alert) overrides the parked/low-battery slow-down. */
    private fun reevaluateInterval() {
        if (!updating) return
        val base = app.prefs.intervalSec.coerceIn(3, 60) * 1000L
        val wanted = if (guard.isLive()) base
        else AdaptivePolicy.intervalMs(app.prefs.intervalSec, lastStationaryFor, lastBattery, lastCharging)
        if (wanted != currentIntervalMs) requestUpdates(wanted)
    }

    private fun onLocation(loc: Location) {
        val (battery, charging) = batteryInfo()

        // Speed: trust the GPS value, otherwise derive it from the previous fix.
        val prev = prevFix
        val speedMs = when {
            loc.hasSpeed() -> loc.speed
            prev != null -> loc.distanceTo(prev) / ((loc.time - prev.time) / 1000f).coerceAtLeast(1f)
            else -> 0f
        }
        prevFix = loc

        val now = SystemClock.elapsedRealtime()
        if (speedMs >= AdaptivePolicy.MOVING_SPEED_MS) lastMoveAt = now
        val stationaryFor = now - lastMoveAt
        lastStationaryFor = stationaryFor
        lastBattery = battery
        lastCharging = charging
        TrackerState.parked.value = stationaryFor >= AdaptivePolicy.PARKED_AFTER_MS
        reevaluateInterval()

        TrackerState.lastFix.value = FixInfo(
            loc.latitude, loc.longitude, speedMs * 3.6f,
            if (loc.hasAccuracy()) loc.accuracy else 0f, loc.time
        )
        guard.onFix(loc, speedMs, battery, stationaryFor)

        scope.launch {
            val dao = app.db.pending()
            dao.insert(
                PendingLocation(
                    lat = loc.latitude, lng = loc.longitude,
                    speed = speedMs,
                    bearing = if (loc.hasBearing()) loc.bearing else null,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                    battery = battery, charging = charging, recordedAt = loc.time
                )
            )
            dao.trim(MAX_QUEUE)
            flushSignal.trySend(Unit)
        }

        if (now - lastWidgetAt > 30_000) { lastWidgetAt = now; TrackerWidget.update(this) }

        val p = app.prefs
        if (p.mode == MODE_AUTO && stationaryFor >= AdaptivePolicy.AUTO_STOP_AFTER_MS && !p.parkingArmed && !guard.isLive()) autoStop()
    }

    /** Auto mode: parked long enough. Upload what is queued, then stop. The next "in vehicle" event restarts us. */
    private fun autoStop() {
        if (autoStopping) return
        autoStopping = true
        app.prefs.trackingActive = false
        scope.launch {
            withTimeoutOrNull(8_000) { app.sync.flush() }
            stopSelf()
        }
    }

    // ---------- remote commands ----------

    private suspend fun runCommands() {
        if (!app.prefs.isLoggedIn) return
        val cmds = try { app.api.pendingCommands(app.prefs.deviceId) } catch (e: IOException) { null } ?: return
        for (c in cmds) {
            val id = c.id ?: continue
            val fresh = System.currentTimeMillis() - isoToMs(c.createdAt.orEmpty()) < 10 * 60_000L
            if (fresh) execute(c)
            try { app.api.ackCommand(id, if (fresh) "done" else "expired") } catch (e: IOException) { /* retried next poll */ }
        }
    }

    private fun execute(c: CommandDto) {
        when (c.type) {
            "locate" -> locateNow()
            "live" -> { guard.goLive(15); reevaluateInterval() }
            "siren_on" -> SirenPlayer.start(this, 60)
            "siren_off" -> SirenPlayer.stop(this)
            "arm" -> GuardControl.setArmed(this, true)
            "disarm" -> { GuardControl.setArmed(this, false); SirenPlayer.stop(this) }
            "set_speed_limit" -> { app.prefs.speedLimitKmh = c.arg?.toIntOrNull() ?: 0; app.prefs.stateDirty = true }
            "cancel_sos" -> SosController.cancel(this)
            "mark_serviced" -> app.prefs.markServiced()
        }
        flushSignal.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun locateNow() {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc -> if (loc != null) onLocation(loc) }
    }

    // ---------- plumbing ----------

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
        guard.stop()
        SirenPlayer.stop(this)
        scope.cancel()
        TrackerState.running.value = false
        TrackerState.parked.value = false
        TrackerWidget.update(this)
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE = 50_000
        const val ACTION_POLL = "com.bikeguard.app.POLL_COMMANDS"

        /** Safe to call repeatedly; also re-applies a changed interval. Throws if Android forbids a background start. */
        fun start(ctx: Context) {
            if (!Perms.hasFine(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        }

        /** Ask a running (or newly started) service to check for remote commands right now. */
        fun pollCommands(ctx: Context) {
            if (!Perms.hasFine(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_POLL))
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
