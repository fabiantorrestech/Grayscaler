package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBoot(context)
            ACTION_SCHEDULE_START -> onScheduleStart(context, intent)
            ACTION_SCHEDULE_END -> onScheduleEnd(context, intent)
            ACTION_SET_ENABLED -> onSetEnabled(context, intent)
            ACTION_PAUSE_GRAYSCALER -> onPauseGrayscaler(context)
            ACTION_APPLY_PAUSE -> onApplyPause(context, intent)
            ACTION_PAUSE_END -> onPauseEnd(context)
        }
    }

    private fun onBoot(context: Context) {
        val store = ScheduleStore(context)
        store.load()
        val manager = ScheduleManager(context)
        manager.registerAll(store.schedules)
        // Re-evaluate current state on boot
        if (store.isAnyScheduleActiveNow()) {
            store.scheduleOverrideActive = true
            applyGrayscale(context, enable = true)
        }
    }

    private fun onScheduleStart(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val day = intent.getIntExtra(EXTRA_DAY, -1)
        val store = ScheduleStore(context)
        store.load()
        val schedule = store.schedules.find { it.id == scheduleId } ?: return
        if (!schedule.enabled) return

        store.scheduleOverrideActive = true
        applyGrayscale(context, enable = true)

        // Re-register next week's alarm for this day
        ScheduleManager(context).register(schedule)
    }

    private fun onScheduleEnd(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val day = intent.getIntExtra(EXTRA_DAY, -1)
        val store = ScheduleStore(context)
        store.load()
        val schedule = store.schedules.find { it.id == scheduleId } ?: return

        // Only deactivate override if no other schedule is currently active
        if (!store.isAnyScheduleActiveNow()) {
            store.scheduleOverrideActive = false
            applyGrayscale(context, enable = false)
        }

        ScheduleManager(context).register(schedule)
    }

    private fun onSetEnabled(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("grayscaler_enabled", enabled).apply()
        if (!enabled) applyGrayscale(context, enable = false)
    }

    private fun onPauseGrayscaler(context: Context) {
        val serviceIntent = Intent(context, PauseOverlayService::class.java)
        context.startService(serviceIntent)
    }

    private fun onApplyPause(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        if (minutes <= 0) return
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pauseUntil = System.currentTimeMillis() + minutes * 60 * 1000L
        prefs.edit().putLong("pause_until", pauseUntil).apply()
        applyGrayscale(context, enable = false)

        // Schedule alarm to end the pause
        val endIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_PAUSE_END
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, PAUSE_END_REQUEST_CODE, endIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, pauseUntil, pi)
    }

    private fun onPauseEnd(context: Context) {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("pause_until", 0L).apply()
        // Re-evaluate: if a schedule is active, re-enable grayscale
        val store = ScheduleStore(context)
        store.load()
        val globalEnabled = prefs.getBoolean("grayscaler_enabled", true)
        if (globalEnabled && store.isAnyScheduleActiveNow()) {
            applyGrayscale(context, enable = true)
        }
    }

    private fun applyGrayscale(context: Context, enable: Boolean) {
        if (enable) {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.ON)
        } else {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
        }
    }

    companion object {
        const val ACTION_SCHEDULE_START = "io.github.cloudburst.grayscaler.ACTION_SCHEDULE_START"
        const val ACTION_SCHEDULE_END = "io.github.cloudburst.grayscaler.ACTION_SCHEDULE_END"
        const val ACTION_SET_ENABLED = "io.github.cloudburst.grayscaler.ACTION_SET_ENABLED"
        const val ACTION_PAUSE_GRAYSCALER = "io.github.cloudburst.grayscaler.ACTION_PAUSE_GRAYSCALER"
        const val ACTION_APPLY_PAUSE = "io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE"
        const val ACTION_PAUSE_END = "io.github.cloudburst.grayscaler.ACTION_PAUSE_END"

        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_DAY = "day"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MINUTES = "minutes"

        private const val PAUSE_END_REQUEST_CODE = 9999
    }
}
