package com.bikeguard.app.push

import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.AppLog
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
        AppLog.i("push", "new token")
        if (app.prefs.isLoggedIn) app.appScope.launch {
            app.api.registerPushToken(token, app.prefs.deviceId)
            PushRegistrar.registerWithPushWorker(token, app.prefs.deviceId, app.prefs.userId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = bikeApp
        if (!app.prefs.isLoggedIn) return
        val d = message.data
        AppLog.i("push", "msg kind=" + (d["kind"] ?: "?"))
        when (d["kind"]) {
            "event" -> Notifier.event(this, d)
            "command" -> {
                app.prefs.trackingActive = true
                if (tryStartTracking()) TrackingService.pollCommands(this)
            }
        }
    }
}
