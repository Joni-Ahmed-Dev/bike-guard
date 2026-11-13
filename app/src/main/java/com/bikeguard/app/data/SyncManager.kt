package com.bikeguard.app.data

import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Uploads the offline queue in batches. Safe to call from anywhere, any number of times. */
class SyncManager(
    private val dao: PendingDao,
    private val events: EventDao,
    private val api: SupabaseApi,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()

    /** @return true when the queue is empty (everything uploaded), false when something must be retried. */
    suspend fun flush(): Boolean = mutex.withLock {
        val ok = flushLocked()
        prefs.lastSyncAttemptMs = System.currentTimeMillis()
        prefs.lastSyncError = if (ok) "" else (api.lastSyncError ?: "no internet")
        ok
    }

    private suspend fun flushLocked(): Boolean {
        if (!prefs.isLoggedIn) return true
        return try {
            if (!prefs.deviceRegistered) {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                if (!api.registerDevice(prefs.deviceId, name)) return false
                prefs.deviceRegistered = true
            }
            var allSent = true
            while (true) {
                val batch = dao.next(100)
                if (batch.isEmpty()) break
                when (api.upload(batch.map { it.toDto(prefs.deviceId) })) {
                    UploadResult.OK, UploadResult.REJECTED -> dao.delete(batch.map { it.id })
                    UploadResult.RETRY -> { allSent = false; break }
                }
            }
            // alerts (tamper, theft, crash ...) go right after the locations
            while (allSent) {
                val batch = events.next(50)
                if (batch.isEmpty()) break
                when (api.uploadEvents(batch.map { it.toDto(prefs.deviceId) })) {
                    UploadResult.OK, UploadResult.REJECTED -> events.delete(batch.map { it.id })
                    UploadResult.RETRY -> { allSent = false; break }
                }
            }
            // mirror armed / odometer / limits to the server so the Viewer can show them
            if (allSent && prefs.stateDirty) {
                val patch = DeviceStatePatch(
                    armed = prefs.parkingArmed, trackingExpected = prefs.shouldTrack,
                    odometerKm = prefs.odometerKm.toDouble(),
                    nextServiceKm = prefs.nextServiceKm.takeIf { it > 0f }?.toDouble(),
                    speedLimitKmh = prefs.speedLimitKmh,
                )
                if (api.patchDeviceState(prefs.deviceId, patch)) prefs.stateDirty = false
            }
            allSent
        } catch (e: IOException) {
            false
        }
    }
}
