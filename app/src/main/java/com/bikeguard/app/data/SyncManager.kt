package com.bikeguard.app.data

import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Uploads the offline queue in batches. Safe to call from anywhere, any number of times. */
class SyncManager(
    private val dao: PendingDao,
    private val api: SupabaseApi,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()

    /** @return true when the queue is empty (everything uploaded), false when something must be retried. */
    suspend fun flush(): Boolean = mutex.withLock {
        if (!prefs.isLoggedIn) return@withLock true
        try {
            if (!prefs.deviceRegistered) {
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                if (!api.registerDevice(prefs.deviceId, name)) return@withLock false
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
            allSent
        } catch (e: IOException) {
            false
        }
    }
}
