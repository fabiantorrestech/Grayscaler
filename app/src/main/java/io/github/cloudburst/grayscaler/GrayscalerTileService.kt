package io.github.cloudburst.grayscaler

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class GrayscalerTileService : TileService() {
    private val prefs: SharedPreferences by lazy {
        GrayscalerToggleCoordinator.prefs(this)
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GrayscalerToggleCoordinator.KEY_ENABLED) {
            syncTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        syncTile()
    }

    override fun onStopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onStopListening()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        GrayscalerToggleCoordinator.toggle(this)
        syncTile()
    }

    private fun syncTile() {
        val enabled = GrayscalerToggleCoordinator.isEnabled(this)
        val isDarkMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val iconRes = if (enabled == isDarkMode) {
            R.drawable.ic_qs_tile_black
        } else {
            R.drawable.ic_qs_tile_white
        }
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Grayscaler+"
            icon = Icon.createWithResource(this@GrayscalerTileService, iconRes)
            updateTile()
        }
    }
}
