package com.bikeguard.app.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_AUTO

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.bikeApp
        if (app.prefs.isLoggedIn && app.prefs.mode == MODE_AUTO) AutoMode.enable(context)
        if (app.prefs.isLoggedIn && app.prefs.shouldTrack) {
            GuardControl.raise(context, "device_rebooted", "warning")   // a thief power-cycling the phone is worth knowing
            context.tryStartTracking()
        }
    }
}
