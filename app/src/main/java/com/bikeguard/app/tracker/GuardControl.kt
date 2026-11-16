package com.bikeguard.app.tracker

import android.content.Context
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.AppLog
import com.bikeguard.app.data.PendingEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException

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

    /**
     * Audit C1 (v1.2.44): flush a pending guard_armed value to the device_settings row.
     * The PATCH is field-scoped (patchTrackerSettings) and never bumps updated_at, so it
     * cannot look like new Remote work. A filtered PATCH on a missing row succeeds
     * without writing anything, so unless the caller already knows a row exists
     * ([rowExists]) the row is checked first: a missing row or any failed check leaves
     * the value pending for the next syncSettings poll or Watchdog tick. Returns true
     * when nothing is pending or the PATCH succeeded. The flag is cleared only when
     * the value still pending is the one just patched, so a newer arm/disarm queued
     * meanwhile is never dropped.
     */
    suspend fun flushPendingGuardArmed(app: BikeGuardApp, rowExists: Boolean = false): Boolean {
        val pending = app.prefs.pendingGuardArmed ?: return true
        if (!rowExists) {
            val exists = try { app.api.deviceSettingsRowExists(app.prefs.deviceId) } catch (e: IOException) { null }
            if (exists != true) return false
        }
        val ok = app.api.patchTrackerSettings(app.prefs.deviceId, pending, null)
        if (ok && app.prefs.pendingGuardArmed == pending) {
            app.prefs.pendingGuardArmed = null
            AppLog.i("guard", "pending guard patch sent")
        }
        return ok
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
        prefs.lastLocalMasterMs = System.currentTimeMillis()
        prefs.masterOn = on
        prefs.trackingActive = on
        AppLog.i("master", if (on) "on (local)" else "off (local)")
        if (on) {
            // The guard no longer arms at once (v1.2.42): it arms itself 5
            // minutes after the bike stops. A fresh ON clears the
            // manual-disarm latch, so it is never blocked for 10 minutes.
            // Live starts with the service (beginUpdates goes live while the
            // master is on), so no guard call is needed here.
            prefs.manualDisarmAtMs = 0L
            // A pending guard_armed=false from an earlier OFF is superseded by
            // this ON: the guard will push its own value when it auto-arms.
            prefs.pendingGuardArmed = null
            prefs.stateDirty = true
            ctx.tryStartTracking()
            app.appScope.launch { app.sync.flush() }
        } else {
            // Disarms the guard and clears the anchor.
            GuardControl.setArmed(ctx, false)
            app.prefs.trackingStartedAtMs = 0L // explicit stop: the timer ends here
            // Audit C1: the devices-row mirror flushed below never touches
            // device_settings.guard_armed, so a Remote settings edit afterwards
            // silently re-armed the tracker. Persist the disarm for the settings
            // row too and try the PATCH at once; while offline it stays pending
            // and syncSettings / the Watchdog retry it. The Remote phone owns no
            // guard_armed row for its own device id, so it records nothing.
            if (!prefs.isRemote) {
                prefs.pendingGuardArmed = false
                app.appScope.launch { GuardControl.flushPendingGuardArmed(app) }
            }
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
