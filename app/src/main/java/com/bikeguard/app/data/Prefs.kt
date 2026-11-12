package com.bikeguard.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

const val MODE_MANUAL = "manual"
const val MODE_ALWAYS = "always"

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

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()
    val shouldTrack: Boolean get() = isLoggedIn && (trackingActive || mode == MODE_ALWAYS)

    fun clearSession() = sp.edit {
        remove("at"); remove("rt"); remove("uid"); remove("email")
        remove("device_id"); putBoolean("device_registered", false); putBoolean("tracking_active", false)
    }
}
