package com.bikeguard.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

const val MODE_MANUAL = "manual"
const val MODE_ALWAYS = "always"
const val MODE_AUTO = "auto"

/** Phone roles (v1.1.0): the bike phone only tracks, the owner's phone only controls. */
const val ROLE_TRACKER = "tracker"
const val ROLE_REMOTE = "remote"

/** Tokens and settings. Encrypted with the Android Keystore; falls back to plain prefs only if the Keystore is broken. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "bikeguard_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("bikeguard_fallback", Context.MODE_PRIVATE)
    }

    var accessToken: String?
        get() = sp.getString("at", null)
        set(v) = sp.edit { putString("at", v) }
    var refreshToken: String?
        get() = sp.getString("rt", null)
        set(v) = sp.edit { putString("rt", v) }
    var userId: String?
        get() = sp.getString("uid", null)
        set(v) = sp.edit { putString("uid", v) }
    var email: String?
        get() = sp.getString("email", null)
        set(v) = sp.edit { putString("email", v) }

    /** Generated locally so the phone can start recording before it ever reaches the server. */
    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            sp.edit { putString("device_id", id) }
        }
    var deviceRegistered: Boolean
        get() = sp.getBoolean("device_registered", false)
        set(v) = sp.edit { putBoolean("device_registered", v) }

    var trackingActive: Boolean
        get() = sp.getBoolean("tracking_active", false)
        set(v) = sp.edit { putBoolean("tracking_active", v) }
    /** When the current tracking session started, millis since epoch; 0 = not tracking.
     * Stamped only on the not-tracking -> tracking transition and cleared only on an
     * explicit stop, so the elapsed time survives a service restart and a phone reboot. */
    var trackingStartedAtMs: Long
        get() = sp.getLong("tracking_started_at_ms", 0L)
        set(v) = sp.edit { putLong("tracking_started_at_ms", v) }
    var mode: String
        get() = sp.getString("mode", MODE_MANUAL) ?: MODE_MANUAL
        set(v) = sp.edit { putString("mode", v) }
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 5)
        set(v) = sp.edit { putInt("interval_sec", v) }


    // ---- Phase 3/4 settings (all live on the tracker phone) ----
    var parkingArmed: Boolean
        get() = sp.getBoolean("armed", false)
        set(v) = sp.edit { putBoolean("armed", v) }
    var autoArm: Boolean
        get() = sp.getBoolean("auto_arm", false)
        set(v) = sp.edit { putBoolean("auto_arm", v) }
    var sirenOnAlert: Boolean
        get() = sp.getBoolean("siren_on_alert", false)
        set(v) = sp.edit { putBoolean("siren_on_alert", v) }
    var crashDetection: Boolean
        get() = sp.getBoolean("crash", true)
        set(v) = sp.edit { putBoolean("crash", v) }
    var sosSms: Boolean
        get() = sp.getBoolean("sos_sms", false)
        set(v) = sp.edit { putBoolean("sos_sms", v) }
    var emergencyNumber: String
        get() = sp.getString("sos_number", "") ?: ""
        set(v) = sp.edit { putString("sos_number", v) }
    var speedLimitKmh: Int
        get() = sp.getInt("speed_limit", 0)
        set(v) = sp.edit { putInt("speed_limit", v) }
    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit { putBoolean("app_lock", v) }

    // ---- odometer and service reminder ----
    var odometerKm: Float
        get() = sp.getFloat("odo_km", 0f)
        set(v) = sp.edit { putFloat("odo_km", v) }
    var serviceIntervalKm: Int
        get() = sp.getInt("service_every", 0)
        set(v) = sp.edit { putInt("service_every", v) }
    var nextServiceKm: Float
        get() = sp.getFloat("service_next", 0f)
        set(v) = sp.edit { putFloat("service_next", v) }
    var dueNotified: Boolean
        get() = sp.getBoolean("due_notified", false)
        set(v) = sp.edit { putBoolean("due_notified", v) }

    /** Next service due = odometer + interval (0 interval switches the reminder off). */
    fun scheduleService(intervalKm: Int) {
        serviceIntervalKm = intervalKm
        nextServiceKm = if (intervalKm > 0) odometerKm + intervalKm else 0f
        dueNotified = false
        stateDirty = true
    }
    fun markServiced() = scheduleService(serviceIntervalKm)

    /** True when the device row on the server is out of date and should be patched on the next sync. */
    var stateDirty: Boolean
        get() = sp.getBoolean("state_dirty", true)
        set(v) = sp.edit { putBoolean("state_dirty", v) }

    var lastAlertsSeenMs: Long
        get() = sp.getLong("alerts_seen", 0L)
        set(v) = sp.edit { putLong("alerts_seen", v) }

    /** Newest event id already posted as a system notification; older ids are never re-notified. */
    var lastNotifiedEventId: Long
        get() = sp.getLong("last_notified_event_id", 0L)
        set(v) = sp.edit { putLong("last_notified_event_id", v) }
    /** When the last event notification was posted, in millis since epoch; 0 = never. */
    var lastNotifiedEventMs: Long
        get() = sp.getLong("last_notified_event_ms", 0L)
        set(v) = sp.edit { putLong("last_notified_event_ms", v) }

    /** Custom notification sounds, as ringtone URI strings; "" = the system default sound. */
    var alertRingtoneUri: String
        get() = sp.getString("ringtone_alerts", "") ?: ""
        set(v) = sp.edit { putString("ringtone_alerts", v) }
    var generalRingtoneUri: String
        get() = sp.getString("ringtone_general", "") ?: ""
        set(v) = sp.edit { putString("ringtone_general", v) }

    /** Last upload failure reason ("no internet" / "timed out" / "server 401"); "" when the last try worked. */
    var lastSyncError: String
        get() = sp.getString("last_sync_error", "") ?: ""
        set(v) = sp.edit { putString("last_sync_error", v) }
    /** When the last upload was tried, in millis since epoch; 0 = never tried yet. */
    var lastSyncAttemptMs: Long
        get() = sp.getLong("last_sync_attempt", 0L)
        set(v) = sp.edit { putLong("last_sync_attempt", v) }

    /** Last diagnostics report sent to the bikeguard-diag Worker, in millis since epoch; 0 = never sent. */
    var lastDiagReportAtMs: Long
        get() = sp.getLong("last_diag_report", 0L)
        set(v) = sp.edit { putLong("last_diag_report", v) }
    /** Whether the last diagnostics report was accepted by the Worker. */
    var lastDiagReportOk: Boolean
        get() = sp.getBoolean("last_diag_ok", false)
        set(v) = sp.edit { putBoolean("last_diag_ok", v) }

    // ---- v1.1.0 roles + server-synced settings ----
    /** "" = not picked yet, ROLE_TRACKER or ROLE_REMOTE. */
    var appRole: String
        get() = sp.getString("role", "") ?: ""
        set(v) = sp.edit { putString("role", v) }
    /** 4-digit PIN set when the role is first picked; needed to change the role later. */
    var rolePin: String
        get() = sp.getString("role_pin", "") ?: ""
        set(v) = sp.edit { putString("role_pin", v) }
    /** Family view-only invite code entered on this phone (read-only Viewer, no controls). */
    var viewerInviteCode: String?
        get() = sp.getString("invite_code", null)
        set(v) = sp.edit { putString("invite_code", v) }
    /** The bike device the Remote phone controls (its device_settings row). Empty = this phone. */
    var remoteTargetDeviceId: String
        get() = sp.getString("remote_target", "") ?: ""
        set(v) = sp.edit { putString("remote_target", v) }
    var lowDataMode: Boolean
        get() = sp.getBoolean("low_data", false)
        set(v) = sp.edit { putBoolean("low_data", v) }
    /** Viewer display preference for this phone only (never synced to the server): draw the route line on the map. */
    var showRouteLine: Boolean
        get() = sp.getBoolean("show_route_line", true)
        set(v) = sp.edit { putBoolean("show_route_line", v) }
    /** Last known bike position (Remote phone), for the offline-map download in Settings. 0,0 = unknown. */
    var lastKnownLat: Double
        get() = sp.getString("last_known_lat", "0.0")?.toDoubleOrNull() ?: 0.0
        set(v) = sp.edit { putString("last_known_lat", v.toString()) }
    var lastKnownLng: Double
        get() = sp.getString("last_known_lng", "0.0")?.toDoubleOrNull() ?: 0.0
        set(v) = sp.edit { putString("last_known_lng", v.toString()) }
    /** When the guard was last armed/disarmed on this phone; a server settings row older than this must not undo it. */
    var lastGuardChangeMs: Long
        get() = sp.getLong("last_guard_change_ms", 0L)
        set(v) = sp.edit { putLong("last_guard_change_ms", v) }
    /** Guard schedule from the server: hours 0-23, -1 = no schedule. */
    var guardScheduleStart: Int
        get() = sp.getInt("sched_start", -1)
        set(v) = sp.edit { putInt("sched_start", v) }
    var guardScheduleEnd: Int
        get() = sp.getInt("sched_end", -1)
        set(v) = sp.edit { putInt("sched_end", v) }
    /** True while the guard was armed by the schedule (so it can be disarmed when the window ends). */
    var scheduleArmed: Boolean
        get() = sp.getBoolean("sched_armed", false)
        set(v) = sp.edit { putBoolean("sched_armed", v) }
    /** Last health state seen by the health watch ("fine=true|batt=true"); a change raises an alert. */
    var lastHealthState: String
        get() = sp.getString("health_state", "") ?: ""
        set(v) = sp.edit { putString("health_state", v) }

    /** When the guard was last disarmed by hand, in millis since epoch; 0 = never. Auto re-arm waits 10 minutes after this. */
    var manualDisarmAtMs: Long
        get() = sp.getLong("disarm_at_ms", 0L)
        set(v) = sp.edit { putLong("disarm_at_ms", v) }

    val isTracker: Boolean get() = appRole == ROLE_TRACKER
    val isRemote: Boolean get() = appRole == ROLE_REMOTE
    val isInviteMode: Boolean get() = !viewerInviteCode.isNullOrBlank()

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()
    /** The Remote phone never tracks, even if an old install left tracking prefs behind. */
    val shouldTrack: Boolean get() = isLoggedIn && !isRemote && (trackingActive || mode == MODE_ALWAYS || parkingArmed)

    /** Signs out but keeps this phone's device identity, so a re-login uploads under the same device. */
    fun clearSession() = sp.edit {
        remove("at"); remove("rt"); remove("uid"); remove("email")
        putBoolean("tracking_active", false)
        putBoolean("armed", false); putBoolean("state_dirty", true)
        remove("invite_code")
        remove("remote_target") // a stale Remote target must not point at the old account's device
    }
}
