package com.bikeguard.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.R
import com.bikeguard.app.data.AuthResult
import com.bikeguard.app.data.DeviceDto
import com.bikeguard.app.data.DeviceSettingsDto
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.ROLE_REMOTE
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.data.pickActiveDevice
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.tracker.TrackingControl
import com.bikeguard.app.tracker.TrackingService
import com.bikeguard.app.tracker.tryStartTracking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp

    var loggedIn by mutableStateOf(app.prefs.isLoggedIn); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<String?>(null); private set
    var mode by mutableStateOf(app.prefs.mode); private set
    var intervalSec by mutableStateOf(app.prefs.intervalSec); private set
    var role by mutableStateOf(app.prefs.appRole); private set
    var inviteCode by mutableStateOf(app.prefs.viewerInviteCode); private set
    /** Remote settings status: true = applied on the bike phone, false = waiting, null = unknown. */
    var settingsApplied by mutableStateOf<Boolean?>(null); private set
    var settingsNote by mutableStateOf<String?>(null); private set
    /** Last server settings row, so Settings shows the server's values, not this phone's stale defaults. */
    var remoteSettings by mutableStateOf<DeviceSettingsDto?>(null); private set
    /** The bike device the Remote is currently watching (freshest last_seen), for Settings. */
    var watchedDevice by mutableStateOf<DeviceDto?>(null); private set

    /** The device the Remote phone's settings target (the bike), or this phone when unset. */
    val settingsTargetId get() = app.prefs.remoteTargetDeviceId.ifBlank { app.prefs.deviceId }

    val configured get() = app.api.isConfigured
    val email get() = app.prefs.email.orEmpty()
    val deviceId get() = app.prefs.deviceId

    init {
        viewModelScope.launch {
            app.api.sessionExpired.collect { expired -> if (expired) endSession(flushFirst = false) }
        }
    }

    fun submit(email: String, password: String, signUp: Boolean) {
        if (busy) return
        if (!configured) { error = app.getString(R.string.config_missing); return }
        busy = true; error = null; info = null
        viewModelScope.launch {
            val e = email.trim()
            when (val r = if (signUp) app.api.signUp(e, password) else app.api.signIn(e, password)) {
                AuthResult.Success -> { loggedIn = true; ensureTracking() }
                AuthResult.NeedsConfirmation -> info = app.getString(R.string.confirm_email)
                is AuthResult.Error -> error = r.message
            }
            busy = false
        }
    }

    fun signOut() { viewModelScope.launch { endSession(flushFirst = true) } }

    private suspend fun endSession(flushFirst: Boolean) {
        // Best effort: don't lose queued points; the flush empties the queue when it works.
        // But a failed flush must NOT keep the queue: points queued under the old account
        // would upload under the next account and fail forever (audit #6), so always clear.
        if (flushFirst) withTimeoutOrNull(5_000) { app.sync.flush() }
        TrackingService.stop(app)
        AutoMode.disable(app)
        withContext(Dispatchers.IO) { app.db.pending().clear(); app.db.events().clear() }
        app.prefs.clearSession()
        TrackerState.lastFix.value = null
        TrackerState.armed.value = false
        loggedIn = false
    }

    fun setTracking(on: Boolean) = TrackingControl.set(app, on)

    // ---------- roles (v1.1.0) ----------

    /** First pick: the PIN must be exactly 4 digits. False when the PIN is bad. */
    fun pickRole(r: String, pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) return false
        app.prefs.appRole = r
        app.prefs.rolePin = pin
        role = r
        if (r == ROLE_REMOTE) {
            // The Remote phone never tracks; stop anything running here.
            TrackingService.stop(app)
            AutoMode.disable(app)
        }
        return true
    }

    /** Clears the role (so the picker shows again) only when the PIN matches. */
    fun clearRoleAfterPin(pin: String): Boolean {
        if (app.prefs.rolePin.isEmpty() || pin != app.prefs.rolePin) return false
        app.prefs.appRole = ""
        role = ""
        return true
    }

    fun updateInviteCode(code: String) {
        val c = code.trim()
        app.prefs.viewerInviteCode = c.ifBlank { null }
        inviteCode = app.prefs.viewerInviteCode
    }

    fun clearInviteCode() {
        app.prefs.viewerInviteCode = null
        inviteCode = null
    }

    /** Creates a family view-only invite code on the server. [onCode] gets it, or null on failure. */
    fun createFamilyInvite(onCode: (String?) -> Unit) {
        viewModelScope.launch {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = java.security.SecureRandom()
            val code = (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            val ok = try { app.api.createFamilyInvite(code) } catch (e: Exception) { false }
            onCode(if (ok) code else null)
        }
    }

    // ---------- server-synced settings (Remote edits, tracker applies) ----------

    private fun settingsFromPrefs() = DeviceSettingsDto(
        deviceId = settingsTargetId,
        mode = app.prefs.mode,
        intervalSec = app.prefs.intervalSec,
        speedLimitKmh = app.prefs.speedLimitKmh,
        guardArmed = app.prefs.parkingArmed,
        autoArm = app.prefs.autoArm,
        sirenOnAlert = app.prefs.sirenOnAlert,
        sosSms = app.prefs.sosSms,
        crashEnabled = app.prefs.crashDetection,
        sosNumber = app.prefs.emergencyNumber,
        serviceKm = app.prefs.serviceIntervalKm,
        guardScheduleStart = app.prefs.guardScheduleStart.takeIf { it in 0..23 },
        guardScheduleEnd = app.prefs.guardScheduleEnd.takeIf { it in 0..23 },
    )

    /** Sends a one-off command (e.g. mark_serviced) to the bike from the Remote phone. */
    fun sendRemoteCommand(type: String, arg: String? = null) {
        viewModelScope.launch {
            try { app.api.sendCommand(settingsTargetId, type, arg) } catch (e: Exception) { }
        }
    }

    /** Pushes this phone's settings to the server. Call after any Remote settings change. */
    fun pushSettings() {
        if (app.prefs.appRole != ROLE_REMOTE) return
        viewModelScope.launch {
            settingsNote = null
            if (app.api.saveDeviceSettings(settingsFromPrefs(), touchUpdated = true)) {
                settingsApplied = false // applied once the tracker polls and confirms
            } else {
                settingsNote = app.getString(R.string.cmd_failed)
            }
        }
    }

    /** Reads the server row so the Remote screen can show applied / waiting. */
    fun loadRemoteSettings() {
        viewModelScope.launch {
            // Resolve the watched device first: if the tracker re-registered,
            // the stored target may point at a dead row. This also heals
            // remote_target for the settings/commands path even when the
            // Dashboard was never opened in this session.
            try {
                app.api.listDevices()?.let { list ->
                    pickActiveDevice(list, app.prefs.deviceId)?.let { active ->
                        watchedDevice = active
                        active.id?.let { app.prefs.remoteTargetDeviceId = it }
                    }
                }
            } catch (e: Exception) { }
            val s = try { app.api.getDeviceSettings(settingsTargetId) } catch (e: Exception) { null }
            if (s == null) { settingsApplied = null; return@launch }
            remoteSettings = s
            // Mirror the server row locally: a later push must not wipe a value this phone never saw
            // (for example, clearing a guard schedule the Remote phone did not know about).
            mode = s.mode; app.prefs.mode = s.mode
            intervalSec = s.intervalSec; app.prefs.intervalSec = s.intervalSec
            app.prefs.speedLimitKmh = s.speedLimitKmh
            app.prefs.parkingArmed = s.guardArmed
            app.prefs.autoArm = s.autoArm
            app.prefs.sirenOnAlert = s.sirenOnAlert
            app.prefs.sosSms = s.sosSms
            app.prefs.crashDetection = s.crashEnabled
            app.prefs.emergencyNumber = s.sosNumber
            app.prefs.serviceIntervalKm = s.serviceKm
            app.prefs.guardScheduleStart = s.guardScheduleStart ?: -1
            app.prefs.guardScheduleEnd = s.guardScheduleEnd ?: -1
            val upd = isoToMs(s.updatedAt.orEmpty())
            val apl = isoToMs(s.appliedAt.orEmpty())
            settingsApplied = upd > 0 && apl >= upd
        }
    }

    fun updateMode(m: String) {
        mode = m
        app.prefs.mode = m
        pushSettings()
        if (app.prefs.isRemote) return // the Remote phone never tracks; the mode is for the bike
        // Leaving Always-on: keep the current run going, but it is user/auto-controlled from now on.
        if (m == MODE_ALWAYS) app.tryStartTracking() else app.prefs.trackingActive = TrackerState.running.value
        if (m == MODE_AUTO) AutoMode.enable(app) else AutoMode.disable(app)
    }

    fun setInterval(sec: Int) {
        intervalSec = sec
        app.prefs.intervalSec = sec
        pushSettings()
        if (app.prefs.isRemote) return
        if (TrackerState.running.value) app.tryStartTracking() // re-applies the new interval
    }

    /** Called when the UI opens: revive tracking if the mode or earlier state says it should be on. */
    fun ensureTracking() {
        if (app.prefs.isRemote) return
        if (app.prefs.mode == MODE_AUTO) AutoMode.enable(app)
        if (app.prefs.shouldTrack && !TrackerState.running.value) app.tryStartTracking()
    }
}
