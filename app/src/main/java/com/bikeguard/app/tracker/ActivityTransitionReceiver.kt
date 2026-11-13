package com.bikeguard.app.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.Perms
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Starts tracking when the bike starts moving. Android allows a foreground-service start from this broadcast. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.bikeApp
        if (app.prefs.mode != MODE_AUTO || !app.prefs.isLoggedIn || !Perms.hasFine(context)) return
        val entered = result.transitionEvents.any {
            it.activityType == DetectedActivity.IN_VEHICLE && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }
        if (entered) {
            app.prefs.trackingActive = true
            context.tryStartTracking()
        }
    }
}
