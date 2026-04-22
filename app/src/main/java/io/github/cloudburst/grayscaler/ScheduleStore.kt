package io.github.cloudburst.grayscaler

import android.content.Context

class ScheduleStore(private val context: Context) {

    private val path = context.filesDir.resolve("schedules.txt")
    private val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

    var schedules: MutableList<Schedule> = mutableListOf()

    var scheduleOverrideActive: Boolean
        get() = prefs.getBoolean("schedule_override_active", false)
        set(value) = prefs.edit().putBoolean("schedule_override_active", value).apply()

    var activeScheduleId: String?
        get() = prefs.getString("active_schedule_id", null)?.takeIf { it.isNotEmpty() }
        set(value) = if (value != null)
            prefs.edit().putString("active_schedule_id", value).apply()
        else
            prefs.edit().remove("active_schedule_id").apply()

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

    fun isAnyScheduleActiveNow(): Boolean = findActiveScheduleNow() != null

    fun findActiveScheduleNow(): Schedule? {
        val cal = java.util.Calendar.getInstance()
        val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return schedules.firstOrNull { schedule ->
            schedule.enabled &&
            schedule.days.contains(currentDay) &&
            currentMinutes >= schedule.startMinutes() &&
            currentMinutes < schedule.endMinutes()
        }
    }

    private fun serializeLine(s: Schedule): String {
        val days = s.days.joinToString(",")
        val enabled = if (s.enabled) "1" else "0"
        val whitelist = s.profileWhitelist.joinToString(",")
        val blacklist = s.profileBlacklist.joinToString(",")
        return "${s.id}|${s.label}|${days}|${s.startHour}|${s.startMinute}|${s.endHour}|${s.endMinute}|${enabled}|${s.profileMode}|${whitelist}|${blacklist}"
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
                enabled = p[7] == "1",
                // fields 8-10 are absent in old format — migrate to global defaults
                profileMode = if (p.size > 8) p[8] else "global",
                profileWhitelist = if (p.size > 9 && p[9].isNotBlank()) p[9].split(",").toSet() else emptySet(),
                profileBlacklist = if (p.size > 10 && p[10].isNotBlank()) p[10].split(",").toSet() else emptySet()
            )
        } catch (e: Exception) {
            null
        }
    }
}
