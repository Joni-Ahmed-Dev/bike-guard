package com.bikeguard.app.ui.viewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/** A MapView whose lifecycle follows the screen, forwarding each state exactly once. */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(context); MapView(context) }
    DisposableEffect(lifecycle, mapView) {
        var created = false; var started = false; var resumed = false
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE -> if (!created) { mapView.onCreate(null); created = true }
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }
    return mapView
}

/** True when this phone currently has a network with internet capability. */
fun isOnlineNow(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Connectivity state that updates live as networks come and go (issue #5): the Viewer shows
 * the offline-map UI only while this is false, and the map reloads live tiles on reconnect.
 * Snapshot-state writes from the network callback thread are safe; Compose reschedules.
 */
@Composable
fun rememberIsOnline(): Boolean {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(isOnlineNow(ctx)) }
    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = isOnlineNow(ctx) } // another network may still be up
            override fun onUnavailable() { online = false }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return online
}
