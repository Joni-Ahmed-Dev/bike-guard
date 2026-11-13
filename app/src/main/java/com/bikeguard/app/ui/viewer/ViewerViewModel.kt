package com.bikeguard.app.ui.viewer

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.data.DeviceDto
import com.bikeguard.app.data.GeofenceDto
import com.bikeguard.app.data.LocationDto
import com.bikeguard.app.data.RtEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant

/**
 * Live updates arrive over Realtime; a slow poll stays as a safety net (and is the fallback when the
 * websocket cannot connect). Also carries the remote-control, geofence and share-link actions.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp

    var devices by mutableStateOf<List<DeviceDto>>(emptyList()); private set
    var devicesLoaded by mutableStateOf(false); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var latest by mutableStateOf<LocationDto?>(null); private set
    var trail by mutableStateOf<List<LocationDto>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var connectionIssue by mutableStateOf(false); private set

    var commandNote by mutableStateOf<Int?>(null); private set
    var sirenOn by mutableStateOf(false); private set
    var geofences by mutableStateOf<List<GeofenceDto>>(emptyList()); private set

    val selectedDevice: DeviceDto? get() = devices.firstOrNull { it.id == selectedId }

    /** Call from repeatOnLifecycle(STARTED) so everything stops when the app is in the background. */
    suspend fun pollLoop() {
        coroutineScope {
            app.realtime.acquire()
            val rt = launch { app.realtime.events.collect { onRealtime(it) } }
            try {
                var n = 0
                while (isActive) {
                    if (!devicesLoaded || n % 6 == 0) loadDevices()
                    selectedId?.let { refresh(it, full = n % 6 == 0 || trail.isEmpty()) }
                    n++
                    // Low-data mode: poll every 15 s instead of 5 s when the websocket is down.
                    delay(if (app.realtime.connected.value) 20_000 else if (app.prefs.lowDataMode) 15_000 else 5_000)
                }
            } finally {
                rt.cancel()
                app.realtime.release()
            }
        }
    }

    private fun onRealtime(e: RtEvent) {
        when (e) {
            is RtEvent.Loc -> if (e.loc.deviceId == selectedId) {
                val cur = latest
                if (cur == null || e.loc.recordedAtMs >= cur.recordedAtMs) latest = e.loc
                if (trail.lastOrNull()?.recordedAt != e.loc.recordedAt) trail = (trail + e.loc).takeLast(300)
                loaded = true
                connectionIssue = false
            }
            is RtEvent.Dev -> e.dev.id?.let { id -> devices = devices.map { if (it.id == id) e.dev else it } }
            is RtEvent.Ev -> Unit // alerts are handled by the Alerts screen
        }
    }

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id; latest = null; trail = emptyList(); loaded = false
        app.prefs.remoteTargetDeviceId = id
        viewModelScope.launch { refresh(id, full = true) }
    }

    private suspend fun loadDevices() {
        try {
            val list = app.api.listDevices()
            if (list == null) { connectionIssue = true; return }
            connectionIssue = false
            devices = list
            devicesLoaded = true
            if (selectedId == null || list.none { it.id == selectedId }) {
                selectedId = (list.firstOrNull { it.id != app.prefs.deviceId } ?: list.firstOrNull())?.id
                selectedId?.let { app.prefs.remoteTargetDeviceId = it }
            }
        } catch (e: IOException) {
            connectionIssue = true
        }
    }

    private suspend fun refresh(id: String, full: Boolean) {
        try {
            val rows = app.api.recent(id, if (full) 120 else 1)
            if (rows == null) { connectionIssue = true; return }
            connectionIssue = false
            if (id != selectedId) return
            loaded = true
            if (rows.isEmpty()) return
            val newest = rows.first()
            trail = if (full) rows.asReversed()
            else if (trail.lastOrNull()?.recordedAt != newest.recordedAt) (trail + newest).takeLast(300)
            else trail
            if (latest == null || newest.recordedAtMs >= (latest?.recordedAtMs ?: 0L)) latest = newest
        } catch (e: IOException) {
            connectionIssue = true
        }
    }

    // ---------- remote control ----------

    fun send(type: String, arg: String? = null) {
        val id = selectedId ?: return
        viewModelScope.launch {
            commandNote = R.string.cmd_sending
            try {
                val cid = app.api.sendCommand(id, type, arg)
                if (cid == null) { commandNote = R.string.cmd_failed; return@launch }
                commandNote = R.string.cmd_sent
                repeat(12) {                          // wait up to ~36 s for the tracker to confirm
                    delay(3_000)
                    val st = try { app.api.commandStatus(cid) } catch (e: IOException) { null }
                    if (st == "done") {
                        // Only a confirmed command changes the siren state, never the send itself.
                        if (type == "siren_on") sirenOn = true
                        if (type == "siren_off") sirenOn = false
                        commandNote = R.string.cmd_done; return@launch
                    }
                }
                commandNote = R.string.cmd_failed // no confirmation within ~36 s
            } catch (e: IOException) {
                commandNote = R.string.cmd_failed
            }
        }
    }

    // ---------- geofences ----------

    fun loadGeofences() {
        viewModelScope.launch { try { app.api.listGeofences()?.let { geofences = it } } catch (e: IOException) { } }
    }

    fun addGeofence(name: String, radiusM: Int): Boolean {
        val here = latest ?: return false
        viewModelScope.launch {
            try {
                app.api.addGeofence(GeofenceDto(name = name.trim().ifBlank { "Place" }, lat = here.lat, lng = here.lng, radiusM = radiusM))
                loadGeofences()
            } catch (e: IOException) { }
        }
        return true
    }

    fun deleteGeofence(id: String) {
        viewModelScope.launch { try { app.api.deleteGeofence(id); loadGeofences() } catch (e: IOException) { } }
    }

    // ---------- temporary share link ----------

    val canShare: Boolean get() = BuildConfig.SHARE_BASE_URL.isNotBlank()

    /** Creates a link that stops working after [hours]. [onReady] gets the URL, or null on failure. */
    fun createShare(hours: Int, onReady: (String?) -> Unit) {
        val id = selectedId
        if (id == null || !canShare) { onReady(null); return }
        viewModelScope.launch {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }   // 192 bits: not guessable
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val expires = Instant.now().plusSeconds(hours * 3600L).toString()
            val ok = try { app.api.createShareLink(token, id, expires) } catch (e: IOException) { false }
            onReady(if (ok) BuildConfig.SHARE_BASE_URL.trimEnd('/') + "/#" + token else null)
        }
    }

    fun stopSharing() {
        val id = selectedId ?: return
        viewModelScope.launch { try { app.api.revokeShareLinks(id) } catch (e: IOException) { } }
    }
}
