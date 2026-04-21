package io.github.cloudburst.grayscaler

import android.content.Context

// TODO (future feature): Each Schedule should optionally carry its own whitelist of apps that
//  remain in color during that window, or start from a blank whitelist instead of inheriting the
//  global one. When implementing, add a `customWhitelist: Set<String>?` field to Schedule
//  (null = use global AppListStore whitelist). ScheduleStore serialization will need a new
//  section per schedule entry to persist this list.

class ScheduleStore(private val context: Context) {

    private val path = context.filesDir.resolve("schedules.txt")
    private val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

    var schedules: MutableList<Schedule> = mutableListOf()

    var scheduleOverrideActive: Boolean
        get() = prefs.getBoolean("schedule_override_active", false)
        set(value) = prefs.edit().putBoolean("schedule_override_active", value).apply()

    fun load() {
        schedules.clear()
        if (!path.exists()) return
        path.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                parseLine(line)?.let { schedules.add(it) }
            }
        }
    }

    fun save() {
        path.bufferedWriter().use { writer ->
            schedules.forEach { writer.write(serializeLine(it) + "\n") }
        }
    }

    fun add(schedule: Schedule) {
        schedules.add(schedule)
        save()
    }

    fun remove(id: String) {
        schedules.removeAll { it.id == id }
        save()
    }

    fun update(schedule: Schedule) {
        val idx = schedules.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) schedules[idx] = schedule
        save()
    }

    // Returns the first enabled existing schedule that overlaps with the candidate on any shared
    // day and time range. Does not flag conflicts with the candidate's own id (for edits).
    // TODO: Overnight schedules (endTime < startTime) are not currently supported.
    fun conflictsWith(candidate: Schedule): Schedule? {
        return schedules.firstOrNull { existing ->
            if (existing.id == candidate.id || !existing.enabled) return@firstOrNull false
            val sharedDays = existing.days.intersect(candidate.days)
            if (sharedDays.isEmpty()) return@firstOrNull false
            candidate.startMinutes() < existing.endMinutes() && existing.startMinutes() < candidate.endMinutes()
        }
    }

    fun isAnyScheduleActiveNow(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return schedules.any { schedule ->
            schedule.enabled &&
            schedule.days.contains(currentDay) &&
            currentMinutes >= schedule.startMinutes() &&
            currentMinutes < schedule.endMinutes()
        }
    }

    private fun serializeLine(s: Schedule): String {
        val days = s.days.joinToString(",")
        val enabled = if (s.enabled) "1" else "0"
        return "${s.id}|${s.label}|${days}|${s.startHour}|${s.startMinute}|${s.endHour}|${s.endMinute}|${enabled}"
    }

    private fun parseLine(line: String): Schedule? {
        return try {
            val p = line.split("|")
            Schedule(
                id = p[0],
                label = p[1],
                days = p[2].split(",").map { it.toInt() }.toSet(),
                startHour = p[3].toInt(),
                startMinute = p[4].toInt(),
                endHour = p[5].toInt(),
                endMinute = p[6].toInt(),
                enabled = p[7] == "1"
            )
        } catch (e: Exception) {
            null
        }
    }
}
