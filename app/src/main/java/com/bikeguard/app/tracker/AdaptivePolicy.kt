package com.bikeguard.app.tracker

/** Decides how often to ask for GPS. Pure function: easy to reason about and to unit-test. */
object AdaptivePolicy {
    const val MOVING_SPEED_MS = 1.5f                  // above this the bike counts as moving
    const val PARKED_AFTER_MS = 2 * 60_000L           // stationary this long => parked
    const val PARKED_INTERVAL_MS = 60_000L
    const val LOW_BATTERY_PCT = 15
    const val AUTO_STOP_AFTER_MS = 10 * 60_000L       // Auto mode stops tracking after this much parking

    fun intervalMs(baseSec: Int, stationaryForMs: Long, battery: Int?, charging: Boolean?): Long {
        val base = baseSec.coerceIn(3, 60) * 1000L
        val low = battery != null && battery <= LOW_BATTERY_PCT && charging != true
        val parked = stationaryForMs >= PARKED_AFTER_MS
        return when {
            parked && low -> 180_000L
            parked -> PARKED_INTERVAL_MS
            low -> base * 3
            else -> base
        }
    }
}
