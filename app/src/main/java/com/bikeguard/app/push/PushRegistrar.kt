package com.bikeguard.app.push

import com.bikeguard.app.BikeGuardApp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

/** Registers this phone's FCM token with the server. Silently does nothing when Firebase isn't configured. */
object PushRegistrar {
    fun register(app: BikeGuardApp) {
        if (!app.prefs.isLoggedIn || FirebaseApp.getApps(app).isEmpty()) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                app.appScope.launch { app.api.registerPushToken(token, app.prefs.deviceId) }
            }
        }
    }
}
