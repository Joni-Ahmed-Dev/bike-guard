package com.bikeguard.app.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/** Motion-based Auto mode: Google's Activity Recognition tells us when the phone starts travelling in a vehicle. */
object AutoMode {
    fun hasPermission(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 4711, Intent(c, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Idempotent. Registrations are lost on reboot/update, so call it on app start and on boot. */
    @SuppressLint("MissingPermission")
    fun enable(c: Context) {
        if (!hasPermission(c)) return
        val transitions = listOf(
            ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT
        ).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        runCatching {
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(c))
        }
    }

    @SuppressLint("MissingPermission")
    fun disable(c: Context) {
        runCatching { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c)) }
    }
}
