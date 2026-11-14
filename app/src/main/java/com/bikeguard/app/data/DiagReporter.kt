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
 * Best-effort only: never throws, never blocks the caller.
 *
 * NOTE (v1.2.1, issue 2): the release build shipped with an empty
 * DIAG_TOKEN — the CI workflow never passed the DIAG_TOKEN secret into the
 * build, so BuildConfig.DIAG_TOKEN was "" and [configured] was false, so
 * [report] returned false before ever touching the network and every tap
 * on Send Report showed "Failed" even on a perfectly online phone. The
 * Worker itself was verified live and correct (wrong token -> 403
 * "forbidden", never a 500). Until the workflow passes DIAG_TOKEN, this
 * class now at least reports WHY it failed ([ReportOutcome.NOT_CONFIGURED])
 * instead of blaming the connection. */
enum class ReportOutcome { SENT, NOT_CONFIGURED, REJECTED, FAILED }

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

    /** Outcome of the last [reportDetailed] call; null before any attempt. */
    var lastOutcome: ReportOutcome? = null
        private set

    /** HTTP status of the last attempt; 0 when no response was received. */
    var lastHttpCode: Int = 0
        private set

    /** @return true when the Worker accepted the snapshot. Never throws. */
    suspend fun report(note: String): Boolean =
        reportDetailed(note) == ReportOutcome.SENT

    /** Like [report] but says why it failed, so the UI can show the real
     * reason instead of a generic "check connection". Never throws. */
    suspend fun reportDetailed(note: String): ReportOutcome {
        if (!configured) {
            lastOutcome = ReportOutcome.NOT_CONFIGURED
            lastHttpCode = 0
            prefs.lastDiagReportAtMs = System.currentTimeMillis()
            prefs.lastDiagReportOk = false
            return ReportOutcome.NOT_CONFIGURED
        }
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
                put("fix_status", TrackerState.fixStatus.value)
                put("has_fix", TrackerState.hasFix.value)
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
            val outcome = http.newCall(req).execute().use { resp ->
                lastHttpCode = resp.code
                if (resp.isSuccessful) ReportOutcome.SENT else ReportOutcome.REJECTED
            }
            lastOutcome = outcome
            prefs.lastDiagReportAtMs = now
            prefs.lastDiagReportOk = outcome == ReportOutcome.SENT
            outcome
        } catch (e: Exception) {
            lastOutcome = ReportOutcome.FAILED
            lastHttpCode = 0
            prefs.lastDiagReportAtMs = now
            prefs.lastDiagReportOk = false
            ReportOutcome.FAILED
        }
    }
}
