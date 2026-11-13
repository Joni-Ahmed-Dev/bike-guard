package com.bikeguard.app.ui.trips

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.data.DeviceDto
import com.bikeguard.app.data.PointDto
import com.bikeguard.app.data.TripDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

class TripsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp

    var devices by mutableStateOf<List<DeviceDto>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var trips by mutableStateOf<List<TripDto>?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set

    var openTrip by mutableStateOf<TripDto?>(null); private set
    var points by mutableStateOf<List<PointDto>>(emptyList()); private set
    var pointsLoading by mutableStateOf(false); private set
    var pointsError by mutableStateOf(false); private set

    private var job: Job? = null

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true; error = false
            try {
                val list = app.api.listDevices()
                if (list == null) {
                    error = true
                } else {
                    devices = list
                    if (selectedId == null || list.none { it.id == selectedId }) {
                        selectedId = (list.firstOrNull { it.id != app.prefs.deviceId } ?: list.firstOrNull())?.id
                    }
                    selectedId?.let { id ->
                        val t = app.api.trips(id, Instant.now().minus(30, ChronoUnit.DAYS).toString())
                        if (t == null) error = true else trips = t
                    }
                }
            } catch (e: IOException) {
                error = true
            }
            loading = false
        }
    }

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id; trips = null
        load()
    }

    fun open(trip: TripDto) {
        val id = selectedId ?: return
        openTrip = trip; points = emptyList(); pointsLoading = true; pointsError = false
        viewModelScope.launch {
            try {
                val p = app.api.tripPoints(id, trip.startedAt, trip.endedAt)
                if (p == null) pointsError = true else points = p
            } catch (e: IOException) {
                pointsError = true
            }
            pointsLoading = false
        }
    }

    fun close() { openTrip = null; points = emptyList() }
}
