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

    // ---------- weekly report + export (v1.1.0) ----------

    data class WeeklySummary(val trips: Int, val km: Double, val minutes: Long, val topSpeed: Double)

    /** Last 7 days, from the loaded trip list. */
    val weeklySummary: WeeklySummary?
        get() {
            val list = trips ?: return null
            val since = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli()
            val week = list.filter { it.startedMs >= since }
            if (week.isEmpty()) return WeeklySummary(0, 0.0, 0, 0.0)
            return WeeklySummary(
                trips = week.size,
                km = week.sumOf { it.distanceM } / 1000.0,
                minutes = week.sumOf { (it.endedMs - it.startedMs) / 60_000 },
                topSpeed = week.maxOf { it.maxSpeedKmh },
            )
        }

    /** One CSV row per trip (last 30 days), shared as plain text. */
    fun weeklyCsv(): String {
        val head = "date,start,end,km,minutes,avg_kmh,max_kmh\n"
        val rows = (trips ?: emptyList()).joinToString("\n") { t ->
            val mins = (t.endedMs - t.startedMs) / 60_000
            "${t.startedAt.take(10)},${t.startedAt.drop(11).take(5)},${t.endedAt.drop(11).take(5)}," +
                "${"%.2f".format(t.distanceM / 1000.0)},$mins,${t.avgSpeedKmh.toInt()},${t.maxSpeedKmh.toInt()}"
        }
        return head + rows
    }

    /** GPX track of the most recent trip, shared as plain text. Null when there is no trip. */
    fun exportLatestTripGpx(onReady: (String?) -> Unit) {
        val id = selectedId ?: return onReady(null)
        val trip = trips?.maxByOrNull { it.startedMs } ?: return onReady(null)
        viewModelScope.launch {
            val p = try { app.api.tripPoints(id, trip.startedAt, trip.endedAt) } catch (e: IOException) { null }
            if (p.isNullOrEmpty()) { onReady(null); return@launch }
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"BikeGuard\">\n<trk><name>BikeGuard trip</name><trkseg>\n")
            for (pt in p) {
                sb.append("<trkpt lat=\"${pt.lat}\" lon=\"${pt.lng}\"><time>${pt.recordedAt}</time></trkpt>\n")
            }
            sb.append("</trkseg></trk></gpx>\n")
            onReady(sb.toString())
        }
    }
}
