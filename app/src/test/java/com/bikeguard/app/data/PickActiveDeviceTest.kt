package com.bikeguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the Remote's device picker (v1.2.21, review item #23).
 * Pure function — no Android framework needed, so these run on the JVM. */
class PickActiveDeviceTest {

    private fun device(id: String, lastSeen: String? = null) =
        DeviceDto(id = id, name = "Bike $id", lastSeen = lastSeen)

    @Test fun `empty list returns null`() {
        assertNull(pickActiveDevice(emptyList(), "own"))
    }

    @Test fun `own device is never picked when others exist`() {
        val own = device("own", lastSeen = "2026-11-15T07:00:00Z")
        val other = device("other", lastSeen = "2026-11-15T06:00:00Z")
        assertEquals("other", pickActiveDevice(listOf(own, other), "own")?.id)
    }

    @Test fun `newest last_seen wins among other devices`() {
        val a = device("a", lastSeen = "2026-11-15T06:00:00Z")
        val b = device("b", lastSeen = "2026-11-15T07:00:00Z")
        assertEquals("b", pickActiveDevice(listOf(a, b), "own")?.id)
        assertEquals("b", pickActiveDevice(listOf(b, a), "own")?.id)
    }

    @Test fun `rows with no last_seen are ignored when any row has been seen`() {
        val unseen = device("unseen")
        val seen = device("seen", lastSeen = "2026-11-15T06:00:00Z")
        assertEquals("seen", pickActiveDevice(listOf(unseen, seen), "own")?.id)
    }

    @Test fun `falls back to the first other row when nothing has ever been seen`() {
        val a = device("a")
        val b = device("b")
        assertEquals("a", pickActiveDevice(listOf(a, b), "own")?.id)
    }

    @Test fun `single device returns it even when it is the only row`() {
        val only = device("only", lastSeen = "2026-11-15T06:00:00Z")
        assertEquals("only", pickActiveDevice(listOf(only), "someone-else")?.id)
    }

    @Test fun `rows with a null id are ignored`() {
        val noId = DeviceDto(id = null, name = "No id", lastSeen = "2026-11-15T07:00:00Z")
        val real = device("real", lastSeen = "2026-11-15T06:00:00Z")
        assertEquals("real", pickActiveDevice(listOf(noId, real), "own")?.id)
    }
}
