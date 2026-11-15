package com.bikeguard.app.push

import com.bikeguard.app.BikeGuardApp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Registers this phone's FCM token with the server. Silently does nothing when Firebase isn't configured. */
object PushRegistrar {
    private const val PUSH_WORKER_URL = "https://bikeguard-push.aminuljoni180.workers.dev"
    private val http = OkHttpClient.Builder().build()

    fun register(app: BikeGuardApp) {
        if (!app.prefs.isLoggedIn || FirebaseApp.getApps(app).isEmpty()) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                app.appScope.launch {
                    app.api.registerPushToken(token, app.prefs.deviceId)
                    registerWithPushWorker(token, app.prefs.deviceId, app.prefs.userId)
                }
            }
        }
    }

    /** Tell the push Worker this phone's token, so a command webhook can reach it.
     *  Best effort and silent on failure: the Worker only needs the token when a
     *  command arrives, and the next app launch registers again. */
    suspend fun registerWithPushWorker(token: String, deviceId: String, ownerId: String?) {
        runCatching {
            val payload = buildJsonObject {
                put("token", token)
                put("device_id", deviceId)
                if (ownerId != null) put("owner_id", ownerId)
            }.toString()
            val req = Request.Builder()
                .url("$PUSH_WORKER_URL/register")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            withContext(Dispatchers.IO) { http.newCall(req).execute().close() }
        }
    }
}
