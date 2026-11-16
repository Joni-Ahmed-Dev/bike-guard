package com.bikeguard.app.tracker

import android.content.Context
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.AppLog
import com.bikeguard.app.data.PendingEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Entry points shared by the service, the UI, the widget and FCM handlers. */
object GuardControl {
    /** Alerts queue cap (v1.2.13): the locations queue already trims to 50,000,
     *  but the alerts queue had no cap at all — a flapping guard could grow it
     *  without bound while offline. 500 is far more than a day of alerts. */
    private const val MAX_EVENTS = 500

    /**
     * [latch] records a manual disarm, so auto re-arm waits 10 minutes. The guard schedule's
     * own window-end disarm passes latch = false, so it never blocks the next window.
     * [local] is false when the change came from the server: server changes do not stamp
     * [Prefs.lastGuardChangeMs], so they never outrank a newer local arm/disarm.
     */
    fun setArmed(ctx: Context, on: Boolean, latch: Boolean = true, local: Boolean = true) {
        val app = ctx.bikeApp
        AppLog.i("guard", if (on) "armed" else "disarmed")
        app.prefs.parkingArmed = on
        // A disarm drops the saved anchor (every disarm path runs through here:
        // UI toggle, widget, remote command, server settings sync, schedule
        // window end), so the next arm re-anchors at the new spot instead of
        // measuring against where the bike was parked last time.
        if (!on) app.prefs.clearGuardAnchor()
        if (local) app.prefs.lastGuardChangeMs = System.currentTimeMillis()
        app.prefs.stateDirty = true
        TrackerState.armed.value = on
        if (!on && latch) app.prefs.manualDisarmAtMs = System.currentTimeMillis()
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
            app.db.events().trim(MAX_EVENTS)
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
    // The pending stop (below): a fresh ON tap cancels it, so a quick off/on
    // can never stop tracking that was just switched back on.
    private var stopJob: Job? = null

    /**
     * Master switch (v1.2.40): the one shared button on both phones. ON means
     * everything on (tracking, guard, live); OFF means everything off — no
     * exception for Always-on mode or an armed guard. The Remote phone's tap
     * arrives as a master_on/master_off command and lands here through the
     * service; this is the local tap (tracker ring, parking toggle, widget).
     */
    fun set(ctx: Context, on: Boolean) {
        val app = ctx.bikeApp
        val prefs = app.prefs
        // The Remote phone never tracks: the widget and tile refuse to start it here.
        if (on && prefs.isRemote) return
        stopJob?.cancel()
        prefs.masterOn = on
        prefs.trackingActive = on
        AppLog.i("master", if (on) "on (local)" else "off (local)")
        if (on) {
            // Arms the guard, starts tracking and flushes the new state.
            GuardControl.setArmed(ctx, true)
            ctx.tryStartTracking()
        } else {
            // Disarms the guard and clears the anchor.
            GuardControl.setArmed(ctx, false)
            app.prefs.trackingStartedAtMs = 0L // explicit stop: the timer ends here
            // Flush the new state BEFORE the service stops (v1.2.38 lesson):
            // stopping first kills the scope that would have reported back, so
            // the Remote's switch would wait for a confirmation that never came.
            stopJob = app.appScope.launch {
                withTimeoutOrNull(8_000) { app.sync.flush() }
                TrackingService.stop(ctx)
            }
        }
        // The log rides with the report (v1.2.41), so a local tap is readable
        // without waiting for the 15 min heartbeat.
        app.appScope.launch { app.diag.reportAuto("master " + if (on) "on" else "off") }
    }
}
