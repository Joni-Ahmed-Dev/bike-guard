package com.bikeguard.app.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.R
import com.bikeguard.app.data.eventTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything the tracker watches besides position: parking mode, tamper signals, crash detection,
 * speed limit, odometer and the service reminder. Owned by [TrackingService].
 */
class Guard(private val ctx: Context, private val app: BikeGuardApp) : SensorGuard.Listener {
    private val prefs get() = app.prefs
    private val sensors = SensorGuard(ctx, this)
    private val cooldowns = HashMap<String, Long>()

    @Volatile var liveUntil = 0L
        private set

    private var armedSeen = false
    private var anchor: Location? = null
    private var prev: Location? = null
    private var overSpeed = 0
    private var recentFastAt = -1_000_000L
    private var odoPendingM = 0f

    fun isLive() = System.currentTimeMillis() < liveUntil
    fun goLive(minutes: Int) { liveUntil = System.currentTimeMillis() + minutes * 60_000L }

    private fun cooled(key: String, ms: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (cooldowns[key] ?: -ms) < ms) return false
        cooldowns[key] = now
        return true
    }

    private fun raise(type: String, severity: String, data: kotlinx.serialization.json.JsonObject? = null) =
        GuardControl.raise(ctx, type, severity, data)

    /** True when there is no guard schedule, or the current hour is inside the window. */
    private fun insideScheduleWindow(): Boolean {
        val start = prefs.guardScheduleStart; val end = prefs.guardScheduleEnd
        if (start !in 0..23 || end !in 0..23 || start == end) return true
        val hour = LocalTime.now().hour
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    // ---------- tamper signals ----------

    private val tamper = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    if (i.getBooleanExtra("state", false)) raise("tamper_airplane", "critical")
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val lm = c.getSystemService(LocationManager::class.java)
                    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && cooled("gps_off", 60_000))
                        raise("tamper_gps_off", "critical")
                }
                Intent.ACTION_POWER_DISCONNECTED -> if (prefs.parkingArmed) raise("charger_unplugged", "warning")
                Intent.ACTION_BATTERY_LOW -> if (cooled("battery_low", 30 * 60_000L)) raise("battery_low", "warning")
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        runCatching { ContextCompat.registerReceiver(ctx, tamper, f, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    fun stop() {
        sensors.updateMode(SensorGuard.Mode.OFF)
        runCatching { ctx.unregisterReceiver(tamper) }
    }

    // ---------- per-fix logic ----------

    fun onFix(loc: Location, speedMs: Float, battery: Int?, stationaryFor: Long) {
        val armed = prefs.parkingArmed
        val moving = speedMs >= AdaptivePolicy.MOVING_SPEED_MS

        // odometer (ignore poor fixes and teleports)
        val p = prev
        prev = loc
        if (p != null && moving && (!loc.hasAccuracy() || loc.accuracy <= 50f)) {
            val d = loc.distanceTo(p)
            if (d in 1f..500f) addOdometer(d)
        }
        checkServiceDue()

        // parking mode: remember where the bike was parked, alert when it leaves
        if (armed && !armedSeen) {
            // Restore the saved anchor first: after a service restart (Watchdog
            // revival, reboot, force-stop) the guard is still armed but the
            // in-memory anchor is gone. Without this the bike's current position —
            // where the thief may already have taken it — would become the new
            // anchor and `moved` would never fire. Only when nothing was saved
            // (first arm at this spot) is the anchor taken from the current fix.
            val savedLat = prefs.guardAnchorLat
            val savedLng = prefs.guardAnchorLng
            if (savedLat != null && savedLng != null) {
                anchor = Location("guard-anchor").apply {
                    latitude = savedLat
                    longitude = savedLng
                    time = prefs.guardAnchorAtMs
                }
            } else {
                anchor = loc
                prefs.saveGuardAnchor(loc.latitude, loc.longitude)
                // Save the spot so the Remote phone can show "where I parked".
                app.appScope.launch { runCatching { app.api.saveParkingSpot(app.prefs.deviceId, loc.latitude, loc.longitude) } }
            }
            armedSeen = true
        }
        if (!armed && armedSeen) { armedSeen = false; anchor = null; prefs.clearGuardAnchor() }
        if (armed) {
            anchor?.let { a ->
                val d = loc.distanceTo(a)
                if (d > max(60f, (if (loc.hasAccuracy()) loc.accuracy else 0f) * 2) && cooled("moved", 5 * 60_000L)) {
                    raise("moved", "critical", buildJsonObject { put("meters", d.roundToInt()) })
                    goLive(15)
                    if (prefs.sirenOnAlert) SirenPlayer.start(ctx, 30)
                }
            }
        } else if (prefs.autoArm && stationaryFor >= 5 * 60_000L && !isLive()
            && System.currentTimeMillis() - prefs.manualDisarmAtMs >= TrackingService.DISARM_LATCH_MS
            && insideScheduleWindow()) {
            GuardControl.setArmed(ctx, true)
            // The owner is told the guard armed itself; movement while armed never disarms.
            raise("auto_armed", "info")
        }

        // speed limit: three fixes in a row over the limit
        val limit = prefs.speedLimitKmh
        if (limit > 0 && speedMs * 3.6f > limit) overSpeed++ else overSpeed = 0
        if (overSpeed >= 3 && cooled("speed_limit", 5 * 60_000L))
            raise("speed_limit", "warning", buildJsonObject { put("kmh", (speedMs * 3.6f).roundToInt()) })

        // accelerometer mode: parked + armed -> watch for handling; riding -> watch for impacts
        val now = SystemClock.elapsedRealtime()
        if (speedMs >= CRASH_MIN_SPEED_MS) recentFastAt = now
        sensors.updateMode(
            when {
                armed && !moving -> SensorGuard.Mode.MOTION
                prefs.crashDetection && now - recentFastAt < 15_000 -> SensorGuard.Mode.CRASH
                else -> SensorGuard.Mode.OFF
            }
        )
    }

    private fun addOdometer(meters: Float) {
        odoPendingM += meters
        if (odoPendingM < 100f) return
        val old = prefs.odometerKm
        val new = old + odoPendingM / 1000f
        odoPendingM = 0f
        prefs.odometerKm = new
        if (floor(new) != floor(old)) prefs.stateDirty = true   // server mirror updates per whole km
    }

    private fun checkServiceDue() {
        val next = prefs.nextServiceKm
        if (next > 0f && prefs.odometerKm >= next && !prefs.dueNotified) {
            prefs.dueNotified = true
            prefs.stateDirty = true
            raise("service_due", "warning")
            Notifier.local(ctx, 1004, eventTitle(ctx, "service_due", null), ctx.getString(R.string.service_due_hint))
        }
    }

    // ---------- accelerometer callbacks ----------

    override fun onMotion() {
        if (!prefs.parkingArmed || !cooled("motion", 2 * 60_000L)) return
        raise("motion", "warning")
        goLive(5)
        if (prefs.sirenOnAlert) SirenPlayer.start(ctx, 30)
    }

    override fun onImpact(peak: Float) {
        if (!prefs.crashDetection) return
        // Only count it when the bike was really moving a moment ago, and only once a minute.
        if (SystemClock.elapsedRealtime() - recentFastAt > 15_000 || !cooled("impact", 60_000L)) return
        val impactAt = System.currentTimeMillis()
        app.appScope.launch {
            delay(15_000)
            val f = TrackerState.lastFix.value
            // A crash = hard hit, then the bike stays put. A pothole = hard hit, then the ride goes on.
            if (f != null && f.timeMs > impactAt && f.speedKmh < 5f) {
                raise("crash", "critical", buildJsonObject { put("peak_ms2", peak.roundToInt()) })
                goLive(30)
                SosController.startCountdown(app, f.lat, f.lng)
            }
        }
    }

    private companion object { const val CRASH_MIN_SPEED_MS = 8f } // about 29 km/h
}
