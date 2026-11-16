package com.bikeguard.app.ui

import android.app.Application
import android.content.Context
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
import com.bikeguard.app.data.UpdateCheckResult
import com.bikeguard.app.data.UpdateChecker
import com.bikeguard.app.data.UpdateInfo
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.data.pickActiveDevice
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.tracker.Notifier
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.tracker.TrackingControl
import com.bikeguard.app.tracker.TrackingService
import com.bikeguard.app.tracker.tryStartTracking
import com.bikeguard.app.update.ApkUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val PIN_MAX_TRIES = 5
private const val PIN_LOCK_MS = 5 * 60_000L

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp

    var loggedIn by mutableStateOf(app.prefs.isLoggedIn); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var info by mutableStateOf<String?>(null); private set
    var mode by mutableStateOf(app.prefs.mode); private set
    var intervalSec by mutableStateOf(app.prefs.intervalSec); private set
    var role by mutableStateOf(app.prefs.appRole); private set
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

    /** Newer GitHub Release, when the update check found one. */
    var updateAvailable by mutableStateOf<UpdateInfo?>(null); private set

    init {
        viewModelScope.launch {
            app.api.sessionExpired.collect { expired -> if (expired) endSession(flushFirst = false) }
        }
        // Update check once per app start (v1.2.21): Releases are the
        // distribution channel now; the check is a public API call, needs
        // no login, and fails silently offline.
        viewModelScope.launch {
            val result = UpdateChecker.checkLatest()
            if (result is UpdateCheckResult.Available) updateAvailable = result.info
        }
    }

    /** Manual re-check from Settings; the callback gets the full outcome. */
    fun checkForUpdate(onResult: (UpdateCheckResult) -> Unit) {
        viewModelScope.launch {
            val result = UpdateChecker.checkLatest()
            if (result is UpdateCheckResult.Available) updateAvailable = result.info
            onResult(result)
        }
    }

    fun dismissUpdate() { updateAvailable = null }

    /** Download the available update inside the app (v1.2.30). Falls back
     *  to the release page when the release has no APK to download. */
    fun startUpdateDownload(ctx: Context) {
        val info = updateAvailable ?: return
        if (!ApkUpdater.startDownload(ctx, info)) openWebPage(ctx, info.url)
        dismissUpdate()
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
        // The owner is now looking at the sign-in screen, so the session-expired warning
        // has done its job: clear it (a no-op after an explicit sign-out, when no warning
        // was ever posted). An explicit sign-out posts nothing — only a silent expiry does.
        Notifier.clearSessionExpired(app)
        app.prefs.sessionExpiredPending = false
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
        if (app.prefs.rolePin.isEmpty()) return false
        // A 4 digit PIN has only 10,000 values: after 5 wrong tries in a row the
        // check locks for 5 minutes, so it cannot be guessed by brute force.
        if (pinLockRemainingMin() > 0) return false
        if (pin != app.prefs.rolePin) {
            val fails = app.prefs.pinFailCount + 1
            if (fails >= PIN_MAX_TRIES) {
                app.prefs.pinFailCount = 0
                app.prefs.pinLockUntilMs = System.currentTimeMillis() + PIN_LOCK_MS
            } else app.prefs.pinFailCount = fails
            return false
        }
        app.prefs.pinFailCount = 0
        app.prefs.appRole = ""
        role = ""
        return true
    }

    /** Whole minutes left of a PIN lockout (rounded up), 0 when not locked. */
    fun pinLockRemainingMin(): Int {
        val left = app.prefs.pinLockUntilMs - System.currentTimeMillis()
        return if (left <= 0L) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    /**
     * PIN recovery (v1.2.20): the role PIN used to be recoverable only by clearing
     * app data, which also signed the owner out. Re-authenticating with the
     * account password clears the role and PIN, and the picker then asks for a
     * new role and PIN. The session is untouched: signIn refreshes the same
     * account's tokens. False when there is no stored email or the password is
     * wrong; a failed sign-in changes nothing.
     */
    fun resetRolePin(password: String, onResult: (Boolean) -> Unit) {
        val email = app.prefs.email.orEmpty()
        if (email.isBlank()) { onResult(false); return }
        viewModelScope.launch {
            val ok = app.api.signIn(email, password) is AuthResult.Success
            if (ok) {
                app.prefs.rolePin = ""
                app.prefs.appRole = ""
                role = ""
            }
            onResult(ok)
        }
    }

    // ---------- server-synced settings (Remote edits, tracker applies) ----------

    /** Sends a one-off command (e.g. mark_serviced) to the bike from the Remote phone. */
    fun sendRemoteCommand(type: String, arg: String? = null) {
        viewModelScope.launch {
            try { app.api.sendCommand(settingsTargetId, type, arg) } catch (e: Exception) { }
        }
    }

    /** Pushes exactly the fields the user just changed (v1.2.17). The old
     *  whole-row push rebuilt the server row from this phone's local Prefs,
     *  so a change tapped before loadRemoteSettings() finished overwrote
     *  values this phone had never seen with wiped defaults. Each call site
     *  passes only its own field; everything else on the server is untouched. */
    fun pushSettingsPatch(
        mode: String? = null,
        intervalSec: Int? = null,
        speedLimitKmh: Int? = null,
        autoArm: Boolean? = null,
        sirenOnAlert: Boolean? = null,
        sosSms: Boolean? = null,
        crashEnabled: Boolean? = null,
        sosNumber: String? = null,
        serviceKm: Int? = null,
        guardScheduleStart: Int? = null,
        guardScheduleEnd: Int? = null,
        clearScheduleStart: Boolean = false,
        clearScheduleEnd: Boolean = false,
    ) {
        if (app.prefs.appRole != ROLE_REMOTE) return
        viewModelScope.launch {
            settingsNote = null
            if (app.api.patchDeviceSettings(
                    settingsTargetId, mode, intervalSec, speedLimitKmh, autoArm,
                    sirenOnAlert, sosSms, crashEnabled, sosNumber, serviceKm,
                    guardScheduleStart, guardScheduleEnd, clearScheduleStart, clearScheduleEnd,
                )) {
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
            // Mirror the server row locally, so the screen shows the server's values.
            // Pushes are field-scoped now (pushSettingsPatch), so the mirror is only
            // for display — a later push cannot wipe a value this phone never saw.
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

    fun setInterval(sec: Int) {
        intervalSec = sec
        app.prefs.intervalSec = sec
        pushSettingsPatch(intervalSec = sec)
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
