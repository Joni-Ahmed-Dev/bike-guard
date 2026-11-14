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
    private const val METADATA = "bikeguard-around"

    /** Downloads a roughly 20 km box around [lat]/[lng]. [onResult] gets a string resource id. */
    fun downloadAround(ctx: Context, lat: Double, lng: Double, onResult: (Int) -> Unit) {
        runCatching {
            val density = ctx.resources.displayMetrics.density
            val bounds = LatLngBounds.Builder()
                .include(LatLng(lat - 0.09, lng - 0.09))
                .include(LatLng(lat + 0.09, lng + 0.09))
                .build()
            val definition = OfflineTilePyramidRegionDefinition(MAP_STYLE, bounds, 10.0, 16.0, density)
            val mgr = OfflineManager.getInstance(ctx)
            // Reuse the existing region instead of creating a duplicate on every tap.
            mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    val existing = regions?.firstOrNull {
                        runCatching { String(it.metadata, Charsets.UTF_8) }.getOrNull() == METADATA
                    }
                    if (existing != null) {
                        existing.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        onResult(R.string.offline_map_started)
                    } else {
                        createRegion(mgr, definition, onResult)
                    }
                }

                override fun onError(error: String) {
                    createRegion(mgr, definition, onResult)
                }
            })
        }.onFailure { onResult(R.string.offline_map_failed) }
    }

    private fun createRegion(mgr: OfflineManager, definition: OfflineTilePyramidRegionDefinition, onResult: (Int) -> Unit) {
        mgr.createOfflineRegion(
            definition, METADATA.toByteArray(Charsets.UTF_8),
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
    }
}
