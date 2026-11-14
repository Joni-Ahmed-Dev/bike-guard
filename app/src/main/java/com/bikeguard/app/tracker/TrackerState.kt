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
    /**
     * True only when a real fresh fix exists: a fix that arrived live from
     * the GPS and is at most ~30 s old. A shown last-known point does NOT
     * count, so the UI can never claim "Live" on a cached position.
     */
    val hasFix = MutableStateFlow(false)
    /**
     * Why the shown position is (or is not) a live fix. One of the
     * FIX_* constants below; the service keeps this honest as fixes
     * arrive, go stale, or the location provider is switched off.
     */
    val fixStatus = MutableStateFlow(FIX_NO_FIX)

    // Fix-status constants. These live directly on the object (a companion
    // object is not allowed inside a standalone object declaration).
    /** A fresh real fix is shown. */
    const val FIX_LIVE = "live"
    /** Only a stale / last-known fix is shown; it is not a live fix. */
    const val FIX_LAST_KNOWN = "last_known"
    /** No fix at all yet, and the provider is on. */
    const val FIX_NO_FIX = "no_fix"
    /** The location provider (GPS) is off, so no fix can arrive. */
    const val FIX_GPS_OFF = "gps_off"
}
