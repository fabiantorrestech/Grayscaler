package io.github.cloudburst.grayscaler

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

object GrayscalerToggleCoordinator {
    const val PREFS_NAME = "grayscaler_prefs"
    const val KEY_ENABLED = "grayscaler_enabled"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        GrayscaleStateManager.invalidate(context)
        requestTileSync(context)
    }

    fun toggle(context: Context): Boolean {
        val enabled = !isEnabled(context)
        setEnabled(context, enabled)
        return enabled
    }

    fun requestTileSync(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                context,
                ComponentName(context, GrayscalerTileService::class.java)
            )
        }
    }
}
