package com.bikeguard.app.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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

/** In-app language (v1.2.20): "" = system, "en" = English, "bn" = Bengali. */
const val LANG_SYSTEM = ""
/** Theme (v1.2.23): "light" is the default; "dark" is the dark option; "system" follows the phone. */
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

/** Tokens and settings. Encrypted with the Android Keystore; falls back to plain prefs only if the Keystore is broken. */
class Prefs(private val context: Context) {
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

    /** DownloadManager id of the in-app update download (v1.2.30); 0 = none. */
    var updateDownloadId: Long
        get() = sp.getLong("update_dl_id", 0L)
        set(v) = sp.edit { putLong("update_dl_id", v) }

    /** Hardware-stable key for this phone: Settings.Secure.ANDROID_ID. Unlike the random
     *  device id below it survives an uninstall and an app-data clear (same app, same
     *  phone, same signing key), so after a reinstall the phone can find its old server
     *  row instead of registering a second one. Needs no permission. Null when Android
     *  has none; the well-known broken value 9774d56d682e549c counts as absent. A factory
     *  reset changes it, so it is a rejoin key, not a forever identity. */
    val hardwareKey: String?
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

    /** The server device row this phone uploads under. Generated locally so the phone can start recording before it ever reaches the server.
     *  Stored in EncryptedSharedPreferences ("device_id"): an app update keeps it, but an
     *  uninstall or app-data clear wipes it — that wipe is what created the duplicate
     *  device rows, because the next launch minted a fresh random UUID. */
    var deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            sp.edit { putString("device_id", id) }
        }
        set(v) = sp.edit { putString("device_id", v) }
    /** True when an earlier run stored a device id. Checked BEFORE reading [deviceId],
     *  whose getter would mint a fresh random id and hide the fact that none was stored. */
    val hasStoredDeviceId: Boolean get() = sp.contains("device_id")
    /** Point this phone at an existing server row (found by [hardwareKey]) and forget the
     *  unregistered id minted since the reinstall, if any. Registration runs again right
     *  after, so the adopted row is re-registered (a no-op upsert) instead of orphaned. */
    fun adoptDeviceId(id: String) {
        deviceId = id
        deviceRegistered = false
        // A new row means the odometer adopt must run again for that row.
        odometerAdopted = false
        // Same for the service interval adopt (v1.2.16).
        serviceIntervalAdopted = false
        // Same for the armed / speed-limit adopt (v1.2.17).
        deviceStateAdopted = false
    }
    /** True once this phone's hardware key has been stamped on its server device row
     *  (devices.hardware_key, migration 0008). False until the stamp succeeds, so a
     *  failed stamp — e.g. the migration is not applied yet — retries on the next flush. */
    var hardwareKeyStamped: Boolean
        get() = sp.getBoolean("hardware_key_stamped", false)
        set(v) = sp.edit { putBoolean("hardware_key_stamped", v) }
    var deviceRegistered: Boolean
        get() = sp.getBoolean("device_registered", false)
        set(v) = sp.edit { putBoolean("device_registered", v) }

    var trackingActive: Boolean
        get() = sp.getBoolean("tracking_active", false)
        set(v) = sp.edit { putBoolean("tracking_active", v) }

    /**
     * Master switch (v1.2.40): the one shared tracking button on both phones.
     * ON means everything on (tracking, guard, live); OFF means everything off,
     * no exception for Always-on mode or an armed guard. Unset (first run after
     * the update) it takes the current tracking state, so the switch never flips
     * on its own. Kept in step with trackingActive and parkingArmed by the
     * master paths, so the three never disagree.
     */
    var masterOn: Boolean
        get() = sp.getBoolean("master_on", trackingActive)
        set(v) = sp.edit { putBoolean("master_on", v) }
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

    /** Pending SOS countdown (v1.2.19): the 60 s countdown used to live only
     *  in an appScope coroutine, so a dead process lost the SMS. The deadline
     *  and location are persisted here when the countdown starts and cleared
     *  when the SMS is sent (or cancelled); SosController.resumePending
     *  re-arms the countdown or sends at once on the next start. 0 / "" =
     *  no SOS pending. */
    var sosPendingUntilMs: Long
        get() = sp.getLong("sos_pending_until", 0L)
        set(v) = sp.edit { putLong("sos_pending_until", v) }
    var sosPendingLat: String
        get() = sp.getString("sos_pending_lat", "") ?: ""
        set(v) = sp.edit { putString("sos_pending_lat", v) }
    var sosPendingLng: String
        get() = sp.getString("sos_pending_lng", "") ?: ""
        set(v) = sp.edit { putString("sos_pending_lng", v) }
    var speedLimitKmh: Int
        get() = sp.getInt("speed_limit", 0)
        set(v) = sp.edit { putInt("speed_limit", v) }
    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit { putBoolean("app_lock", v) }

    /** In-app language (v1.2.20): "" = system, "en" = English, "bn" = Bengali.
     *  Applied in attachBaseContext via LocaleHelper; changing it recreates
     *  the activity, so the whole UI switches without a reinstall. */
    var language: String
        get() = sp.getString("language", LANG_SYSTEM) ?: LANG_SYSTEM
        set(v) = sp.edit { putString("language", v) }
    /** Theme (v1.2.23): THEME_LIGHT is the default, THEME_DARK for dark mode,
     *  THEME_SYSTEM to follow the phone. Changing it recreates the
     *  activity, so the status-bar style and window background follow too. */
    var themeMode: String
        get() = sp.getString("theme_mode", THEME_LIGHT) ?: THEME_LIGHT
        set(v) = sp.edit { putString("theme_mode", v) }

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
    /** Marks the bike serviced: next due = odometer + interval. Refuses while the
     *  interval is still unknown (fresh install, server value not adopted yet):
     *  computing from interval 0 would switch the reminder off, and the zero
     *  must never be treated as a real value. Once adopted, an interval of 0
     *  genuinely means "reminder off" and is honoured. */
    fun markServiced() {
        if (serviceIntervalKm == 0 && !serviceIntervalAdopted) return
        scheduleService(serviceIntervalKm)
    }

    /** True once this phone has pulled the server's odometer / next-service values
     *  into Prefs (v1.2.11). A reinstall wipes Prefs (odometer 0, stateDirty true by
     *  default), so without this the first flush after a reinstall would PATCH
     *  odometer_km 0 onto the server row and erase the real odometer. The adopt runs
     *  once per install (reset by [adoptDeviceId]); until it succeeds, the patch
     *  omits the odometer entirely, so the server value is never overwritten with 0. */
    var odometerAdopted: Boolean
        get() = sp.getBoolean("odo_adopted", false)
        set(v) = sp.edit { putBoolean("odo_adopted", v) }

    /** True once this phone has applied the server's service interval
     *  (device_settings.service_km) into Prefs (v1.2.16). A reinstall wipes
     *  serviceIntervalKm to 0; until the adopt runs, 0 means "unknown", not
     *  "reminder off" — markServiced() must not compute from it. */
    var serviceIntervalAdopted: Boolean
        get() = sp.getBoolean("svc_interval_adopted", false)
        set(v) = sp.edit { putBoolean("svc_interval_adopted", v) }

    /** True once this phone has applied the server's device_settings row at
     *  least once since install (v1.2.17) — or learned there is no row yet.
     *  Until then the stateDirty patch omits armed / speed limit /
     *  tracking_expected: a reinstall wipes those to false/0, and the first
     *  flush must never overwrite the server row with them. The service loop
     *  owns the adoption (applySettings, via GuardControl.setArmed for the
     *  guard), because arming has side effects the flush must not duplicate
     *  (anchor, TrackerState, tracking start). Reset on a new device row and
     *  on sign-out, when the local state is untrusted again. */
    var deviceStateAdopted: Boolean
        get() = sp.getBoolean("device_state_adopted", false)
        set(v) = sp.edit { putBoolean("device_state_adopted", v) }

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

    /** Consecutive flushes the server rejected with 403/409 (v1.2.13). Reset on any
     *  successful flush. After 3 in a row the sync backs off instead of hammering
     *  the server with a register + upload round-trip on every location fix. */
    var serverRejectCount: Int
        get() = sp.getInt("server_reject_count", 0)
        set(v) = sp.edit { putInt("server_reject_count", v) }
    /** Do not attempt any server upload before this time (millis); 0 = no backoff.
     *  Set when the server keeps rejecting us; cleared on a successful flush or a
     *  fresh sign-in. While it is in the future, flush() returns the stored reason
     *  without touching the network. */
    var syncBackoffUntilMs: Long
        get() = sp.getLong("sync_backoff_until_ms", 0L)
        set(v) = sp.edit { putLong("sync_backoff_until_ms", v) }
    /** The server's own rejection message while backed off ("" = not blocked).
     *  Shown on the Tracker screen through lastSyncError. */
    var syncBlockedReason: String
        get() = sp.getString("sync_blocked_reason", "") ?: ""
        set(v) = sp.edit { putString("sync_blocked_reason", v) }

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

    /** First-run setup wizard (v1.2.9): true once the user finished it with every
     *  step done. Skipping leaves it false, so the wizard shows again on the next
     *  launch while setup is still incomplete. Survives sign-out (the phone's
     *  permissions survive too); the wizard stays reachable from Settings. */
    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit { putBoolean("onboarding_done", v) }
    /** MIUI Autostart cannot be detected by any public API, so the wizard asks the
     *  user to confirm it by hand after turning it on in the Security app. */
    var autostartConfirmed: Boolean
        get() = sp.getBoolean("autostart_confirmed", false)
        set(v) = sp.edit { putBoolean("autostart_confirmed", v) }

    /** Guard anchor: the parked position `moved` alerts are measured against.
     *  Persisted (not just in-memory like Guard.anchor) so a service restart —
     *  Watchdog revival, reboot, force-stop — does not lose it: without this
     *  the bike's current position, where the thief may already have taken it,
     *  would become the new anchor and `moved` would never fire. Null = none saved. */
    var guardAnchorLat: Double?
        get() = sp.getString("guard_anchor_lat", null)?.toDoubleOrNull()
        set(v) = sp.edit { if (v == null) remove("guard_anchor_lat") else putString("guard_anchor_lat", v.toString()) }
    var guardAnchorLng: Double?
        get() = sp.getString("guard_anchor_lng", null)?.toDoubleOrNull()
        set(v) = sp.edit { if (v == null) remove("guard_anchor_lng") else putString("guard_anchor_lng", v.toString()) }
    /** When the anchor was set, millis since epoch; 0 = none saved. */
    var guardAnchorAtMs: Long
        get() = sp.getLong("guard_anchor_at_ms", 0L)
        set(v) = sp.edit { putLong("guard_anchor_at_ms", v) }
    /** Save the guard anchor (position + set time) in one call. */
    fun saveGuardAnchor(lat: Double, lng: Double) {
        guardAnchorLat = lat
        guardAnchorLng = lng
        guardAnchorAtMs = System.currentTimeMillis()
    }
    /** Drop the saved guard anchor, e.g. on disarm or sign-out. */
    fun clearGuardAnchor() {
        guardAnchorLat = null
        guardAnchorLng = null
        guardAnchorAtMs = 0L
    }

    val isTracker: Boolean get() = appRole == ROLE_TRACKER
    val isRemote: Boolean get() = appRole == ROLE_REMOTE

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()
    /** The Remote phone never tracks, even if an old install left tracking prefs behind. */
    /** Master switch (v1.2.40): the switch alone decides whether the bike is
     *  watched. Mode and guard no longer start tracking on their own — they
     *  follow the switch, so OFF always means off. */
    val shouldTrack: Boolean get() = isLoggedIn && !isRemote && masterOn

    /** True when the session died on its own (refresh token rejected) and the owner
     *  has not been told yet. Survives clearSession() on purpose: the flag is what
     *  re-posts the warning notification on the next app start if the process died
     *  before the owner saw it. Cleared when the owner reaches the sign-in screen
     *  (endSession) or signs in again. */
    var sessionExpiredPending: Boolean
        get() = sp.getBoolean("session_expired_pending", false)
        set(v) = sp.edit { putBoolean("session_expired_pending", v) }

    /** Signs out but keeps this phone's device identity, so a re-login uploads under the same device. */
    fun clearSession() = sp.edit {
        remove("at"); remove("rt"); remove("uid"); remove("email")
        putBoolean("tracking_active", false)
        putBoolean("armed", false); putBoolean("state_dirty", true)
        // A new session must re-adopt the server state before the patch may
        // mirror armed / speed limit again (v1.2.17): sign-out forces armed
        // false locally, and that wiped value must never reach the server row.
        putBoolean("device_state_adopted", false)
        // Sign-out disarms the guard: the saved anchor must go too, or a
        // later re-arm at a new spot would measure against the old one.
        remove("guard_anchor_lat"); remove("guard_anchor_lng"); remove("guard_anchor_at_ms")
        remove("invite_code") // stale key from the removed family-invite feature (v1.2.5); nothing reads it anymore
        remove("remote_target") // a stale Remote target must not point at the old account's device
        // A pending SOS belongs to the old session: a signed-out phone must
        // never text the old emergency contact after the countdown outlives it.
        remove("sos_pending_until"); remove("sos_pending_lat"); remove("sos_pending_lng")
    }
}
