package com.bikeguard.app.tracker

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp

/** Quick Settings tile: start/stop tracking from the notification shade. */
class TrackingTileService : TileService() {
    override fun onStartListening() {
        // The Remote phone never tracks: the tile shows as unavailable there.
        if (bikeApp.prefs.isRemote) {
            val t = qsTile ?: return
            t.state = Tile.STATE_UNAVAILABLE
            t.label = getString(R.string.app_name)
            t.updateTile()
            return
        }
        render(TrackerState.running.value)
    }

    override fun onClick() {
        if (!bikeApp.prefs.isLoggedIn || bikeApp.prefs.isRemote) return
        val target = !TrackerState.running.value
        TrackingControl.set(this, target)
        render(target)
    }

    private fun render(active: Boolean) {
        val t = qsTile ?: return
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.app_name)
        t.updateTile()
    }
}
