package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBoot(context)
            Intent.ACTION_POWER_CONNECTED -> onPowerChanged(context)
            Intent.ACTION_POWER_DISCONNECTED -> onPowerChanged(context)
            ACTION_SCHEDULE_START -> onScheduleStart(context, intent)
            ACTION_SCHEDULE_END -> onScheduleEnd(context, intent)
            ACTION_BEDTIME_START -> onBedtimeBoundary(context)
            ACTION_BEDTIME_END -> onBedtimeBoundary(context)
            ACTION_SET_ENABLED -> onSetEnabled(context, intent)
            ACTION_TOGGLE_ENABLED -> onToggleEnabled(context)
            ACTION_PAUSE_GRAYSCALER -> onPauseGrayscaler(context)
            ACTION_APPLY_PAUSE -> onApplyPause(context, intent)
            ACTION_PAUSE_END -> onPauseEnd(context)
            ACTION_PAUSE_NOTIFY_COUNTDOWN -> onPauseNotifyCountdown(context, intent)
        }
    }

    private fun onBoot(context: Context) {
        val store = ScheduleStore(context)
        store.load()
        ScheduleManager(context).registerAll(store.schedules)
        store.syncRuntimeStateNow()
        BedtimeManager(context).apply {
            cancelAll()
            registerAll()
            resync()
        }
        GrayscaleStateManager.invalidate(context)
    }

    private fun onPowerChanged(context: Context) {
        BedtimeManager(context).resync()
    }

    private fun onScheduleStart(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val store = ScheduleStore(context)
        store.load()
        val schedule = store.schedules.find { it.id == scheduleId } ?: return
        if (!schedule.enabled) return

        store.syncRuntimeStateNow(scheduleId)
        BedtimeManager(context).resync()
        GrayscaleStateManager.invalidate(context)

        // Re-register next week's alarm for this day
        ScheduleManager(context).register(schedule)
    }

    private fun onScheduleEnd(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val store = ScheduleStore(context)
        store.load()
        val schedule = store.schedules.find { it.id == scheduleId } ?: return

        store.syncRuntimeStateNow()
        BedtimeManager(context).resync()
        GrayscaleStateManager.invalidate(context)

        ScheduleManager(context).register(schedule)
    }

    private fun onBedtimeBoundary(context: Context) {
        val bedtimeManager = BedtimeManager(context)
        bedtimeManager.cancelAll()
        bedtimeManager.registerAll()
        bedtimeManager.resync()
    }

    private fun onSetEnabled(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("grayscaler_enabled", enabled).commit()
        GrayscaleStateManager.invalidate(context)
        GrayscalerToggleCoordinator.requestTileSync(context)
        GrayscalerWidgetReceiver.triggerUpdate(context)
    }

    private fun onToggleEnabled(context: Context) {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val current = prefs.getBoolean("grayscaler_enabled", true)
        prefs.edit().putBoolean("grayscaler_enabled", !current).commit()
        GrayscaleStateManager.invalidate(context)
        GrayscalerToggleCoordinator.requestTileSync(context)
        GrayscalerWidgetReceiver.triggerUpdate(context)
    }

    private fun onPauseGrayscaler(context: Context) {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("persistent_overlay_mode", false)) {
            context.sendBroadcast(Intent(MainService.ACTION_SHOW_PAUSE_OVERLAY).apply {
                setPackage(context.packageName)
            })
        } else {
            context.startService(Intent(context, PauseOverlayService::class.java))
        }
    }

    private fun onApplyPause(context: Context, intent: Intent) {
        val seconds = when {
            intent.hasExtra(EXTRA_SECONDS) -> intent.getLongExtra(EXTRA_SECONDS, 0)
            else -> intent.getIntExtra(EXTRA_MINUTES, 0) * 60L
        }
        if (seconds <= 0) return
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pauseUntil = System.currentTimeMillis() + seconds * 1000L

        // Clean up any existing pause before overwriting
        cancelPauseEndAlarm(context)
        cancelPauseNotifyAlarm(context)
        cancelNotifications(context)

        prefs.edit().putLong("pause_until", pauseUntil).commit()
        GrayscaleStateManager.invalidate(context)

        // Schedule alarm to end the pause
        val endIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_PAUSE_END
        }
        val pi = PendingIntent.getBroadcast(
            context, PAUSE_END_REQUEST_CODE, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, pauseUntil, pi)

        postPauseNotification(context, seconds, pauseUntil, prefs)

        // Update widget and schedule tick if live countdown is enabled
        GrayscalerWidgetReceiver.triggerUpdate(context)
        if (WidgetSettingsStore.isLiveCountdown(context)) {
            GrayscalerWidgetReceiver.scheduleTickAlarm(context)
        }
    }

    private fun onPauseEnd(context: Context) {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("pause_until", 0L).commit()

        cancelPauseEndAlarm(context)
        cancelPauseNotifyAlarm(context)
        cancelNotifications(context)

        GrayscaleStateManager.invalidate(context)
        GrayscalerWidgetReceiver.cancelTickAlarm(context)
        GrayscalerWidgetReceiver.triggerUpdate(context)
    }

    private fun onPauseNotifyCountdown(context: Context, intent: Intent) {
        val pauseUntil = intent.getLongExtra(EXTRA_PAUSE_UNTIL, 0L)
        if (pauseUntil == 0L || pauseUntil <= System.currentTimeMillis()) return

        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        // Verify the stored pause_until still matches — guard against stale alarms after overwrite
        val storedUntil = prefs.getLong("pause_until", 0L)
        if (storedUntil != pauseUntil) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_STATIC)

        val cancelPi = buildCancelPausePendingIntent(context)
        postCountdownOrStatic(context, pauseUntil, nm, cancelPi, prefs)
    }

    private fun postPauseNotification(context: Context, seconds: Long, pauseUntil: Long, prefs: android.content.SharedPreferences) {
        if (!hasNotificationPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cancelPi = buildCancelPausePendingIntent(context)

        if (seconds > 300) {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val notif = NotificationCompat.Builder(context, CHANNEL_ID_STATIC)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Grayscaler+ Paused")
                .setContentText("Paused at ${fmt.format(Date())}. Will re-enable at ${fmt.format(Date(pauseUntil))}.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(pauseUntil - System.currentTimeMillis())
                .addAction(0, "Cancel Pause", cancelPi)
                .build()
            nm.notify(NOTIF_ID_STATIC, notif)

            // Schedule 5-min transition alarm
            val transitionTime = pauseUntil - 5 * 60 * 1000L
            val transitionIntent = Intent(context, ScheduleReceiver::class.java).apply {
                action = ACTION_PAUSE_NOTIFY_COUNTDOWN
                putExtra(EXTRA_PAUSE_UNTIL, pauseUntil)
            }
            val transitionPi = PendingIntent.getBroadcast(
                context, PAUSE_NOTIFY_TRANSITION_REQUEST_CODE, transitionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC_WAKEUP, transitionTime, transitionPi)
        } else {
            postCountdownOrStatic(context, pauseUntil, nm, cancelPi, prefs)
        }
    }

    private fun postCountdownOrStatic(
        context: Context,
        pauseUntil: Long,
        nm: NotificationManager,
        cancelPi: PendingIntent,
        prefs: android.content.SharedPreferences
    ) {
        val countdownEnabled = prefs.getBoolean("pause_countdown_notif_enabled", true)
        val remainingMs = pauseUntil - System.currentTimeMillis()
        if (countdownEnabled) {
            val notif = NotificationCompat.Builder(context, CHANNEL_ID_COUNTDOWN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Grayscaler+ re-enabling soon")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(pauseUntil)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(remainingMs)
                .addAction(0, "Cancel Pause", cancelPi)
                .build()
            nm.notify(NOTIF_ID_COUNTDOWN, notif)
        } else {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val notif = NotificationCompat.Builder(context, CHANNEL_ID_STATIC)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Grayscaler+ Paused")
                .setContentText("Will re-enable at ${fmt.format(Date(pauseUntil))}.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(remainingMs)
                .addAction(0, "Cancel Pause", cancelPi)
                .build()
            nm.notify(NOTIF_ID_STATIC, notif)
        }
    }

    private fun buildCancelPausePendingIntent(context: Context): PendingIntent {
        val cancelIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_PAUSE_END
        }
        return PendingIntent.getBroadcast(
            context, PAUSE_CANCEL_NOTIF_REQUEST_CODE, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPauseEndAlarm(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, PAUSE_END_REQUEST_CODE,
            Intent(context, ScheduleReceiver::class.java).apply { action = ACTION_PAUSE_END },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    private fun cancelPauseNotifyAlarm(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, PAUSE_NOTIFY_TRANSITION_REQUEST_CODE,
            Intent(context, ScheduleReceiver::class.java).apply { action = ACTION_PAUSE_NOTIFY_COUNTDOWN },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    private fun cancelNotifications(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_STATIC)
        nm.cancel(NOTIF_ID_COUNTDOWN)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    companion object {
        const val ACTION_SCHEDULE_START = "io.github.cloudburst.grayscaler.ACTION_SCHEDULE_START"
        const val ACTION_SCHEDULE_END = "io.github.cloudburst.grayscaler.ACTION_SCHEDULE_END"
        const val ACTION_BEDTIME_START = "io.github.cloudburst.grayscaler.ACTION_BEDTIME_START"
        const val ACTION_BEDTIME_END = "io.github.cloudburst.grayscaler.ACTION_BEDTIME_END"
        const val ACTION_SET_ENABLED = "io.github.cloudburst.grayscaler.ACTION_SET_ENABLED"
        const val ACTION_TOGGLE_ENABLED = "io.github.cloudburst.grayscaler.ACTION_TOGGLE_ENABLED"
        const val ACTION_PAUSE_GRAYSCALER = "io.github.cloudburst.grayscaler.ACTION_PAUSE_GRAYSCALER"
        const val ACTION_APPLY_PAUSE = "io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE"
        const val ACTION_PAUSE_END = "io.github.cloudburst.grayscaler.ACTION_PAUSE_END"
        const val ACTION_PAUSE_NOTIFY_COUNTDOWN = "io.github.cloudburst.grayscaler.ACTION_PAUSE_NOTIFY_COUNTDOWN"

        const val CHANNEL_ID_STATIC = "grayscaler_pause_static"
        const val CHANNEL_ID_COUNTDOWN = "grayscaler_pause_countdown"

        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_DAY = "day"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_PAUSE_UNTIL = "pause_until"

        private const val PAUSE_END_REQUEST_CODE = 9999
        private const val PAUSE_NOTIFY_TRANSITION_REQUEST_CODE = 9998
        private const val PAUSE_CANCEL_NOTIF_REQUEST_CODE = 9997

        const val NOTIF_ID_STATIC = 1001
        const val NOTIF_ID_COUNTDOWN = 1002
    }
}
