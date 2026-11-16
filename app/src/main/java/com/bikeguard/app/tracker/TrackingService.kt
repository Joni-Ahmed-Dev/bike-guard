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
import android.location.LocationManager
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
import com.bikeguard.app.data.AppLog
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
    /** Last fix accepted for upload: jitter is measured against this, never against a skipped fix. */
    private var anchorFix: Location? = null
    /** True while the fix in [TrackerState.lastFix] came from the last-known
     * seed below instead of a live GPS delivery: it is shown, never live. */
    private var seededFix = false
    /** Last time the last-known seed was attempted, wall-clock ms; throttles retries. */
    private var lastSeedAttemptMs = 0L
    /** When the last accepted (or heartbeat) fix was sent, elapsedRealtime ms. */
    private var lastAcceptedAtMs = 0L
    private var autoStopping = false
    private var lastBattery: Int? = null
    private var lastCharging: Boolean? = null
    private var lastStationaryFor = 0L
    private var lastWidgetAt = 0L
    private var lastSettingsSeenMs = 0L
    /** A one-shot locate fix is async: after a locate command with the master
     * off, the service must stay alive until the fix lands (or this grace
     * expires), or the end-of-batch stop below kills the upload (C3). */
    private var locateGraceUntilMs = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        guard = Guard(
            this, app,
            onWakeMotion = { wakeGpsForMotion() },
            onAutoArmed = { pushSettingsToServer(guardArmed = true) },
        )
        guard.start()
        // A 60 s SOS countdown that outlived the process (v1.2.19): re-arm it
        // here, or send at once when its deadline already passed.
        SosController.resumePending(app)
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
        // Fix-status watchdog (v1.2.2 issue 1): a fix is only "live" for ~30 s.
        // Without it the UI kept saying "Live and synced" on a fix from an
        // hour ago, or on no fix at all. While tracking runs, re-check every
        // 5 s so a stale fix decays to last-known / gps_off, and retry the
        // last-known seed while nothing has ever arrived.
        scope.launch {
            while (isActive) {
                try {
                    if (TrackerState.running.value) {
                        refreshFixStatus()
                        seedLastKnown()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                delay(5_000)
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
        // Parked with GPS off (v1.2.42): no fixes arrive, so the Dashboard
        // would decay to Delayed/Offline even though the tracker is alive
        // and guarding. Re-send the saved anchor every 10 min as a location
        // (speed 0, same spot: zero trip distance) so last_seen stays fresh.
        // No GPS is used: the coordinates come from the saved anchor.
        scope.launch {
            while (isActive) {
                delay(STATIONARY_HEARTBEAT_MS)
                try {
                    if (TrackerState.parked.value && app.prefs.parkingArmed && !updating) heartbeatParkedAnchor()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
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
            // Tracking is definitely off now (permission gone, or it was off
            // before the restart): drop a stale timer stamp. A restart that
            // resumes tracking never reaches this branch.
            app.prefs.trackingStartedAtMs = 0L
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) GuardControl.raise(this, "tracker_restarted", "warning")
        if (intent?.action == ACTION_POLL) {
            // Poll only, never start GPS: a push wake must not disturb
            // the parked and armed GPS off state, and a master off wake
            // must not start tracking. The poll loop runs the commands.
            pollSignal.trySend(Unit)
            return START_STICKY
        }
        if (!app.prefs.shouldTrack) {
            // Poll only, no GPS yet: stay alive in foreground so the
            // incoming command can run, and let execute decide.
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
        anchorFix = null
        lastAcceptedAtMs = 0L
        autoStopping = false
        lastStationaryFor = 0L
        TrackerState.parked.value = false
        // Timer (v1.2.2 issue 1): stamp only on the transition into tracking.
        // A restart or a reboot re-enters here with the stamp already set, so
        // the elapsed time survives both; only an explicit stop clears it.
        if (app.prefs.trackingStartedAtMs == 0L) {
            app.prefs.trackingStartedAtMs = System.currentTimeMillis()
        }
        requestUpdates(app.prefs.intervalSec.coerceIn(3, 60) * 1000L)
        TrackerState.running.value = true
        AppLog.i("tracking", "started")
        // Master switch ON (v1.2.40): live starts with the service, so a local
        // ON tap is live from the first fix, not only after a remote command.
        if (app.prefs.masterOn && !guard.isLive()) guard.goLive(15)
        // Show the last known position right away instead of dashes while the
        // first real fix is still coming; indoors that can take minutes.
        seedLastKnown(force = true)
        refreshFixStatus()
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

    /**
     * Single source of truth for [TrackerState.hasFix] / [TrackerState.fixStatus].
     * A fix is "live" only while it is a real (never seeded) fix at most
     * [FRESH_FIX_MS] old. Older than that it decays to last-known, and when
     * the provider is off the reason is gps_off, so the UI can say why
     * instead of showing dashes next to "Live and synced".
     */
    private fun refreshFixStatus() {
        val fix = TrackerState.lastFix.value
        val now = System.currentTimeMillis()
        val fresh = fix != null && !seededFix && now - fix.timeMs <= FRESH_FIX_MS
        if (fresh) {
            TrackerState.hasFix.value = true
            TrackerState.fixStatus.value = TrackerState.FIX_LIVE
        } else if (!isLocationProviderOn()) {
            TrackerState.hasFix.value = false
            TrackerState.fixStatus.value = TrackerState.FIX_GPS_OFF
        } else if (fix != null) {
            TrackerState.hasFix.value = false
            TrackerState.fixStatus.value = TrackerState.FIX_LAST_KNOWN
        } else {
            TrackerState.hasFix.value = false
            TrackerState.fixStatus.value = TrackerState.FIX_NO_FIX
        }
    }

    /** True when any location provider can deliver a fix. Fused Location can
     * fall back to the network provider, so GPS alone being off is not the
     * whole story; only both off means no fix can arrive. Fails open: an
     * unexpected error never blames the GPS without proof. */
    private fun isLocationProviderOn(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return true
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Last-known fallback (v1.2.2 issue 1): when tracking starts, and while
     * no fix has ever arrived, show the fused cached position instead of
     * dashes, with its real accuracy and age. It is only shown: it never
     * touches [anchorFix]/[prevFix], is never queued and never uploaded,
     * and a fresher real fix always replaces it. A seeded fix is never
     * "live" ([seededFix]), even if the cached point is only seconds old.
     * Retried at most every [SEED_RETRY_MS] unless [force] is set.
     */
    @SuppressLint("MissingPermission")
    private fun seedLastKnown(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSeedAttemptMs < SEED_RETRY_MS) return
        lastSeedAttemptMs = now
        if (!TrackerState.running.value || !Perms.hasFine(this)) return
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) return@addOnSuccessListener
            val current = TrackerState.lastFix.value
            // Never overwrite a fresher fix with an older cached point.
            if (current != null && loc.time <= current.timeMs) return@addOnSuccessListener
            seededFix = true
            TrackerState.lastFix.value = FixInfo(
                loc.latitude, loc.longitude,
                if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
                if (loc.hasAccuracy()) loc.accuracy else 0f, loc.time
            )
            refreshFixStatus()
        }
    }

    /** Live mode (from the Viewer, or after a theft alert) overrides the parked/low-battery slow-down. */
    private fun reevaluateInterval() {
        if (!updating) return
        // Master switch ON (v1.2.40) keeps live mode extended: the live window
        // is 15 minutes, so re-extend it on every re-evaluation while the
        // master is on, instead of letting it lapse mid-watch.
        if (app.prefs.masterOn && !guard.isLive()) guard.goLive(15)
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
        val p = app.prefs
        // Publish when the bike stopped (v1.2.42): the Remote Dashboard counts
        // down from here to the guard arming itself. The stamp is the moment
        // of the last movement, so it survives a service restart. Only the
        // transitions mark the state dirty, so a parked bike does not spam patches.
        if (speedMs >= AdaptivePolicy.MOVING_SPEED_MS) {
            if (p.parkedSinceMs != 0L) { p.parkedSinceMs = 0L; p.stateDirty = true }
        } else if (p.parkedSinceMs == 0L) {
            p.parkedSinceMs = System.currentTimeMillis() - stationaryFor
            p.stateDirty = true
        }
        reevaluateInterval()

        // Guard sees every raw fix, so tamper / movement detection is unaffected by the
        // jitter filter below: its own "moved" threshold still fires on a real displacement.
        guard.onFix(loc, speedMs, battery, stationaryFor)

        // Parked and armed (v1.2.42): the accelerometer watches the bike now,
        // so GPS can sleep until motion wakes it again. The 15 s command poll,
        // the flush loop and the 5 s watchdog are all delay-based coroutines,
        // so they keep running with GPS off.
        if (TrackerState.parked.value && p.parkingArmed && guard.hasAnchor && updating) {
            client.removeLocationUpdates(callback)
            updating = false
            currentIntervalMs = 0L
            AppLog.i("gps", "off: parked")
        }

        // GPS jitter while parked: never upload it, never move the displayed marker.
        if (isAcceptedFix(loc, speedMs, now)) {
            anchorFix = loc
            lastAcceptedAtMs = now
            seededFix = false
            TrackerState.lastFix.value = FixInfo(
                loc.latitude, loc.longitude, speedMs * 3.6f,
                if (loc.hasAccuracy()) loc.accuracy else 0f, loc.time
            )
            refreshFixStatus()
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
        } else if (now - lastAcceptedAtMs >= STATIONARY_HEARTBEAT_MS) {
            // Parked for a while: re-send the anchor itself (speed 0, same spot), so the
            // Remote phone still sees the bike live without the marker wandering.
            // A re-sent anchor adds zero trip distance and is too slow for trip detection.
            val anchor = anchorFix
            if (anchor != null) {
                lastAcceptedAtMs = now
                seededFix = false
                TrackerState.lastFix.value = FixInfo(
                    anchor.latitude, anchor.longitude, 0f,
                    if (anchor.hasAccuracy()) anchor.accuracy else 0f, loc.time
                )
                refreshFixStatus()
                scope.launch {
                    val dao = app.db.pending()
                    dao.insert(
                        PendingLocation(
                            lat = anchor.latitude, lng = anchor.longitude,
                            speed = 0f, bearing = null,
                            accuracy = if (anchor.hasAccuracy()) anchor.accuracy else null,
                            battery = battery, charging = charging, recordedAt = loc.time
                        )
                    )
                    dao.trim(MAX_QUEUE)
                    flushSignal.trySend(Unit)
                }
            }
        }

        if (now - lastWidgetAt > 30_000) { lastWidgetAt = now; TrackerWidget.update(this) }

        if (p.mode == MODE_AUTO && stationaryFor >= AdaptivePolicy.AUTO_STOP_AFTER_MS && !p.parkingArmed && !guard.isLive()) autoStop()
    }

    /**
     * Motion while parked with GPS off (v1.2.42): resume fixes at once so a real
     * move is measured against the guard anchor instead of waiting for the next
     * scheduled fix. No-op while updates are already running. The stationary
     * clock restarts here: the bike was just handled, so "stopped since" is now.
     */
    private fun wakeGpsForMotion() {
        if (updating) return
        lastMoveAt = SystemClock.elapsedRealtime()
        requestUpdates(app.prefs.intervalSec.coerceIn(3, 60) * 1000L)
        seedLastKnown(force = true)
        AppLog.i("gps", "on: motion")
    }

    /**
     * GPS jitter filter: a fix counts as real when the GPS itself reports movement, when the
     * bike only just stopped (covers the crash check, which needs a fresh slow fix within
     * 15 s of an impact), or when it moved clearly beyond the fix accuracy. Anything else
     * while stationary is jitter: it is dropped, and [anchorFix] stays where the bike is.
     * A derived speed (no GPS speed) never counts as movement on its own: a jitter jump
     * between two fixes would otherwise fake one.
     */
    private fun isAcceptedFix(loc: Location, speedMs: Float, now: Long): Boolean {
        val anchor = anchorFix ?: return true
        if (loc.hasSpeed() && speedMs >= AdaptivePolicy.MOVING_SPEED_MS) return true
        if (now - lastMoveAt < STOP_SETTLE_MS) return true
        val threshold = maxOf(if (loc.hasAccuracy()) loc.accuracy else 0f, JITTER_FLOOR_M)
        return loc.distanceTo(anchor) >= threshold
    }

    /**
     * Parked anchor heartbeat (v1.2.42): while GPS sleeps, re-send the saved
     * parked spot as a location row so the Remote phone still sees the bike
     * live. Uses the saved anchor coordinates, never a fresh fix.
     */
    private suspend fun heartbeatParkedAnchor() {
        val lat = app.prefs.guardAnchorLat ?: return
        val lng = app.prefs.guardAnchorLng ?: return
        val (battery, charging) = batteryInfo()
        app.db.pending().insert(
            PendingLocation(
                lat = lat, lng = lng, speed = 0f, bearing = null, accuracy = null,
                battery = battery, charging = charging, recordedAt = System.currentTimeMillis()
            )
        )
        app.db.pending().trim(MAX_QUEUE)
        flushSignal.trySend(Unit)
        AppLog.i("gps", "anchor heartbeat")
    }

    /** Auto mode: parked long enough. Upload what is queued, then stop. The next "in vehicle" event restarts us. */
    private fun autoStop() {
        if (autoStopping) return
        autoStopping = true
        AppLog.i("tracking", "auto stop: parked")
        app.prefs.trackingActive = false
        app.prefs.trackingStartedAtMs = 0L // explicit stop: the timer ends here
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
            // Freshness is measured against the server-computed age (v1.2.8),
            // so a phone clock minutes wrong cannot expire a live command.
            val fresh = c.ageMs < freshWindowMs(c.type)
            if (fresh) execute(c)
            try { app.api.ackCommand(id, if (fresh) "done" else "expired") } catch (e: IOException) { /* retried next poll */ }
        }
        // C3: do not sit running forever after a master off wake. A batch
        // that contained master_on leaves shouldTrack true, so this never
        // fires on the wake that turns tracking on. A locate fix is async,
        // so the stop waits out the locate grace, or the fix would be lost.
        if (!app.prefs.shouldTrack && System.currentTimeMillis() >= locateGraceUntilMs) TrackingService.stop(this)
    }

    private suspend fun execute(c: CommandDto) {
        AppLog.i("cmd", (c.type ?: "?") + (c.id?.let { " #$it" } ?: "") + (c.arg?.let { " $it" } ?: ""))
        when (c.type) {
            "locate" -> { locateGraceUntilMs = System.currentTimeMillis() + LOCATE_GRACE_MS; locateNow() }
            "live" -> { guard.goLive(15); reevaluateInterval() }
            "siren_on" -> SirenPlayer.start(this, 60)
            "siren_off" -> SirenPlayer.stop(this)
            "arm" -> { if (app.prefs.masterOn) { GuardControl.setArmed(this, true); pushSettingsToServer(guardArmed = true) } else AppLog.i("cmd", "arm ignored: master off") }
            "disarm" -> { GuardControl.setArmed(this, false); SirenPlayer.stop(this); pushSettingsToServer(guardArmed = false) }
            "set_speed_limit" -> { app.prefs.speedLimitKmh = c.arg?.toIntOrNull() ?: 0; app.prefs.stateDirty = true; pushSettingsToServer(speedLimitKmh = app.prefs.speedLimitKmh) }
            "cancel_sos" -> SosController.cancel(this)
            "sos" -> GuardControl.sos(this) // Remote trigger (v1.2.19): same immediate SOS as the Tracker's hold button
            "mark_serviced" -> app.prefs.markServiced()
            "tracking_start" -> {
                // C3: a stale start delivered by a wake must not start
                // tracking while the master switch is off.
                if (app.prefs.masterOn) {
                    app.prefs.trackingActive = true
                    app.prefs.stateDirty = true // the Remote phone reads this back as tracking_expected
                    tryStartTracking()
                } else AppLog.i("cmd", "tracking_start ignored master off")
            }
            "master_on" -> {
                // Master switch ON (v1.2.42): tracking and live start at once,
                // but the guard no longer arms instantly — it arms itself 5
                // minutes after the bike stops (Guard.onFix), so no
                // guardArmed mirror is pushed here either. A fresh ON clears
                // the manual-disarm latch, so it is never blocked for 10 minutes.
                app.prefs.masterOn = true
                app.prefs.trackingActive = true
                app.prefs.stateDirty = true
                app.prefs.manualDisarmAtMs = 0L
                guard.goLive(15)
                reevaluateInterval()
                tryStartTracking()
            }
            "master_off" -> {
                // Master switch OFF (v1.2.40): everything off, no exceptions.
                app.prefs.masterOn = false
                app.prefs.trackingActive = false
                app.prefs.trackingStartedAtMs = 0L // explicit stop: the timer ends here
                app.prefs.stateDirty = true
                GuardControl.setArmed(this, false)
                guard.endLive()
                SirenPlayer.stop(this)
                pushSettingsToServer(guardArmed = false)
                // Push the new state to the server BEFORE the service stops (v1.2.38):
                // stopService() kills the scope that would have flushed the mirror
                // patch, so the Remote's switch waited for a confirmation that never came.
                withTimeoutOrNull(8_000) { app.sync.flush() }
                TrackingService.stop(this)
            }
            "tracking_stop" -> {
                // Refused while the guard is armed, or in Always-on mode: stopping would
                // kill the monitoring the owner still expects, so the service keeps running.
                // In Auto mode a later movement may restart tracking on its own.
                if (!app.prefs.parkingArmed && app.prefs.mode != MODE_ALWAYS) {
                    app.prefs.trackingActive = false
                    app.prefs.stateDirty = true
                    // Push the new state to the server BEFORE the service stops (v1.2.38):
                    // stopService() kills the scope that would have flushed the mirror
                    // patch, so the Remote's toggle waited for a confirmation that never
                    // came and the switch stayed ON after tracking had already stopped.
                    withTimeoutOrNull(8_000) { app.sync.flush() }
                    TrackingService.stop(this)
                }
            }
        }
        flushSignal.trySend(Unit)
        // The log rides with the report (v1.2.41): after a command runs, the
        // owner can read what happened without waiting for the 15 min heartbeat.
        scope.launch { app.diag.reportAuto("cmd " + (c.type ?: "?")) }
    }

    // ---------- server-synced settings (v1.1.0) ----------

    /** Pulls the Remote phone's settings row and applies it, then confirms with applied_at. */
    private suspend fun syncSettings() {
        checkHealth()
        checkGuardSchedule()
        val p = app.prefs
        if (!p.isLoggedIn || p.isRemote) return
        val s0 = try { app.api.getDeviceSettings(p.deviceId) } catch (e: IOException) { null }
        if (s0 == null) {
            // No settings row yet (a tracker whose Remote never saved settings):
            // there is nothing to adopt, so the local values are the truth. Mark
            // adopted so the stateDirty patch can mirror them; a fetch error
            // leaves the flag false and the patch keeps omitting. Only checked
            // while the flag is false, so this costs one extra call at most.
            if (!p.deviceStateAdopted) {
                val exists = try { app.api.deviceSettingsRowExists(p.deviceId) } catch (e: IOException) { null }
                if (exists == false) { p.deviceStateAdopted = true; p.stateDirty = true }
            }
            return
        }
        // Audit C1: a local master OFF while offline leaves guard_armed pending in
        // Prefs. Flush it now that a row is known to exist, BEFORE applying the
        // row: the fetched copy may still carry the old armed value, and applying
        // it first would re-arm the tracker from a Remote edit made while offline.
        // A successful flush means the server already holds the pending value, so
        // the row applied below uses it instead of the stale fetched one. This runs
        // before the unchanged-row early return below, so a pending value is never
        // skipped just because the Remote made no edit.
        var s = s0
        val pending = p.pendingGuardArmed
        if (pending != null && GuardControl.flushPendingGuardArmed(app, rowExists = true)) {
            s = s.copy(guardArmed = pending)
        }
        val updatedMs = isoToMs(s.updatedAt.orEmpty())
        if (updatedMs <= lastSettingsSeenMs && updatedMs != 0L) return
        if (updatedMs > 0L) lastSettingsSeenMs = updatedMs
        applySettings(s)
        // First settings application since install: the local armed / speed-limit
        // values are now the adopted ones, so the stateDirty patch may mirror
        // them (v1.2.17). stateDirty forces a re-patch: an earlier flush may
        // already have sent — and cleared — a patch that omitted them.
        if (!p.deviceStateAdopted) { p.deviceStateAdopted = true; p.stateDirty = true }
        try { app.api.markSettingsApplied(p.deviceId) } catch (e: IOException) { /* retried next poll */ }
    }

    private fun applySettings(s: DeviceSettingsDto) {
        val p = app.prefs
        if (s.mode in listOf("manual", "auto", "always") && s.mode != p.mode) {
            p.mode = s.mode
            if (s.mode == MODE_AUTO) AutoMode.enable(app) else AutoMode.disable(app)
            if (s.mode == MODE_ALWAYS && p.masterOn) tryStartTracking()
            else p.trackingActive = TrackerState.running.value
        }
        if (s.intervalSec in 3..60 && s.intervalSec != p.intervalSec) {
            p.intervalSec = s.intervalSec
            reevaluateInterval()
        }
        if (s.speedLimitKmh != p.speedLimitKmh) { p.speedLimitKmh = s.speedLimitKmh; p.stateDirty = true }
        // A server row older than a local arm/disarm on this phone must not undo it.
        // Arm direction only with master ON (C2): a Remote arm while master is OFF
        // is logged and skipped, with nothing stamped, so a later legitimate arm
        // is not blocked. Disarm direction stays ungated so a Remote disarm works.
        if (s.guardArmed != p.parkingArmed && isoToMs(s.updatedAt.orEmpty()) >= p.lastGuardChangeMs) {
            if (s.guardArmed && !p.masterOn) AppLog.i("guard", "server arm ignored: master off")
            else GuardControl.setArmed(this, s.guardArmed, local = false)
        }
        if (s.autoArm != p.autoArm) p.autoArm = s.autoArm
        if (s.sirenOnAlert != p.sirenOnAlert) p.sirenOnAlert = s.sirenOnAlert
        if (s.sosSms != p.sosSms) p.sosSms = s.sosSms
        if (s.crashEnabled != p.crashDetection) p.crashDetection = s.crashEnabled
        if (s.sosNumber != p.emergencyNumber) p.emergencyNumber = s.sosNumber
        if (s.serviceKm != p.serviceIntervalKm) {
            // A fresh install has interval 0: restore the server value directly.
            // scheduleService() would recompute nextServiceKm from the current
            // odometer and overwrite the correctly adopted server value (v1.2.16),
            // and the stateDirty patch would then push the wrong value back to
            // the server. A genuine Remote interval change (local non-zero) keeps
            // the old behaviour: recompute the next due from now.
            if (p.serviceIntervalKm == 0) { p.serviceIntervalKm = s.serviceKm; p.serviceIntervalAdopted = true }
            else p.scheduleService(s.serviceKm)
        }
        p.guardScheduleStart = s.guardScheduleStart ?: -1
        p.guardScheduleEnd = s.guardScheduleEnd ?: -1
    }

    /**
     * Reports back exactly what a remote command changed, nothing more.
     * The old whole-row mirror upserted all twelve settings from this phone's local
     * Prefs after every arm/disarm/speed-limit command, so a Remote edit made in the
     * same ~30 s window could be overwritten with stale local values — and the
     * applied_at stamp that followed made the Remote show "applied" for a value
     * that had silently reverted. A field-scoped PATCH cannot do that: only the
     * field the command actually changed is written (plus applied_at, in the same
     * request), and every other field stays exactly as the Remote left it.
     */
    private fun pushSettingsToServer(guardArmed: Boolean? = null, speedLimitKmh: Int? = null) {
        val p = app.prefs
        // Application scope, not the service scope (v1.2.40): the master_off
        // path stops the service right after this call, and a patch launched
        // in the service scope would die with it — leaving a stale guardArmed
        // row that the Watchdog would revive the tracker on.
        app.appScope.launch {
            // Audit C1: a dropped patch used to leave the server row armed after a
            // local master OFF, so the next Remote settings edit re-armed the
            // tracker. Record the value first: when the PATCH cannot run yet (no
            // settings row seen) or fails, it stays pending in Prefs and the next
            // syncSettings poll or Watchdog tick retries it instead of losing it.
            if (guardArmed != null) p.pendingGuardArmed = guardArmed
            // No settings row seen from the server yet: a filtered PATCH on a
            // missing row is a silent no-op, so there is nothing to patch — the
            // value stays pending until syncSettings has fetched a row.
            if (lastSettingsSeenMs == 0L) {
                runCatching { app.api.markSettingsApplied(p.deviceId) }
                return@launch
            }
            val ok = app.api.patchTrackerSettings(p.deviceId, guardArmed, speedLimitKmh)
            // Clear only when this PATCH carried the pending value: a
            // speed-limit-only call must never drop a pending guard_armed, and a
            // newer arm/disarm queued meanwhile must survive an older PATCH.
            if (ok && guardArmed != null && p.pendingGuardArmed == guardArmed) p.pendingGuardArmed = null
            else if (!ok && guardArmed != null) AppLog.i("guard", "guard patch pending")
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
        if (inside && p.masterOn && !p.parkingArmed && !latched) { GuardControl.setArmed(this, true); p.scheduleArmed = true; pushSettingsToServer(guardArmed = true) }
        else if (!inside && p.scheduleArmed) { GuardControl.setArmed(this, false, latch = false); p.scheduleArmed = false; pushSettingsToServer(guardArmed = false) }
    }

    /** Raises one health_warning event when the tracker's health changes for the worse,
     * and a health_ok event when it recovers, so the Dashboard warning clears. */
    private fun checkHealth() {
        val fine = Perms.hasFine(this)
        val battOk = Perms.ignoringBatteryOptimizations(this)
        val key = "fine=$fine|batt=$battOk"
        val prev = app.prefs.lastHealthState
        if (key == prev) return
        app.prefs.lastHealthState = key
        if (!fine || !battOk) {
            GuardControl.raise(
                this, "health_warning", "warning",
                buildJsonObject { put("location", fine); put("battery_ok", battOk) },
            )
        } else if (prev.contains("fine=false") || prev.contains("batt=false")) {
            GuardControl.raise(this, "health_ok", "info")
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
        AppLog.i("tracking", "stopped")
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
        /** Stationary fixes closer than this to the anchor are GPS jitter, never a real move. */
        const val JITTER_FLOOR_M = 10f
        /** A parked bike re-sends its anchor position this often so the Remote phone still sees it live. */
        const val STATIONARY_HEARTBEAT_MS = 10 * 60_000L
        /** After the bike stops, fixes keep being accepted this long (covers the crash check's 15 s window). */
        const val STOP_SETTLE_MS = 30_000L
        /** After a manual disarm, auto re-arm (and the guard schedule) waits this long. */
        const val DISARM_LATCH_MS = 10 * 60_000L
        /** A fix counts as fresh (live) this long after it arrived; older than
         * this without a new fix it is last-known, never live. */
        const val FRESH_FIX_MS = 30_000L
        /** The last-known seed is retried at most this often while no fix exists. */
        const val SEED_RETRY_MS = 60_000L
        /** A remote command stays executable this long after it was sent. */
        const val FRESH_COMMAND_MS = 10 * 60_000L
        /** After a locate command, the service stays alive this long for the
         * one-shot fix to land before the master-off stop runs (C3). */
        const val LOCATE_GRACE_MS = 30_000L
        /**
         * Start/arm commands stay executable longer: the Watchdog only runs every 15 minutes,
         * so a 10-minute window could expire before it ever gets a chance to revive a stopped
         * tracker (v1.2.1 issue 1). A late start or arm is still what the owner asked for;
         * a late siren or locate is not, so those keep the short window.
         */
        const val FRESH_START_MS = 60 * 60_000L
        /** How long a command of [type] stays executable after it was sent. */
        fun freshWindowMs(type: String?): Long =
            if (type == "tracking_start" || type == "arm" || type == "master_on") FRESH_START_MS else FRESH_COMMAND_MS
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
            // Every caller of stop() is an explicit stop (user toggle off,
            // remote tracking_stop, sign-out, role change to Remote, session
            // expiry): the timer ends here. A service restart never goes
            // through stop(), so the timer survives it.
            runCatching { (ctx.applicationContext as BikeGuardApp).prefs.trackingStartedAtMs = 0L }
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }
}

/** Convenience used by receivers/workers that must never crash on a refused background start. */
fun Context.tryStartTracking(): Boolean = try {
    TrackingService.start(this); true
} catch (e: Exception) { false }
