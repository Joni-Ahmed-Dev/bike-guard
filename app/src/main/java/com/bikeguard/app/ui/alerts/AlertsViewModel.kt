package com.bikeguard.app.ui.alerts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.data.EventDto
import com.bikeguard.app.data.RtEvent
import kotlinx.coroutines.coroutineScope
import java.io.IOException

class AlertsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp

    var events by mutableStateOf<List<EventDto>>(emptyList()); private set
    var deviceNames by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var unread by mutableIntStateOf(0); private set

    suspend fun refresh() {
        try {
            val list = app.api.listEvents(100)
            if (list == null) error = true else { events = list; error = false; recount() }
            if (deviceNames.isEmpty()) {
                app.api.listDevices()?.let { d -> deviceNames = d.associate { (it.id ?: "") to it.name } }
            }
        } catch (e: IOException) {
            error = true
        }
        loaded = true
    }

    /** Alerts raised by the tracker phone itself are not "unread news" for the person holding it. */
    private fun recount() {
        val seen = app.prefs.lastAlertsSeenMs
        unread = events.count { it.createdMs > seen && it.deviceId != app.prefs.deviceId }
    }

    fun markSeen() {
        app.prefs.lastAlertsSeenMs = System.currentTimeMillis()
        unread = 0
    }

    /** While the Alerts screen is visible: new alerts slide in over Realtime. */
    suspend fun live() {
        coroutineScope {
            app.realtime.acquire()
            try {
                app.realtime.events.collect { e ->
                    if (e is RtEvent.Ev && events.none { it.id != null && it.id == e.ev.id }) {
                        events = (listOf(e.ev) + events).take(100)
                        markSeen()
                    }
                }
            } finally {
                app.realtime.release()
            }
        }
    }
}
