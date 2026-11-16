package com.bikeguard.app.push

import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.data.AppLog
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
        if (!app.prefs.isLoggedIn) return
        if (FirebaseApp.getApps(app).isEmpty()) {
            // Without Firebase there is no push, so a Remote command can only reach a
            // stopped tracker through the 15 min Watchdog. The log line makes that visible.
            AppLog.i("push", "register skipped: firebase not configured")
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    app.appScope.launch {
                        val ok = try { app.api.registerPushToken(token, app.prefs.deviceId) } catch (e: Exception) { false }
                        AppLog.i("push", if (ok) "token registered" else "token register failed")
                        registerWithPushWorker(token, app.prefs.deviceId, app.prefs.userId)
                    }
                }
                .addOnFailureListener { e -> AppLog.i("push", "no fcm token: " + (e.message ?: "?")) }
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
