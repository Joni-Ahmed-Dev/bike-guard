package com.bikeguard.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bikeguard.app.data.AppDatabase
import com.bikeguard.app.data.Prefs
import com.bikeguard.app.data.RtEvent
import com.bikeguard.app.data.SupabaseApi
import com.bikeguard.app.data.RealtimeClient
import com.bikeguard.app.data.DiagReporter
import com.bikeguard.app.data.SyncManager
import com.bikeguard.app.tracker.Notifier
import com.bikeguard.app.tracker.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.tracker.WatchdogWorker
import java.util.concurrent.TimeUnit

class BikeGuardApp : Application() {
    lateinit var prefs: Prefs
    lateinit var api: SupabaseApi
    lateinit var db: AppDatabase
    lateinit var sync: SyncManager
    lateinit var realtime: RealtimeClient
    lateinit var diag: DiagReporter

    /** Outlives screens and the tracking service: used for uploads, alerts and the SOS countdown. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = SupabaseApi(prefs)
        db = AppDatabase.get(this)
        diag = DiagReporter(this, prefs, db.pending())
        sync = SyncManager(db.pending(), db.events(), api, prefs, diag, appScope)
        realtime = RealtimeClient(api)
        // Report the app's state to the diagnostics Worker on every start,
        // so Muse can see the tracker even when Supabase sync is failing.
        appScope.launch { diag.report("app start") }
        TrackerState.armed.value = prefs.parkingArmed
        createChannels()
        if (prefs.isLoggedIn) startAlertListener()
        if (prefs.isLoggedIn && prefs.mode == MODE_AUTO && !prefs.isRemote) AutoMode.enable(this)
        // Watchdog: every ~15 min flush the queue, make sure tracking is still alive,
        // and poll the events table so the Remote phone gets alerts without FCM.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    /** Channels live in Notifier so the ringtone choices in Prefs are applied in one place. */
    private fun createChannels() = Notifier.createChannels(this)

    /**
     * App-level Realtime listener for Remote alerts (issue #6). The Viewer and Alerts
     * screens each acquire Realtime only while visible, so without this nothing would
     * notify while the app sits in the background with its process alive. Ref-counted:
     * screens acquiring as well just share the one socket. The tracker phone never
     * starts it (its own events are not news for the person holding it). Idempotent,
     * so the Watchdog can (re)start it after a later sign-in or role pick.
     */
    private var alertListenerJob: Job? = null

    fun startAlertListener() {
        if (!prefs.isLoggedIn || prefs.isTracker) return
        if (alertListenerJob?.isActive == true) return
        alertListenerJob = appScope.launch {
            realtime.acquire()
            try {
                realtime.events.collect { e ->
                    if (e is RtEvent.Ev) Notifier.notifyEvent(this@BikeGuardApp, e.ev)
                }
            } finally {
                realtime.release()
            }
        }
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_GENERAL = "general"
    }
}

val android.content.Context.bikeApp: BikeGuardApp get() = applicationContext as BikeGuardApp
