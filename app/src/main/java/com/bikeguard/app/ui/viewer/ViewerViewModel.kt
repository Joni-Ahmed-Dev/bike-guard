package com.bikeguard.app.ui.viewer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.data.DeviceDto
import com.bikeguard.app.data.LocationDto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Phase 1 polls the server every 5 s while the screen is visible (simple and dependable).
 * Phase 3 swaps this for Supabase Realtime; the UI above does not change.
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

    /** Call from repeatOnLifecycle(STARTED) so polling stops when the app is in the background. */
    suspend fun pollLoop() {
        var n = 0
        while (currentCoroutineContext().isActive) {
            if (!devicesLoaded || n % 12 == 0) loadDevices()
            selectedId?.let { refresh(it, full = n % 6 == 0 || trail.isEmpty()) }
            n++
            delay(5_000)
        }
    }

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id; latest = null; trail = emptyList(); loaded = false
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
            latest = newest
        } catch (e: IOException) {
            connectionIssue = true
        }
    }
}
