package com.bikeguard.app.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bikeguard.app.bikeApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.bikeApp.prefs.shouldTrack) context.tryStartTracking()
    }
}
