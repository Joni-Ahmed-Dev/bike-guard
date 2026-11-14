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
import com.bikeguard.app.data.pickActiveDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

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

    /**
     * Today's ride distance in metres, recomputed from the stored points with
     * [filteredDistanceM] so parked GPS jitter does not count. Null while it is still
     * loading (or when the points could not be read): callers fall back to the
     * server trip total. 0.0 when there is no ride today.
     */
    var todayDistanceM by mutableStateOf<Double?>(null); private set

    private var job: Job? = null
    private var todayJob: Job? = null

    fun load() {
        job?.cancel()
        todayJob?.cancel()
        todayDistanceM = null
        job = viewModelScope.launch {
            loading = true; error = false
            try {
                val list = app.api.listDevices()
                if (list == null) {
                    error = true; trips = null
                } else {
                    devices = list
                    // Follow the live row on every load, like the Viewer: a
                    // re-registered tracker gets a new row, and the first-created
                    // row would show an empty trip list.
                    val activeId = pickActiveDevice(list, app.prefs.deviceId)?.id
                    if (activeId != selectedId) { selectedId = activeId; trips = null }
                    selectedId?.let { id ->
                        val t = app.api.trips(id, Instant.now().minus(30, ChronoUnit.DAYS).toString())
                        if (t == null) { error = true; trips = null } else { trips = t; refreshTodayDistance() }
                    }
                }
            } catch (e: IOException) {
                error = true; trips = null
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

    /** Recomputes today's distance from the stored points, jitter removed (see [filteredDistanceM]). */
    private fun refreshTodayDistance() {
        todayJob?.cancel()
        val list = trips ?: return
        val dayStart = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayTrips = list.filter { it.startedMs >= dayStart }
        if (todayTrips.isEmpty()) { todayDistanceM = 0.0; return }
        val id = selectedId ?: return
        todayJob = viewModelScope.launch {
            var total = 0.0
            try {
                for (t in todayTrips) {
                    // Points unreadable: stay null so the Dashboard falls back to the server total.
                    val p = app.api.tripPoints(id, t.startedAt, t.endedAt) ?: return@launch
                    total += filteredDistanceM(p)
                }
                todayDistanceM = total
            } catch (e: IOException) { /* keep null: Dashboard falls back to the server total */ }
        }
    }

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
                "${"%.2f".format(Locale.US, t.distanceM / 1000.0)},$mins,${t.avgSpeedKmh.toInt()},${t.maxSpeedKmh.toInt()}"
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
