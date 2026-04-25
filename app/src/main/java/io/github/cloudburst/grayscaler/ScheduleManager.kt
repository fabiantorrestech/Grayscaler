package io.github.cloudburst.grayscaler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

class ScheduleManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun registerAll(schedules: List<Schedule>) {
        schedules.filter { it.enabled }.forEach { register(it) }
    }

    fun register(schedule: Schedule) {
        schedule.days.forEach { day ->
            setAlarm(schedule, day, isStart = true)
            setAlarm(schedule, day, isStart = false)
        }
    }

    fun cancel(scheduleId: String) {
        // Cancel for all possible day+start/end combinations
        for (day in 1..7) {
            cancelAlarm(scheduleId, day, isStart = true)
            cancelAlarm(scheduleId, day, isStart = false)
        }
    }

    fun cancelAll(schedules: List<Schedule>) {
        schedules.forEach { cancel(it.id) }
    }

    private fun setAlarm(schedule: Schedule, day: Int, isStart: Boolean) {
        val alarmDay = if (isStart || !schedule.isOvernight()) day else Schedule.nextDay(day)
        val triggerAt = nextOccurrence(
            alarmDay,
            if (isStart) schedule.startHour else schedule.endHour,
            if (isStart) schedule.startMinute else schedule.endMinute
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, buildPendingIntent(schedule.id, day, isStart))
    }

    private fun cancelAlarm(scheduleId: String, day: Int, isStart: Boolean) {
        alarmManager.cancel(buildPendingIntent(scheduleId, day, isStart))
    }

    private fun buildPendingIntent(scheduleId: String, day: Int, isStart: Boolean): PendingIntent {
        val action = if (isStart) ScheduleReceiver.ACTION_SCHEDULE_START else ScheduleReceiver.ACTION_SCHEDULE_END
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            this.action = action
            putExtra(ScheduleReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(ScheduleReceiver.EXTRA_DAY, day)
        }
        val requestCode = "${scheduleId}_${day}_${if (isStart) "s" else "e"}".hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
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
