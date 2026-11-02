package io.github.cloudburst.grayscaler

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that opens the pause popup (the same one as the "Pause Popup" launcher
 * shortcut). It is an action tile, not a toggle, so it always shows as inactive.
 *
 * It goes through [PauseOverlayShortcutActivity] instead of starting [PauseOverlayService]
 * directly: launching an activity collapses the shade, so the popup is visible right away, and
 * an activity start from a tile click is always allowed, while a background service start is not.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_pause_label)
            icon = Icon.createWithResource(this@PauseTileService, R.drawable.ic_qs_tile_white)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(R.string.tile_pause_subtitle)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // On the lock screen, unlock first so the popup is usable.
        if (isLocked) unlockAndRun { openPausePopup() } else openPausePopup()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPausePopup() {
        val intent = Intent(this, PauseOverlayShortcutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ only accepts a PendingIntent here (the Intent overload throws).
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
