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

    /**
     * Uploads one queue batch (locations or alerts). A 400/422 splits the batch in halves
     * and retries, so one bad row cannot wipe the other rows: only rows the server
     * rejects one by one are dropped. A transient failure (5xx / network / 409
     * re-register) stops the flush and leaves everything still queued for the next try.
     * @return null when the whole batch is resolved, the retry reason otherwise.
     */
    private suspend fun <T> uploadSplit(
        batch: List<T>,
        upload: suspend (List<T>) -> SupabaseApi.UploadOutcome,
        delete: suspend (List<T>) -> Unit,
    ): String? {
        val stack = ArrayDeque<List<T>>()
        stack.addLast(batch)
        while (stack.isNotEmpty()) {
            val chunk = stack.removeLast()
            val up = upload(chunk)
            when (up.result) {
                UploadResult.OK -> delete(chunk)
                UploadResult.REJECTED -> {
                    if (chunk.size == 1) {
                        delete(chunk) // this row alone is bad; drop only it
                    } else {
                        val mid = chunk.size / 2
                        stack.addLast(chunk.subList(mid, chunk.size))
                        stack.addLast(chunk.subList(0, mid))
                    }
                }
                UploadResult.RETRY -> return up.error // transient: keep the rest queued
            }
        }
        return null
    }

    private suspend fun flushLocked(): FlushOutcome {
        if (!prefs.isLoggedIn) return FlushOutcome(false, "signed out")
        // The Remote phone never registers itself as a device and never uploads locations.
        if (prefs.isRemote) return FlushOutcome(true)
        return try {
            if (!prefs.deviceRegistered) {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                if (!prefs.hasStoredDeviceId) {
                    // Reinstall / app-data clear wipes the stored device id, so without this
                    // the phone would mint a fresh random id and register a second device row
                    // (that is how the four ITEL rows happened). Rejoin the row already
                    // stamped with this phone's hardware key instead. Offline the lookup
                    // throws and the flush retries before anything is minted or registered.
                    prefs.hardwareKey?.let { key ->
                        api.findDeviceByHardwareKey(key)?.let { prefs.adoptDeviceId(it) }
                    }
                }
                val reg = api.registerDevice(prefs.deviceId, name, prefs.userId)
                if (!reg.ok) return FlushOutcome(false, "register: ${reg.error}")
                prefs.deviceRegistered = true
            }
            // Stamp this phone's hardware key on its server row, once. On the v1.2.2 ->
            // v1.2.3 upgrade the row already exists and deviceRegistered is already true,
            // so this — not the branch above — is what links the live row to the phone;
            // without it the next reinstall would still create a new row. A failed stamp
            // (e.g. migration 0008 is not applied yet) only retries on the next flush
            // and never fails the upload below.
            prefs.hardwareKey?.let { key ->
                if (!prefs.hardwareKeyStamped && api.stampHardwareKey(prefs.deviceId, key)) prefs.hardwareKeyStamped = true
            }
            val ownerId = prefs.userId
            var ok = true
            var error: String? = null
            fun fail(e: String?) { ok = false; if (error == null) error = e }

            // locations: one bad row must not wipe the whole batch — a 400/422
            // splits the batch in halves until the bad row is isolated, so only
            // rows the server rejects one by one are dropped
            while (true) {
                val batch = dao.next(100)
                if (batch.isEmpty()) break
                val err = uploadSplit(
                    batch,
                    upload = { chunk -> api.upload(chunk.map { it.toDto(prefs.deviceId, ownerId) }) },
                    delete = { chunk -> dao.delete(chunk.map { it.id }) },
                )
                if (err != null) { fail("upload: $err"); break }
            }
            // alerts (tamper, theft, crash ...) upload even when the locations failed.
            // Same split treatment as locations: one bad alert must not wipe the
            // whole batch of 50.
            while (true) {
                val batch = events.next(50)
                if (batch.isEmpty()) break
                val err = uploadSplit(
                    batch,
                    upload = { chunk -> api.uploadEvents(chunk.map { it.toDto(prefs.deviceId, ownerId) }) },
                    delete = { chunk -> events.delete(chunk.map { it.id }) },
                )
                if (err != null) { fail("events: $err"); break }
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
