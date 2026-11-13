package com.bikeguard.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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

/** OpenStreetMap data via OpenFreeMap (free, no API key). Other styles: liberty, dark, positron, fiord. */
internal const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"
internal const val SIGNAL = "#3DDBD9"
internal const val EMBER = "#FF9F1C"

@Composable
fun BikeMap(
    modifier: Modifier,
    position: LatLng?,
    bearing: Float,
    moving: Boolean,
    trail: List<LatLng>,
    follow: Boolean,
    attributionBottomDp: Int,
    onUserGesture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val mapView = remember { MapLibre.getInstance(context); MapView(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var shown by remember { mutableStateOf<LatLng?>(null) }
    val followNow by rememberUpdatedState(follow)
    val gestureNow by rememberUpdatedState(onUserGesture)

    // Forward lifecycle to the MapView exactly once per state.
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

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.also { mv ->
                mv.getMapAsync { m ->
                    map = m
                    m.uiSettings.apply {
                        isLogoEnabled = false           // OSM attribution stays visible, as the licence requires
                        isCompassEnabled = false
                        isTiltGesturesEnabled = false
                        setAttributionMargins(with(density) { 16.dp.roundToPx() }, 0, 0, with(density) { attributionBottomDp.dp.roundToPx() })
                    }
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(23.7, 90.4)).zoom(6.0).build()
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureNow()
                    }
                    m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { s -> addLayers(s); style = s }
                }
            }
        },
    )

    // Smoothly glide the marker to each new fix instead of teleporting it.
    val glide = remember { Animatable(0f) }
    LaunchedEffect(position, style, map) {
        val s = style; val m = map; val to = position
        if (s == null || m == null || to == null) return@LaunchedEffect
        val first = shown == null
        val from = shown ?: to
        if (first) m.moveCamera(CameraUpdateFactory.newLatLngZoom(to, 16.0))
        glide.snapTo(0f)
        glide.animateTo(1f, tween(if (first) 1 else 900, easing = LinearEasing)) {
            val p = LatLng(
                from.latitude + (to.latitude - from.latitude) * value,
                from.longitude + (to.longitude - from.longitude) * value,
            )
            shown = p
            s.getSourceAs<GeoJsonSource>("bike-src")?.setGeoJson(bikeFeature(p, bearing, moving))
            if (followNow) m.moveCamera(CameraUpdateFactory.newLatLng(p))
        }
    }

    LaunchedEffect(follow) {
        if (follow) shown?.let { map?.animateCamera(CameraUpdateFactory.newLatLng(it), 600) }
    }

    LaunchedEffect(trail, style) {
        val src = style?.getSourceAs<GeoJsonSource>("trail-src") ?: return@LaunchedEffect
        if (trail.size >= 2) src.setGeoJson(LineString.fromLngLats(trail.map { Point.fromLngLat(it.longitude, it.latitude) }))
        else src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    // Soft radar pulse around the bike. Read inside a snapshotFlow so the map does not recompose every frame.
    val pulse = rememberInfiniteTransition(label = "pulse")
        .animateFloat(14f, 32f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "r")
    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        snapshotFlow { pulse.value }.collect { r ->
            s.getLayerAs<CircleLayer>("halo")?.setProperties(
                circleRadius(r), circleOpacity(0.35f * (1f - (r - 14f) / 18f))
            )
        }
    }
}

internal fun bikeFeature(p: LatLng, bearing: Float, moving: Boolean): Feature =
    Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
        addNumberProperty("bearing", bearing)
        addNumberProperty("moving", if (moving) 1 else 0)
    }

private fun addLayers(style: Style) {
    style.addImage("arrow", arrowBitmap())
    style.addSource(GeoJsonSource("trail-src"))
    style.addSource(GeoJsonSource("bike-src"))
    style.addLayer(
        LineLayer("trail-layer", "trail-src").withProperties(
            lineColor(EMBER), lineWidth(4f), lineOpacity(0.85f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(CircleLayer("halo", "bike-src").withProperties(circleColor(SIGNAL), circleRadius(14f), circleOpacity(0.3f)))
    style.addLayer(
        CircleLayer("dot", "bike-src").withProperties(
            circleColor(SIGNAL), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
        )
    )
    style.addLayer(
        SymbolLayer("arrow-layer", "bike-src").withProperties(
            iconImage("arrow"), iconRotate(Expression.get("bearing")), iconOpacity(Expression.get("moving")),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        )
    )
}

/** Heading marker: a small triangle at the top of a transparent 64px square, so it orbits the dot as it rotates. */
internal fun arrowBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val path = Path().apply { moveTo(32f, 2f); lineTo(43f, 21f); lineTo(21f, 21f); close() }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(SIGNAL) })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = android.graphics.Color.WHITE
    })
    return bmp
}
