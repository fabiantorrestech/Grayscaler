package io.github.cloudburst.grayscaler

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class GrayscalerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val newEnabled = !prefs.getBoolean("grayscaler_enabled", true)
        prefs.edit().putBoolean("grayscaler_enabled", newEnabled).apply()
        GrayscaleStateManager.invalidate(this)
        syncTile()
    }

    private fun syncTile() {
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("grayscaler_enabled", true)
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "GrayScaler+"
            updateTile()
        }
    }
}
