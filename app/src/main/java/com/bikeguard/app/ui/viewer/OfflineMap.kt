package com.bikeguard.app.ui.viewer

import android.content.Context
import com.bikeguard.app.R
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloads the map tiles around a point (zoom 10-16) so the map still shows without internet.
 * Uses the documented MapLibre OfflineManager API: getInstance, createOfflineRegion with an
 * OfflineTilePyramidRegionDefinition, then setDownloadState(STATE_ACTIVE) on the created region.
 */
object OfflineMap {
    /** Downloads a roughly 20 km box around [lat]/[lng]. [onResult] gets a string resource id. */
    fun downloadAround(ctx: Context, lat: Double, lng: Double, onResult: (Int) -> Unit) {
        runCatching {
            val density = ctx.resources.displayMetrics.density
            val bounds = LatLngBounds.Builder()
                .include(LatLng(lat - 0.09, lng - 0.09))
                .include(LatLng(lat + 0.09, lng + 0.09))
                .build()
            val definition = OfflineTilePyramidRegionDefinition(MAP_STYLE, bounds, 10.0, 16.0, density)
            val metadata = "bikeguard-around".toByteArray(Charsets.UTF_8)
            OfflineManager.getInstance(ctx).createOfflineRegion(
                definition, metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(region: OfflineRegion) {
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        onResult(R.string.offline_map_started)
                    }

                    override fun onError(error: String) {
                        onResult(R.string.offline_map_failed)
                    }
                },
            )
        }.onFailure { onResult(R.string.offline_map_failed) }
    }
}
