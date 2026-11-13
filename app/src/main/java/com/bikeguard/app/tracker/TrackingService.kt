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
import com.bikeguard.app.data.DeviceSettingsDto
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.PendingLocation
import com.bikeguard.app.data.Perms
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.tracker.AutoMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime
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
    private var lastSettingsSeenMs = 0L

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
                try {
                    val ok = app.sync.flush()
                    TrackerState.online.value = ok
                    if (!ok) delay(10_000)
                } catch (e: Exception) {
                    // One bad exception (a crash in the queue, a bad row) must never kill uploads for good.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val reason = when (e) {
                        is java.net.SocketTimeoutException -> "timed out"
                        is IOException -> "no internet"
                        else -> "sync error"
                    }
                    val prevError = app.prefs.lastSyncError
                    app.prefs.lastSyncError = reason
                    app.prefs.lastSyncAttemptMs = System.currentTimeMillis()
                    TrackerState.online.value = false
                    if (reason != prevError) {
                        scope.launch { app.diag.report("flush loop error: $reason") }
                    }
                    delay(10_000)
                }
            }
        }
        // Remote commands: poll every 15 s, or at once when a push wakes us.
        scope.launch {
            while (isActive) {
                try { runCommands() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                withTimeoutOrNull(15_000) { pollSignal.receive() }
            }
        }
        // Server-synced settings + health watch: every 30 s (tracker role only).
        scope.launch {
            while (isActive) {
                try { syncSettings() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                delay(30_000)
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
            "arm" -> { GuardControl.setArmed(this, true); pushSettingsToServer() }
            "disarm" -> { GuardControl.setArmed(this, false); SirenPlayer.stop(this); pushSettingsToServer() }
            "set_speed_limit" -> { app.prefs.speedLimitKmh = c.arg?.toIntOrNull() ?: 0; app.prefs.stateDirty = true; pushSettingsToServer() }
            "cancel_sos" -> SosController.cancel(this)
            "mark_serviced" -> app.prefs.markServiced()
        }
        flushSignal.trySend(Unit)
    }

    // ---------- server-synced settings (v1.1.0) ----------

    /** Pulls the Remote phone's settings row and applies it, then confirms with applied_at. */
    private suspend fun syncSettings() {
        checkHealth()
        checkGuardSchedule()
        val p = app.prefs
        if (!p.isLoggedIn || p.isRemote) return
        val s = try { app.api.getDeviceSettings(p.deviceId) } catch (e: IOException) { null } ?: return
        val updatedMs = isoToMs(s.updatedAt.orEmpty())
        if (updatedMs <= lastSettingsSeenMs && updatedMs != 0L) return
        if (updatedMs > 0L) lastSettingsSeenMs = updatedMs
        applySettings(s)
        try { app.api.markSettingsApplied(p.deviceId) } catch (e: IOException) { /* retried next poll */ }
    }

    private fun applySettings(s: DeviceSettingsDto) {
        val p = app.prefs
        if (s.mode in listOf("manual", "auto", "always") && s.mode != p.mode) {
            p.mode = s.mode
            if (s.mode == MODE_AUTO) AutoMode.enable(app) else AutoMode.disable(app)
            if (s.mode == MODE_ALWAYS) tryStartTracking()
            else p.trackingActive = TrackerState.running.value
        }
        if (s.intervalSec in 3..60 && s.intervalSec != p.intervalSec) {
            p.intervalSec = s.intervalSec
            reevaluateInterval()
        }
        if (s.speedLimitKmh != p.speedLimitKmh) { p.speedLimitKmh = s.speedLimitKmh; p.stateDirty = true }
        if (s.guardArmed != p.parkingArmed) GuardControl.setArmed(this, s.guardArmed)
        if (s.autoArm != p.autoArm) p.autoArm = s.autoArm
        if (s.sirenOnAlert != p.sirenOnAlert) p.sirenOnAlert = s.sirenOnAlert
        if (s.sosSms != p.sosSms) p.sosSms = s.sosSms
        if (s.crashEnabled != p.crashDetection) p.crashDetection = s.crashEnabled
        if (s.sosNumber != p.emergencyNumber) p.emergencyNumber = s.sosNumber
        if (s.serviceKm != p.serviceIntervalKm) p.scheduleService(s.serviceKm)
        p.guardScheduleStart = s.guardScheduleStart ?: -1
        p.guardScheduleEnd = s.guardScheduleEnd ?: -1
    }

    /** Mirrors this phone's state back to the server after a remote command, without bumping updated_at. */
    private fun pushSettingsToServer() {
        val p = app.prefs
        scope.launch {
            // No settings row seen from the server yet: there is nothing to mirror, and an
            // explicit-null schedule here would wipe a schedule the Remote phone set.
            if (lastSettingsSeenMs == 0L) {
                runCatching { app.api.markSettingsApplied(p.deviceId) }
                return@launch
            }
            val dto = DeviceSettingsDto(
                deviceId = p.deviceId, mode = p.mode, intervalSec = p.intervalSec,
                speedLimitKmh = p.speedLimitKmh, guardArmed = p.parkingArmed,
                autoArm = p.autoArm,
                sirenOnAlert = p.sirenOnAlert, sosSms = p.sosSms,
                crashEnabled = p.crashDetection, sosNumber = p.emergencyNumber,
                serviceKm = p.serviceIntervalKm,
                guardScheduleStart = p.guardScheduleStart.takeIf { it in 0..23 },
                guardScheduleEnd = p.guardScheduleEnd.takeIf { it in 0..23 },
            )
            try {
                if (app.api.saveDeviceSettings(dto, touchUpdated = false)) {
                    app.api.markSettingsApplied(p.deviceId)
                }
            } catch (e: IOException) { /* retried on the next settings poll */ }
        }
    }

    /** Guard schedule: arm inside the window, disarm when it ends (only if the schedule armed it). */
    private fun checkGuardSchedule() {
        val p = app.prefs
        val start = p.guardScheduleStart
        val end = p.guardScheduleEnd
        if (start !in 0..23 || end !in 0..23 || start == end) return
        val hour = LocalTime.now().hour
        val inside = if (start < end) hour in start until end else hour >= start || hour < end
        // After a manual disarm the guard stays off for 10 minutes, even inside the window.
        val latched = System.currentTimeMillis() - p.manualDisarmAtMs < DISARM_LATCH_MS
        if (inside && !p.parkingArmed && !latched) { GuardControl.setArmed(this, true); p.scheduleArmed = true }
        else if (!inside && p.scheduleArmed) { GuardControl.setArmed(this, false, latch = false); p.scheduleArmed = false }
    }

    /** Raises one health_warning event when the tracker's health changes for the worse. */
    private fun checkHealth() {
        val fine = Perms.hasFine(this)
        val battOk = Perms.ignoringBatteryOptimizations(this)
        val key = "fine=$fine|batt=$battOk"
        if (key == app.prefs.lastHealthState) return
        app.prefs.lastHealthState = key
        if (!fine || !battOk) {
            GuardControl.raise(
                this, "health_warning", "warning",
                buildJsonObject { put("location", fine); put("battery_ok", battOk) },
            )
        }
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
        /** After a manual disarm, auto re-arm (and the guard schedule) waits this long. */
        const val DISARM_LATCH_MS = 10 * 60_000L
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
