package com.bikeguard.app.ui.trips

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bikeguard.app.data.PointDto
import com.bikeguard.app.ui.viewer.EMBER
import com.bikeguard.app.ui.viewer.MAP_STYLE
import com.bikeguard.app.ui.viewer.arrowBitmap
import com.bikeguard.app.ui.viewer.bikeFeature
import com.bikeguard.app.ui.viewer.rememberMapViewWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Route coloured by speed, start/end markers, and a cursor that the replay controls move. */
@Composable
fun ReplayMap(
    modifier: Modifier,
    points: List<PointDto>,
    sample: Sample?,
    follow: Boolean,
    onUserGesture: () -> Unit,
    showRouteLine: Boolean = true,
) {
    val density = LocalDensity.current
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val gestureNow by rememberUpdatedState(onUserGesture)
    val followNow by rememberUpdatedState(follow)

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.also { mv ->
                mv.getMapAsync { m ->
                    map = m
                    m.uiSettings.apply {
                        isLogoEnabled = false
                        isCompassEnabled = false
                        isTiltGesturesEnabled = false
                        setAttributionMargins(with(density) { 16.dp.roundToPx() }, 0, 0, with(density) { 232.dp.roundToPx() })
                    }
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(23.7, 90.4)).zoom(6.0).build()
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureNow()
                    }
                    m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { s -> addReplayLayers(s); style = s }
                }
            }
        },
    )

    // Draw the route once per trip and fit the camera to it.
    LaunchedEffect(points, style, map, showRouteLine) {
        val s = style; val m = map
        if (s == null || m == null || points.size < 2) return@LaunchedEffect
        if (showRouteLine) {
            val segments = ArrayList<Feature>(points.size)
            for (i in 0 until points.size - 1) {
                val a = points[i]; val b = points[i + 1]
                val kmh = ((a.speed ?: 0f) + (b.speed ?: 0f)) / 2f * 3.6f
                segments += Feature.fromGeometry(
                    LineString.fromLngLats(listOf(Point.fromLngLat(a.lng, a.lat), Point.fromLngLat(b.lng, b.lat)))
                ).also { it.addStringProperty("c", colorHex(speedColor(kmh))) }
            }
            s.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(FeatureCollection.fromFeatures(segments))
        } else {
            s.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
        s.getSourceAs<GeoJsonSource>("ends-src")?.setGeoJson(
            FeatureCollection.fromFeatures(listOf(endPoint(points.first(), "#EAF0F8"), endPoint(points.last(), EMBER)))
        )
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lng) }).build()
        with(density) {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48.dp.roundToPx(), 120.dp.roundToPx(), 48.dp.roundToPx(), 260.dp.roundToPx()))
        }
    }

    LaunchedEffect(sample, style) {
        val s = style ?: return@LaunchedEffect
        val c = sample ?: return@LaunchedEffect
        val p = LatLng(c.lat, c.lng)
        s.getSourceAs<GeoJsonSource>("cursor-src")?.setGeoJson(bikeFeature(p, c.bearing, true))
        if (followNow) map?.moveCamera(CameraUpdateFactory.newLatLng(p))
    }
}

private fun endPoint(p: PointDto, color: String): Feature =
    Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also { it.addStringProperty("c", color) }

private fun addReplayLayers(style: Style) {
    style.addImage("arrow", arrowBitmap())
    style.addSource(GeoJsonSource("route-src"))
    style.addSource(GeoJsonSource("ends-src"))
    style.addSource(GeoJsonSource("cursor-src"))
    style.addLayer(
        LineLayer("route-layer", "route-src").withProperties(
            lineColor(Expression.get("c")), lineWidth(5f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        CircleLayer("ends-layer", "ends-src").withProperties(
            circleColor(Expression.get("c")), circleRadius(7f), circleStrokeColor("#0B0F17"), circleStrokeWidth(2f),
        )
    )
    style.addLayer(
        CircleLayer("cursor-dot", "cursor-src").withProperties(
            circleColor("#FFFFFF"), circleRadius(8f), circleStrokeColor(EMBER), circleStrokeWidth(3f),
        )
    )
    style.addLayer(
        SymbolLayer("cursor-arrow", "cursor-src").withProperties(
            iconImage("arrow"), iconRotate(Expression.get("bearing")), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        )
    )
}
