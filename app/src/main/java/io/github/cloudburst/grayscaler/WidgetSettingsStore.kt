package io.github.cloudburst.grayscaler

import android.content.Context

object WidgetSettingsStore {
    private const val KEY_LIVE_COUNTDOWN = "widget_live_countdown"
    private const val KEY_STATUS_MONITORING = "widget_status_monitoring"

    fun isLiveCountdown(context: Context): Boolean =
        GrayscalerToggleCoordinator.prefs(context).getBoolean(KEY_LIVE_COUNTDOWN, true)

    fun setLiveCountdown(context: Context, enabled: Boolean) {
        GrayscalerToggleCoordinator.prefs(context)
            .edit().putBoolean(KEY_LIVE_COUNTDOWN, enabled).apply()
    }

    fun isStatusMonitoring(context: Context): Boolean =
        GrayscalerToggleCoordinator.prefs(context).getBoolean(KEY_STATUS_MONITORING, true)

    fun setStatusMonitoring(context: Context, enabled: Boolean) {
        GrayscalerToggleCoordinator.prefs(context)
            .edit().putBoolean(KEY_STATUS_MONITORING, enabled).apply()
    }
}
