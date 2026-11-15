package com.bikeguard.app.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

/** Uploads the offline queue in batches. Safe to call from anywhere, any number of times. */
class SyncManager(
    private val dao: PendingDao,
    private val events: EventDao,
    private val expenses: ExpenseDao,
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
        val now = System.currentTimeMillis()
        // Backed off after repeated server rejections (v1.2.13): report the stored
        // reason without touching the network, and do NOT count this as another
        // rejection — or the backoff would re-arm itself forever.
        if (now < prefs.syncBackoffUntilMs) {
            prefs.lastSyncAttemptMs = now
            prefs.lastSyncError = prefs.syncBlockedReason
            return false
        }
        val prevError = prefs.lastSyncError
        val outcome = flushLocked()
        prefs.lastSyncAttemptMs = now
        prefs.lastSyncError = if (outcome.ok) "" else (outcome.error ?: "no internet")
        if (outcome.ok) {
            // A working flush clears any earlier rejection streak and backoff.
            if (prefs.serverRejectCount != 0 || prefs.syncBackoffUntilMs != 0L) {
                prefs.serverRejectCount = 0; prefs.syncBackoffUntilMs = 0L; prefs.syncBlockedReason = ""
            }
        } else {
            // A 403/409 is a server decision, not a blip: after 3 consecutive
            // rejections stop hammering the server and back off (15 min, then
            // 30, then 1 h). The queue stays put; nothing is dropped. Transient
            // failures (no internet / 5xx) never count here and keep retrying.
            val err = outcome.error.orEmpty()
            if (err.contains("server 403") || err.contains("server 409")) {
                val count = prefs.serverRejectCount + 1
                prefs.serverRejectCount = count
                if (count >= 3) {
                    val backoffMs = when (count) {
                        3 -> 15 * 60_000L
                        4 -> 30 * 60_000L
                        else -> 60 * 60_000L
                    }
                    prefs.syncBackoffUntilMs = now + backoffMs
                    prefs.syncBlockedReason = err
                    scope?.launch { diag?.report("sync blocked: $err, backing off ${backoffMs / 60_000} min") }
                }
            }
        }
        // Report state changes to the diagnostics Worker (only when the
        // failure reason changes, so a stuck offline phone does not spam it).
        if (!outcome.ok && outcome.error != prevError) {
            scope?.launch { diag?.report("sync failed: ${outcome.error ?: "no internet"}") }
        } else if (outcome.ok && prevError.isNotBlank()) {
            scope?.launch { diag?.report("sync recovered") }
        }
        outcome.ok
    }

    /** Syncs the expense log with the server (v1.2.15). Uploads unsynced local rows
     *  (each keyed by its client UUID, so a retry never duplicates), then downloads
     *  any server rows missing locally — so the log comes back after a reinstall or
     *  a destructive Room migration. Separate from flush(): expenses belong to the
     *  Remote phone, which never runs the location flush, so the Expenses screen
     *  calls this directly. Safe to call from either role: the rows are
     *  owner-scoped. Returns false offline or when the server refuses; a 400/422
     *  keeps the rows unsynced instead of dropping a user's expense. */
    suspend fun syncExpenses(): Boolean {
        if (!prefs.isLoggedIn) return false
        return try {
            val pending = expenses.unsynced()
            if (pending.isNotEmpty()) {
                // Give every unsynced row its client UUID first, then upload in
                // one idempotent batch; only mark synced on success.
                val ready = pending.map { row ->
                    if (row.serverId.isBlank()) {
                        val id = UUID.randomUUID().toString()
                        expenses.setServerId(row.id, id)
                        row.copy(serverId = id)
                    } else row
                }
                val up = api.uploadExpenses(ready.map { it.toDto() })
                if (up.result != UploadResult.OK) return false
                expenses.markSynced(ready.map { it.id })
            }
            // Download: any server row missing locally is restored (fresh install,
            // or a local wipe). Local rows are never overwritten or deleted here.
            val remote = api.listExpenses() ?: return false
            for (dto in remote) {
                if (expenses.byServerId(dto.id) == null) {
                    expenses.insert(
                        ExpenseRecord(
                            category = dto.category, amount = dto.amount, note = dto.note.orEmpty(),
                            spentOn = expenseDateToMs(dto.spentOn), serverId = dto.id, synced = true,
                            createdAt = dto.createdAt?.let { isoToMs(it) } ?: System.currentTimeMillis(),
                        )
                    )
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Uploads one queue batch (locations or alerts). A 400/422 splits the batch in halves
     * and retries, so one bad row cannot wipe the other rows: only rows the server
     * rejects one by one are dropped. A transient failure (5xx / network / 403 / 409)
     * stops the flush and leaves everything still queued for the next try; repeated
     * 403/409 rejections back off in flush() instead of looping forever.
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
            // Adopt the server's odometer / next-service values once per install, BEFORE
            // the stateDirty patch below can push local 0s up. A reinstall wipes Prefs
            // (odometer 0, stateDirty true by default) while the server row still holds
            // the real values; without this the first flush after a reinstall would
            // zero the server's odometer. The odometer only grows, so adopt max():
            // a server value lower than local (e.g. zeroed by the old bug) never
            // pulls the local odometer backwards. A failed fetch leaves the flag
            // false and the patch omits the odometer, so nothing is overwritten.
            if (!prefs.odometerAdopted) {
                try {
                    val row = api.listDevices()?.firstOrNull { it.id == prefs.deviceId }
                    if (row != null) {
                        val serverOdo = row.odometerKm?.toFloat() ?: 0f
                        if (serverOdo > prefs.odometerKm) prefs.odometerKm = serverOdo
                        val serverNext = row.nextServiceKm?.toFloat() ?: 0f
                        if (serverNext > prefs.nextServiceKm) prefs.nextServiceKm = serverNext
                        prefs.odometerAdopted = true
                    }
                } catch (e: IOException) {
                    // offline: adopt on the next flush; the patch below omits odometer
                }
            }
            // Adopt the server's service interval once per install, next to the
            // odometer adopt (v1.2.16). A reinstall wipes serviceIntervalKm to 0,
            // and a mark_serviced before the first settings sync would otherwise
            // compute from interval 0. Set the interval directly — never recompute
            // nextServiceKm here; the adopted server value is the anchor. The
            // Tracker never writes service_km back (Remote-owned), so this sets
            // no stateDirty.
            if (!prefs.serviceIntervalAdopted) {
                try {
                    val s = api.getDeviceSettings(prefs.deviceId)
                    if (s != null) {
                        prefs.serviceIntervalKm = s.serviceKm
                        prefs.serviceIntervalAdopted = true
                    }
                } catch (e: IOException) {
                    // offline: adopt on the next flush
                }
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
                    // Only send the odometer once adopted: a fresh install's local 0
                    // must never overwrite the server's stored value (v1.2.11).
                    odometerKm = prefs.odometerKm.toDouble().takeIf { prefs.odometerAdopted },
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
