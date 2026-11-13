package com.bikeguard.app.ui.trips

import androidx.core.graphics.ColorUtils
import com.bikeguard.app.data.PointDto
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Sample(val lat: Double, val lng: Double, val speedKmh: Float, val bearing: Float)

fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
}

/** Position at time [t], interpolated between the two surrounding GPS points. [times] mirrors [pts]. */
fun sampleAt(pts: List<PointDto>, times: LongArray, t: Long): Sample {
    var lo = 0; var hi = pts.size - 1
    while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (times[mid] <= t) lo = mid else hi = mid - 1 }
    val next = minOf(lo + 1, pts.size - 1)
    val a = pts[lo]; val b = pts[next]
    val span = (times[next] - times[lo]).coerceAtLeast(1)
    val f = if (next == lo) 0.0 else ((t - times[lo]).toDouble() / span).coerceIn(0.0, 1.0)
    return Sample(
        a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f,
        (a.speed ?: 0f) * 3.6f, bearingDeg(a.lat, a.lng, b.lat, b.lng),
    )
}

private val LOW = 0xFF3DDBD9.toInt()
private val MID = 0xFFFFC857.toInt()
private val HIGH = 0xFFFF5A5F.toInt()

/** 0 km/h teal -> 40 amber -> 80+ red. */
fun speedColor(kmh: Float): Int =
    if (kmh <= 40f) ColorUtils.blendARGB(LOW, MID, (kmh / 40f).coerceIn(0f, 1f))
    else ColorUtils.blendARGB(MID, HIGH, ((kmh - 40f) / 40f).coerceIn(0f, 1f))

fun colorHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)
