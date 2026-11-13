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

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()
    val shouldTrack: Boolean get() = isLoggedIn && (trackingActive || mode == MODE_ALWAYS || parkingArmed)

    fun clearSession() = sp.edit {
        remove("at"); remove("rt"); remove("uid"); remove("email")
        remove("device_id"); putBoolean("device_registered", false); putBoolean("tracking_active", false)
        putBoolean("armed", false); putBoolean("state_dirty", true)
    }
}
