package com.bikeguard.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bikeguard.app.data.AppDatabase
import com.bikeguard.app.data.Prefs
import com.bikeguard.app.data.SupabaseApi
import com.bikeguard.app.data.RealtimeClient
import com.bikeguard.app.data.SyncManager
import com.bikeguard.app.tracker.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Outlives screens and the tracking service: used for uploads, alerts and the SOS countdown. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = SupabaseApi(prefs)
        db = AppDatabase.get(this)
        sync = SyncManager(db.pending(), db.events(), api, prefs)
        realtime = RealtimeClient(api)
        TrackerState.armed.value = prefs.parkingArmed
        createChannels()
        if (prefs.isLoggedIn && prefs.mode == MODE_AUTO && !prefs.isRemote) AutoMode.enable(this)
        // Watchdog: every ~15 min flush the queue and make sure tracking is still alive.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val android.content.Context.bikeApp: BikeGuardApp get() = applicationContext as BikeGuardApp
