package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Calendar

data class BedtimeSettings(
    val enabled: Boolean = false,
    val days: Set<Int> = emptySet(),
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 6,
    val endMinute: Int = 0,
    val activateUponChargingOnly: Boolean = true,
    val stayActivatedAfterUnplugging: Boolean = true,
) {
    fun startMinutes(): Int = startHour * 60 + startMinute
    fun endMinutes(): Int = endHour * 60 + endMinute
    fun isOvernight(): Boolean = endMinutes() < startMinutes()

    fun activeAt(dayOfWeek: Int, minutesOfDay: Int): Boolean {
        return if (!isOvernight()) {
            days.contains(dayOfWeek) && minutesOfDay >= startMinutes() && minutesOfDay < endMinutes()
        } else {
            (days.contains(dayOfWeek) && minutesOfDay >= startMinutes()) ||
                (days.contains(Schedule.previousDay(dayOfWeek)) && minutesOfDay < endMinutes())
        }
    }
}

class BedtimeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var bedtimeOverrideActive: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_OVERRIDE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_OVERRIDE_ACTIVE, value).apply()

    var bedtimeChargeLatchActive: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_CHARGE_LATCH_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_CHARGE_LATCH_ACTIVE, value).apply()

    fun loadSettings(): BedtimeSettings {
        return BedtimeSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            days = prefs.getString(KEY_DAYS, "").orEmpty()
                .split(",")
                .mapNotNull { it.toIntOrNull() }
                .toSet(),
            startHour = prefs.getInt(KEY_START_HOUR, 22),
            startMinute = prefs.getInt(KEY_START_MINUTE, 0),
            endHour = prefs.getInt(KEY_END_HOUR, 6),
            endMinute = prefs.getInt(KEY_END_MINUTE, 0),
            activateUponChargingOnly = prefs.getBoolean(KEY_ACTIVATE_UPON_CHARGING_ONLY, true),
            stayActivatedAfterUnplugging = prefs.getBoolean(KEY_STAY_ACTIVE_AFTER_UNPLUGGING, true)
        )
    }

    fun saveSettings(settings: BedtimeSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_DAYS, settings.days.sorted().joinToString(","))
            .putInt(KEY_START_HOUR, settings.startHour)
            .putInt(KEY_START_MINUTE, settings.startMinute)
            .putInt(KEY_END_HOUR, settings.endHour)
            .putInt(KEY_END_MINUTE, settings.endMinute)
            .putBoolean(KEY_ACTIVATE_UPON_CHARGING_ONLY, settings.activateUponChargingOnly)
            .putBoolean(KEY_STAY_ACTIVE_AFTER_UNPLUGGING, settings.stayActivatedAfterUnplugging)
            .apply()
    }

    fun syncRuntimeState(): Boolean {
        val settings = loadSettings()
        val withinWindow = isWithinWindow(settings)
        val charging = isChargingNow()
        var nextLatch = bedtimeChargeLatchActive

        val active = when {
            !settings.enabled || settings.days.isEmpty() || !withinWindow -> {
                nextLatch = false
                false
            }
            settings.activateUponChargingOnly -> {
                if (charging) nextLatch = true
                val chargingRequirementMet = charging || (settings.stayActivatedAfterUnplugging && nextLatch)
                chargingRequirementMet && activeScheduleAllowsBedtimeOverride()
            }
            else -> {
                nextLatch = false
                activeScheduleAllowsBedtimeOverride()
            }
        }

        val changed = bedtimeOverrideActive != active || bedtimeChargeLatchActive != nextLatch
        bedtimeChargeLatchActive = nextLatch
        bedtimeOverrideActive = active
        return changed
    }

    fun clearRuntimeState() {
        bedtimeChargeLatchActive = false
        bedtimeOverrideActive = false
    }

    fun isWithinWindow(settings: BedtimeSettings = loadSettings()): Boolean {
        if (settings.days.isEmpty()) return false
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return settings.activeAt(day, minutes)
    }

    fun isChargingNow(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun activeScheduleAllowsBedtimeOverride(): Boolean {
        val scheduleStore = ScheduleStore(context)
        scheduleStore.load()
        val activeSchedule = scheduleStore.findActiveScheduleNow() ?: return true
        return activeSchedule.allowBedtimeOverride
    }

    companion object {
        const val PREFS_NAME = "grayscaler_prefs"
        const val KEY_ENABLED = "bedtime_enabled"
        const val KEY_DAYS = "bedtime_days"
        const val KEY_START_HOUR = "bedtime_start_hour"
        const val KEY_START_MINUTE = "bedtime_start_minute"
        const val KEY_END_HOUR = "bedtime_end_hour"
        const val KEY_END_MINUTE = "bedtime_end_minute"
        const val KEY_ACTIVATE_UPON_CHARGING_ONLY = "bedtime_activate_upon_charging_only"
        const val KEY_STAY_ACTIVE_AFTER_UNPLUGGING = "bedtime_stay_activated_after_unplugging"
        const val KEY_BEDTIME_OVERRIDE_ACTIVE = "bedtime_override_active"
        const val KEY_BEDTIME_CHARGE_LATCH_ACTIVE = "bedtime_charge_latch_active"
    }
}

class BedtimeManager(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = BedtimeStore(context)

    fun updateSettings(settings: BedtimeSettings) {
        store.saveSettings(settings)
        cancelAll()
        registerAll(settings)
        store.syncRuntimeState()
        GrayscaleStateManager.invalidate(context)
    }

    fun registerAll(settings: BedtimeSettings = store.loadSettings()) {
        if (!settings.enabled || settings.days.isEmpty()) return
        settings.days.forEach { day ->
            setAlarm(settings, day, isStart = true)
            setAlarm(settings, day, isStart = false)
        }
    }

    fun cancelAll() {
        for (day in 1..7) {
            alarmManager.cancel(buildPendingIntent(day, isStart = true))
            alarmManager.cancel(buildPendingIntent(day, isStart = false))
        }
    }

    fun resync() {
        store.syncRuntimeState()
        GrayscaleStateManager.invalidate(context)
    }

    private fun setAlarm(settings: BedtimeSettings, day: Int, isStart: Boolean) {
        val alarmDay = if (isStart || !settings.isOvernight()) day else Schedule.nextDay(day)
        val triggerAt = nextOccurrence(
            alarmDay,
            if (isStart) settings.startHour else settings.endHour,
            if (isStart) settings.startMinute else settings.endMinute
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, buildPendingIntent(day, isStart))
    }

    private fun buildPendingIntent(day: Int, isStart: Boolean): PendingIntent {
        val action = if (isStart) ScheduleReceiver.ACTION_BEDTIME_START else ScheduleReceiver.ACTION_BEDTIME_END
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            this.action = action
            putExtra(ScheduleReceiver.EXTRA_DAY, day)
        }
        val requestCode = "bedtime_${day}_${if (isStart) "s" else "e"}".hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextOccurrence(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.WEEK_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
