package com.bikeguard.app.tracker

import android.content.Context
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.PendingEvent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Entry points shared by the service, the UI, the widget and FCM handlers. */
object GuardControl {
    fun setArmed(ctx: Context, on: Boolean) {
        val app = ctx.bikeApp
        app.prefs.parkingArmed = on
        app.prefs.stateDirty = true
        TrackerState.armed.value = on
        if (on) { app.prefs.trackingActive = true; ctx.tryStartTracking() }
        app.appScope.launch { app.sync.flush() }   // tell the server right away when online
    }

    /** Queue an alert in Room (so it survives being offline) and try to upload it now. */
    fun raise(ctx: Context, type: String, severity: String, data: JsonObject? = null) {
        val app = ctx.bikeApp
        val f = TrackerState.lastFix.value
        app.appScope.launch {
            app.db.events().insert(
                PendingEvent(
                    type = type, severity = severity, lat = f?.lat, lng = f?.lng,
                    data = data?.toString(), createdAt = System.currentTimeMillis()
                )
            )
            app.sync.flush()
        }
    }

    /** Manual SOS (held button): alert the Viewer and text the emergency contact immediately. */
    fun sos(ctx: Context) {
        raise(ctx, "sos", "critical")
        val f = TrackerState.lastFix.value
        SosController.sendNow(ctx.bikeApp, f?.lat, f?.lng)
    }
}

/** Start/stop tracking from places that are not the main UI (widget, Quick Settings tile). */
object TrackingControl {
    fun set(ctx: Context, on: Boolean) {
        val prefs = ctx.bikeApp.prefs
        if (!on && prefs.mode == com.bikeguard.app.data.MODE_ALWAYS) return // Always-on cannot be switched off from here
        prefs.trackingActive = on
        if (on) {
            ctx.tryStartTracking()
        } else {
            if (prefs.parkingArmed) GuardControl.setArmed(ctx, false)
            TrackingService.stop(ctx)
        }
    }
}
