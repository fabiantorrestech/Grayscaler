package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
            ACTION_TOGGLE_ENABLED -> onToggleEnabled(context, intent)
            ACTION_PAUSE_GRAYSCALER -> onPauseGrayscaler(context)
            ACTION_APPLY_PAUSE -> onApplyPause(context, intent)
            ACTION_APPLY_SESSION_PAUSE -> onApplySessionPause(context, intent)
            ACTION_PAUSE_END -> onPauseEnd(context, intent)
            ACTION_PAUSE_NOTIFY_COUNTDOWN -> onPauseNotifyCountdown(context, intent)
        }
    }

    private fun onBoot(context: Context) {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pauseType = prefs.getString(PREF_PAUSE_TYPE, "timed") ?: "timed"
        if (pauseType == "session" || pauseType == "pending_session") {
            prefs.edit()
                .putString(PREF_PAUSE_TYPE, "timed")
                .putString(PREF_SESSION_PAUSE_PKG, "")
                .putLong(PREF_SESSION_PAUSE_START_MS, 0L)
                .commit()
            StatusNotification.refresh(context)
        }
        val store = ScheduleStore(context)
        store.load()
        ScheduleManager(context).registerAll(store.schedules)
        store.syncRuntimeStateNow()
        BedtimeManager(context).apply {
            cancelAll()
            registerAll()
            resync()
        }
        GrayscaleStateManager.invalidate(context, "boot")
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
        GrayscaleStateManager.invalidate(context, "schedule_start")

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
        GrayscaleStateManager.invalidate(context, "schedule_end")

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
        GrayscalerToggleCoordinator.setEnabled(context, enabled, "broadcast_set_enabled")
    }

    private fun onToggleEnabled(context: Context, intent: Intent) {
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "widget_toggle"
        GrayscalerToggleCoordinator.toggle(context, reason)
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

        // A timed pause replaces any session pause. Leaving pause_type at "session" kept the
        // session rules (and its grace timer) alive underneath the new timed pause.
        prefs.edit()
            .putLong("pause_until", pauseUntil)
            .putString(PREF_PAUSE_TYPE, "timed")
            .putString(PREF_SESSION_PAUSE_PKG, "")
            .putLong(PREF_SESSION_PAUSE_START_MS, 0L)
            .commit()
        GrayscaleStateManager.invalidate(context, "apply_pause")

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

        // Long pauses show "Re-enables at …" first; this alarm switches the notification to a
        // live countdown for the last 5 minutes.
        if (seconds * 1000L > StatusNotification.COUNTDOWN_WINDOW_MS) {
            schedulePauseNotifyTransition(context, pauseUntil)
        }
        StatusNotification.refresh(context)

        // Update widget and schedule tick if live countdown is enabled
        GrayscalerWidgetReceiver.triggerUpdate(context)
        if (WidgetSettingsStore.isLiveCountdown(context)) {
            GrayscalerWidgetReceiver.scheduleTickAlarm(context)
        }
    }

    private fun onApplySessionPause(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_SESSION_PKG)
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        cancelPauseEndAlarm(context)
        cancelPauseNotifyAlarm(context)

        if (pkg != null) {
            val startMs = System.currentTimeMillis()
            prefs.edit()
                .putString(PREF_PAUSE_TYPE, "session")
                .putString(PREF_SESSION_PAUSE_PKG, pkg)
                .putLong(PREF_SESSION_PAUSE_START_MS, startMs)
                .commit()
            GrayscaleStateManager.invalidate(context, "session_pause")
        } else {
            prefs.edit()
                .putString(PREF_PAUSE_TYPE, "pending_session")
                .putString(PREF_SESSION_PAUSE_PKG, "")
                .putLong(PREF_SESSION_PAUSE_START_MS, 0L)
                .commit()
            GrayscaleStateManager.invalidate(context, "pending_session")
        }
        StatusNotification.refresh(context)

        GrayscalerWidgetReceiver.cancelTickAlarm(context)
        GrayscalerWidgetReceiver.triggerUpdate(context)
    }

    private fun onPauseEnd(context: Context, intent: Intent) {
        // The notification's "Cancel pause" button is always shown; with no pause active it must
        // not reset anything. Alarms and other callers send no extra and always clear.
        if (intent.getBooleanExtra(EXTRA_ONLY_IF_PAUSED, false) && !isPauseActive(context)) return
        clearAllPauseState(context)
        GrayscaleStateManager.invalidate(context, "pause_end")
        GrayscalerWidgetReceiver.triggerUpdate(context)
    }

    private fun onPauseNotifyCountdown(context: Context, intent: Intent) {
        val pauseUntil = intent.getLongExtra(EXTRA_PAUSE_UNTIL, 0L)
        if (pauseUntil == 0L || pauseUntil <= System.currentTimeMillis()) return

        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        // Verify the stored pause_until still matches — guard against stale alarms after overwrite
        val storedUntil = prefs.getLong("pause_until", 0L)
        if (storedUntil != pauseUntil) return

        // Now inside the last 5 minutes, so the notification switches to the live countdown.
        StatusNotification.refresh(context)
    }

    private fun schedulePauseNotifyTransition(context: Context, pauseUntil: Long) {
        val transitionTime = pauseUntil - StatusNotification.COUNTDOWN_WINDOW_MS
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
    }

    private fun isPauseActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pauseType = prefs.getString(PREF_PAUSE_TYPE, "timed") ?: "timed"
        return prefs.getLong("pause_until", 0L) > System.currentTimeMillis() ||
            pauseType == "session" || pauseType == "pending_session"
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
        const val ACTION_APPLY_SESSION_PAUSE = "io.github.cloudburst.grayscaler.ACTION_APPLY_SESSION_PAUSE"
        const val ACTION_PAUSE_END = "io.github.cloudburst.grayscaler.ACTION_PAUSE_END"
        const val ACTION_PAUSE_NOTIFY_COUNTDOWN = "io.github.cloudburst.grayscaler.ACTION_PAUSE_NOTIFY_COUNTDOWN"

        const val PREF_PAUSE_TYPE = "pause_type"
        const val PREF_SESSION_PAUSE_PKG = "session_pause_pkg"
        const val PREF_SESSION_PAUSE_START_MS = "session_pause_start_ms"
        const val PREF_SESSION_GRACE_PERIOD_MS = "session_grace_period_ms"
        const val SESSION_GRACE_PERIOD_DEFAULT_MS = 20_000L

        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_DAY = "day"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_SESSION_PKG = "session_pkg"
        const val EXTRA_REASON = "reason"
        const val EXTRA_ONLY_IF_PAUSED = "only_if_paused"
        private const val EXTRA_PAUSE_UNTIL = "pause_until"

        private const val PAUSE_END_REQUEST_CODE = 9999
        private const val PAUSE_NOTIFY_TRANSITION_REQUEST_CODE = 9998

        /**
         * Cancels every kind of pause (timed, session, pending session) and everything attached to
         * it: end/notify alarms, widget tick alarm, and the pause shown in the status notification.
         * Does NOT re-apply to the OS or
         * refresh widgets; callers decide how to follow up.
         */
        internal fun clearAllPauseState(context: Context) {
            context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("pause_until", 0L)
                .putString(PREF_PAUSE_TYPE, "timed")
                .putString(PREF_SESSION_PAUSE_PKG, "")
                .putLong(PREF_SESSION_PAUSE_START_MS, 0L)
                .commit()

            cancelPauseEndAlarm(context)
            cancelPauseNotifyAlarm(context)
            StatusNotification.refresh(context)
            GrayscalerWidgetReceiver.cancelTickAlarm(context)
        }

        internal fun cancelPauseEndAlarm(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, PAUSE_END_REQUEST_CODE,
                Intent(context, ScheduleReceiver::class.java).apply { action = ACTION_PAUSE_END },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            pi.cancel()
        }

        internal fun cancelPauseNotifyAlarm(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, PAUSE_NOTIFY_TRANSITION_REQUEST_CODE,
                Intent(context, ScheduleReceiver::class.java).apply { action = ACTION_PAUSE_NOTIFY_COUNTDOWN },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            pi.cancel()
        }
    }
}
