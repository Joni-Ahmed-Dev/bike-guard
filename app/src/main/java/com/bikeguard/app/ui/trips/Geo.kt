package com.bikeguard.app.ui.trips

import androidx.core.graphics.ColorUtils
import com.bikeguard.app.data.PointDto
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

/** Great-circle distance in metres between two points. */
fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthR = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earthR * asin(minOf(1.0, sqrt(a)))
}

/**
 * A trip's distance with GPS jitter removed: a segment only counts when it is a real move —
 * either end reports at least 1 m/s (the server's own "moving" line) with a step of at
 * least 5 m, or the step is at least 50 m, clearly beyond any fix accuracy. Segments
 * between two stationary / jitter points contribute 0. This is the second layer: the
 * tracker already drops jitter fixes before upload, so new trips should not need it,
 * but already stored points still carry the jitter.
 */
fun filteredDistanceM(points: List<PointDto>): Double {
    var total = 0.0
    for (i in 1 until points.size) {
        val a = points[i - 1]; val b = points[i]
        val d = haversineM(a.lat, a.lng, b.lat, b.lng)
        val fastest = maxOf(a.speed ?: 0f, b.speed ?: 0f)
        if ((fastest >= 1.0f && d >= 5.0) || d >= 50.0) total += d
    }
    return total
}

private val LOW = 0xFF3DDBD9.toInt()
private val MID = 0xFFFFC857.toInt()
private val HIGH = 0xFFFF5A5F.toInt()

/** 0 km/h teal -> 40 amber -> 80+ red. */
fun speedColor(kmh: Float): Int =
    if (kmh <= 40f) ColorUtils.blendARGB(LOW, MID, (kmh / 40f).coerceIn(0f, 1f))
    else ColorUtils.blendARGB(MID, HIGH, ((kmh - 40f) / 40f).coerceIn(0f, 1f))

fun colorHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

/** Points around a circle (closed ring, first point repeated at the end) for a geofence on the map. */
fun circlePoints(lat: Double, lng: Double, radiusM: Int, steps: Int = 48): List<org.maplibre.android.geometry.LatLng> {
    val earthR = 6_371_000.0
    val latRad = Math.toRadians(lat)
    val out = ArrayList<org.maplibre.android.geometry.LatLng>(steps + 1)
    for (i in 0..steps) {
        val brg = Math.toRadians(i * 360.0 / steps)
        val pLat = Math.asin(
            sin(latRad) * cos(radiusM / earthR) +
                cos(latRad) * sin(radiusM / earthR) * cos(brg)
        )
        val pLng = Math.toRadians(lng) + atan2(
            sin(brg) * sin(radiusM / earthR) * cos(latRad),
            cos(radiusM / earthR) - sin(latRad) * sin(pLat),
        )
        out += org.maplibre.android.geometry.LatLng(Math.toDegrees(pLat), Math.toDegrees(pLng))
    }
    return out
}

data class RideScore(val score: Int, val harshBrakes: Int, val overSpeedPct: Int)

/**
 * 0-100 ride score from the trip's points: -15 per harsh brake (a drop of 20 km/h or more between
 * fixes) and up to -30 for the share of the ride spent over 80 km/h. A short or gentle ride scores 100.
 */
fun rideScore(points: List<PointDto>): RideScore {
    if (points.size < 5) return RideScore(100, 0, 0)
    var harsh = 0
    var over = 0
    var prev = (points.first().speed ?: 0f) * 3.6f
    for (p in points.drop(1)) {
        val kmh = (p.speed ?: 0f) * 3.6f
        if (prev - kmh >= 20f) harsh++
        if (kmh > 80f) over++
        prev = kmh
    }
    val overPct = (over * 100 / points.size).coerceIn(0, 100)
    val score = (100 - harsh * 15 - overPct * 30 / 100).coerceIn(0, 100)
    return RideScore(score, harsh, overPct)
}
