package io.github.cloudburst.grayscaler

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Single entry point for the global on/off switch. Every UI (main screen, popup overlay,
 * quick-settings tile, widgets via ScheduleReceiver) must go through [setEnabled] or [toggle].
 *
 * Flipping the switch is a full state reset: all pauses are cancelled, cached stores are dropped,
 * schedule/bedtime runtime flags are re-derived from the clock, and the OS is re-applied from scratch.
 * A state snapshot is logged before and after so a stuck state can be diagnosed from logcat.
 */
object GrayscalerToggleCoordinator {
    const val PREFS_NAME = "grayscaler_prefs"
    const val KEY_ENABLED = "grayscaler_enabled"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean, reason: String = "unspecified") {
        GrayscaleStateManager.dumpState(context, "before_reset:$reason enabled=$enabled")

        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()

        // Cancel timed, session and pending-session pauses, their alarms, notifications and
        // widget ticks.
        ScheduleReceiver.clearAllPauseState(context)

        // Re-derive runtime flags from the clock. Config is untouched.
        ScheduleStore(context).apply { load() }.syncRuntimeStateNow()
        BedtimeStore(context).syncRuntimeState()

        GrayscaleStateManager.reset(context, reason)

        // In-memory tracking inside the accessibility service (grace timer, SystemUI suppression,
        // browser/WhatsApp tracking). Pref listeners only fire on a value change, so they cannot be
        // relied on to clear it; ask the service directly. Null when the service is not connected.
        MainService.instance?.resetTransientState()

        requestTileSync(context)
        GrayscalerWidgetReceiver.triggerUpdate(context)

        GrayscaleStateManager.dumpState(context, "after_reset:$reason")
    }

    @Synchronized
    fun toggle(context: Context, reason: String = "unspecified"): Boolean {
        val enabled = !isEnabled(context)
        setEnabled(context, enabled, reason)
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
