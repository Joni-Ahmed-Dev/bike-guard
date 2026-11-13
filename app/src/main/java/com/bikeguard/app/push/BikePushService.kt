package com.bikeguard.app.push

import com.bikeguard.app.bikeApp
import com.bikeguard.app.tracker.Notifier
import com.bikeguard.app.tracker.TrackingService
import com.bikeguard.app.tracker.tryStartTracking
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * Data-only FCM messages (the server sends no "notification" block) so we can localise the text ourselves.
 *  kind=event   -> show an alert on the Viewer phone
 *  kind=command -> wake the Tracker phone and run the command now (high-priority FCM may start the service)
 */
class BikePushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = bikeApp
        if (app.prefs.isLoggedIn) app.appScope.launch { app.api.registerPushToken(token, app.prefs.deviceId) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = bikeApp
        if (!app.prefs.isLoggedIn) return
        val d = message.data
        when (d["kind"]) {
            "event" -> Notifier.event(this, d)
            "command" -> {
                app.prefs.trackingActive = true
                if (tryStartTracking()) TrackingService.pollCommands(this)
            }
        }
    }
}
