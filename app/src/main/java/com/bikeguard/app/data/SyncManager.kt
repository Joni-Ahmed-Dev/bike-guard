package com.bikeguard.app.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Uploads the offline queue in batches. Safe to call from anywhere, any number of times. */
class SyncManager(
    private val dao: PendingDao,
    private val events: EventDao,
    private val api: SupabaseApi,
    private val prefs: Prefs,
    private val diag: DiagReporter? = null,
    private val scope: CoroutineScope? = null,
) {
    private val mutex = Mutex()

    /** One flush's outcome: whether everything uploaded, and the local reason when it did not. */
    data class FlushOutcome(val ok: Boolean, val error: String? = null)

    /** @return true when everything uploaded, false when something must be retried. */
    suspend fun flush(): Boolean = mutex.withLock {
        val prevError = prefs.lastSyncError
        val outcome = flushLocked()
        prefs.lastSyncAttemptMs = System.currentTimeMillis()
        prefs.lastSyncError = if (outcome.ok) "" else (outcome.error ?: "no internet")
        // Report state changes to the diagnostics Worker (only when the
        // failure reason changes, so a stuck offline phone does not spam it).
        if (!outcome.ok && outcome.error != prevError) {
            scope?.launch { diag?.report("sync failed: ${outcome.error ?: "no internet"}") }
        } else if (outcome.ok && prevError.isNotBlank()) {
            scope?.launch { diag?.report("sync recovered") }
        }
        outcome.ok
    }

    private suspend fun flushLocked(): FlushOutcome {
        if (!prefs.isLoggedIn) return FlushOutcome(false, "signed out")
        // The Remote phone never registers itself as a device and never uploads locations.
        if (prefs.isRemote) return FlushOutcome(true)
        return try {
            if (!prefs.deviceRegistered) {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val reg = api.registerDevice(prefs.deviceId, name, prefs.userId)
                if (!reg.ok) return FlushOutcome(false, "register: ${reg.error}")
                prefs.deviceRegistered = true
            }
            val ownerId = prefs.userId
            var ok = true
            var error: String? = null
            fun fail(e: String?) { ok = false; if (error == null) error = e }

            // locations: one bad batch must not block the alerts behind it
            while (true) {
                val batch = dao.next(100)
                if (batch.isEmpty()) break
                val up = api.upload(batch.map { it.toDto(prefs.deviceId, ownerId) })
                when (up.result) {
                    UploadResult.OK, UploadResult.REJECTED -> dao.delete(batch.map { it.id })
                    UploadResult.RETRY -> { fail("upload: ${up.error}"); break }
                }
            }
            // alerts (tamper, theft, crash ...) upload even when the locations failed
            while (true) {
                val batch = events.next(50)
                if (batch.isEmpty()) break
                val up = api.uploadEvents(batch.map { it.toDto(prefs.deviceId, ownerId) })
                when (up.result) {
                    UploadResult.OK, UploadResult.REJECTED -> events.delete(batch.map { it.id })
                    UploadResult.RETRY -> { fail("events: ${up.error}"); break }
                }
            }
            // mirror armed / odometer / limits to the server so the Viewer can show them
            if (prefs.stateDirty) {
                val patch = DeviceStatePatch(
                    armed = prefs.parkingArmed, trackingExpected = prefs.shouldTrack,
                    odometerKm = prefs.odometerKm.toDouble(),
                    nextServiceKm = prefs.nextServiceKm.takeIf { it > 0f }?.toDouble(),
                    speedLimitKmh = prefs.speedLimitKmh,
                )
                val res = api.patchDeviceState(prefs.deviceId, patch)
                if (res.ok) prefs.stateDirty = false else fail("patch: ${res.error}")
            }
            FlushOutcome(ok, error)
        } catch (e: IOException) {
            FlushOutcome(false, if (e is java.net.SocketTimeoutException) "timed out" else "no internet")
        }
    }
}
