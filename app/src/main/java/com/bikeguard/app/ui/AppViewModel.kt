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
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
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
        if (flushFirst) withTimeoutOrNull(5_000) { app.sync.flush() } // best effort: don't lose queued points
        TrackingService.stop(app)
        AutoMode.disable(app)
        withContext(Dispatchers.IO) { app.db.pending().clear() }
        app.prefs.clearSession()
        TrackerState.lastFix.value = null
        TrackerState.armed.value = false
        loggedIn = false
    }

    fun setTracking(on: Boolean) = TrackingControl.set(app, on)

    fun updateMode(m: String) {
        mode = m
        app.prefs.mode = m
        // Leaving Always-on: keep the current run going, but it is user/auto-controlled from now on.
        if (m == MODE_ALWAYS) app.tryStartTracking() else app.prefs.trackingActive = TrackerState.running.value
        if (m == MODE_AUTO) AutoMode.enable(app) else AutoMode.disable(app)
    }

    fun setInterval(sec: Int) {
        intervalSec = sec
        app.prefs.intervalSec = sec
        if (TrackerState.running.value) app.tryStartTracking() // re-applies the new interval
    }

    /** Called when the UI opens: revive tracking if the mode or earlier state says it should be on. */
    fun ensureTracking() {
        if (app.prefs.mode == MODE_AUTO) AutoMode.enable(app)
        if (app.prefs.shouldTrack && !TrackerState.running.value) app.tryStartTracking()
    }
}
