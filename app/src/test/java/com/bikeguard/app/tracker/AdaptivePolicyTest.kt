package com.bikeguard.app.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the GPS interval policy (v1.2.21, review item #23).
 * Pure function — no Android framework needed, so these run on the JVM. */
class AdaptivePolicyTest {

    @Test fun `moving with a healthy battery uses the base interval`() {
        assertEquals(5_000L, AdaptivePolicy.intervalMs(5, stationaryForMs = 0, battery = 80, charging = false))
        assertEquals(10_000L, AdaptivePolicy.intervalMs(10, stationaryForMs = 0, battery = null, charging = null))
    }

    @Test fun `base interval is clamped to 3 to 60 seconds`() {
        assertEquals(3_000L, AdaptivePolicy.intervalMs(1, stationaryForMs = 0, battery = 80, charging = false))
        assertEquals(60_000L, AdaptivePolicy.intervalMs(120, stationaryForMs = 0, battery = 80, charging = false))
    }

    @Test fun `parked for two minutes slows to one fix a minute`() {
        assertEquals(
            AdaptivePolicy.PARKED_INTERVAL_MS,
            AdaptivePolicy.intervalMs(5, stationaryForMs = AdaptivePolicy.PARKED_AFTER_MS, battery = 80, charging = false),
        )
    }

    @Test fun `just under the parked threshold is still the base interval`() {
        assertEquals(
            5_000L,
            AdaptivePolicy.intervalMs(5, stationaryForMs = AdaptivePolicy.PARKED_AFTER_MS - 1, battery = 80, charging = false),
        )
    }

    @Test fun `low battery while moving triples the interval`() {
        assertEquals(15_000L, AdaptivePolicy.intervalMs(5, stationaryForMs = 0, battery = 15, charging = false))
        assertEquals(15_000L, AdaptivePolicy.intervalMs(5, stationaryForMs = 0, battery = 5, charging = null))
    }

    @Test fun `low battery while charging does not slow anything down`() {
        assertEquals(5_000L, AdaptivePolicy.intervalMs(5, stationaryForMs = 0, battery = 10, charging = true))
    }

    @Test fun `parked and low battery is the slowest interval`() {
        assertEquals(
            180_000L,
            AdaptivePolicy.intervalMs(5, stationaryForMs = AdaptivePolicy.PARKED_AFTER_MS, battery = 10, charging = false),
        )
    }

    @Test fun `battery just above the low threshold is not low`() {
        assertEquals(5_000L, AdaptivePolicy.intervalMs(5, stationaryForMs = 0, battery = 16, charging = false))
    }
}
