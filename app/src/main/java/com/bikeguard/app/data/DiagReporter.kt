package com.bikeguard.app.data

import android.content.Context
import android.os.BatteryManager
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.ui.fmtHourMin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Reports what is happening inside the app to the bikeguard-diag Worker,
 * so Muse can see the tracker's state without asking for screenshots.
 * No location is ever sent: only role, tracking state, queued count, last
 * fix age, last sync error, battery and app version.
 * Best-effort only: never throws, never blocks the caller. */
class DiagReporter(
    private val ctx: Context,
    private val prefs: Prefs,
    private val dao: PendingDao,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val configured: Boolean get() =
        BuildConfig.DIAG_URL.startsWith("http") && BuildConfig.DIAG_TOKEN.isNotBlank()

    /** @return true when the Worker accepted the snapshot. Never throws. */
    suspend fun report(note: String): Boolean {
        if (!configured) return false
        val now = System.currentTimeMillis()
        return try {
            val fix = TrackerState.lastFix.value
            val queued = try {
                dao.count().first()
            } catch (e: Exception) {
                -1
            }
            val battery = try {
                ctx.getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (e: Exception) {
                -1
            }
            val payload = buildJsonObject {
                put("device_id", prefs.deviceId)
                put("role", prefs.appRole)
                put("version", BuildConfig.VERSION_NAME)
                put("tracking", TrackerState.running.value)
                put("armed", TrackerState.armed.value)
                put("queued", queued)
                put("last_fix_age_s", if (fix == null) -1 else ((now - fix.timeMs) / 1000).toInt())
                put("last_sync_error", prefs.lastSyncError)
                put("last_sync_at", if (prefs.lastSyncAttemptMs == 0L) "" else fmtHourMin(prefs.lastSyncAttemptMs))
                put("battery", battery)
                put("note", note.take(200))
            }.toString()
            val req = Request.Builder()
                .url(BuildConfig.DIAG_URL.trimEnd('/') + "/report?token=" + BuildConfig.DIAG_TOKEN)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val ok = http.newCall(req).execute().use { it.isSuccessful }
            prefs.lastDiagReportAtMs = now
            prefs.lastDiagReportOk = ok
            ok
        } catch (e: Exception) {
            prefs.lastDiagReportAtMs = now
            prefs.lastDiagReportOk = false
            false
        }
    }
}
