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
import com.bikeguard.app.data.SyncManager
import com.bikeguard.app.tracker.WatchdogWorker
import java.util.concurrent.TimeUnit

class BikeGuardApp : Application() {
    lateinit var prefs: Prefs
    lateinit var api: SupabaseApi
    lateinit var db: AppDatabase
    lateinit var sync: SyncManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = SupabaseApi(prefs)
        db = AppDatabase.get(this)
        sync = SyncManager(db.pending(), api, prefs)
        createChannels()
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
