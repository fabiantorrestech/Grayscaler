package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GrayscalerWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = GrayscalerWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_STATE_CHANGED -> triggerUpdate(context)
            ACTION_WIDGET_TICK -> {
                triggerUpdate(context)
                // Reschedule tick only if pause is still active and live countdown is on
                val pauseUntil = GrayscalerToggleCoordinator.prefs(context).getLong("pause_until", 0L)
                if (pauseUntil > System.currentTimeMillis() && WidgetSettingsStore.isLiveCountdown(context)) {
                    scheduleTickAlarm(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_STATE_CHANGED = "io.github.cloudburst.grayscaler.ACTION_WIDGET_STATE_CHANGED"
        const val ACTION_WIDGET_TICK = "io.github.cloudburst.grayscaler.ACTION_WIDGET_TICK"
        private const val WIDGET_TICK_REQUEST_CODE = 9996

        fun triggerUpdate(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                GrayscalerWidget().updateAll(context)
            }
        }

        fun scheduleTickAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                buildTickPendingIntent(context)
            )
        }

        fun cancelTickAlarm(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context,
                WIDGET_TICK_REQUEST_CODE,
                Intent(context, GrayscalerWidgetReceiver::class.java).apply {
                    action = ACTION_WIDGET_TICK
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            pi.cancel()
        }

        private fun buildTickPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                WIDGET_TICK_REQUEST_CODE,
                Intent(context, GrayscalerWidgetReceiver::class.java).apply {
                    action = ACTION_WIDGET_TICK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
