package com.bikeguard.app.tracker

import kotlinx.coroutines.flow.MutableStateFlow

data class FixInfo(val lat: Double, val lng: Double, val speedKmh: Float, val accuracy: Float, val timeMs: Long)

/** In-memory state shared between the service and the UI. */
object TrackerState {
    val running = MutableStateFlow(false)
    val online = MutableStateFlow(true)
    /** True while the bike has been stationary long enough that tracking runs in low-power mode. */
    val parked = MutableStateFlow(false)
    /** Parking mode (anti-theft) on or off. */
    val armed = MutableStateFlow(false)
    val lastFix = MutableStateFlow<FixInfo?>(null)
}
