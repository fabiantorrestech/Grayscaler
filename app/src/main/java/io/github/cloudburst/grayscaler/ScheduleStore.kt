package io.github.cloudburst.grayscaler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
        val text = path.readText()
        if (text.isBlank()) return

        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(text)
            for (index in 0 until array.length()) {
                decodeSchedule(array.optJSONObject(index))?.let { schedules.add(it) }
            }
            return
        }

        path.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                parseLegacyLine(line)?.let { schedules.add(it) }
            }
        }
        save()
    }

    fun save() {
        val array = JSONArray()
        schedules.forEach { array.put(encodeSchedule(it)) }
        path.writeText(array.toString())
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

    fun conflictsWith(candidate: Schedule): Schedule? {
        return schedules.firstOrNull { existing ->
            if (existing.id == candidate.id || !existing.enabled) return@firstOrNull false
            schedulesOverlap(existing, candidate)
        }
    }

    fun isAnyScheduleActiveNow(): Boolean = findActiveScheduleNow() != null

    fun findActiveScheduleNow(): Schedule? {
        val cal = java.util.Calendar.getInstance()
        val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return schedules.firstOrNull { schedule ->
            schedule.enabled &&
                schedule.activeAt(currentDay, currentMinutes)
        }
    }

    fun syncRuntimeStateNow(preferredScheduleId: String? = null): Schedule? {
        val activeSchedule = when {
            preferredScheduleId != null -> schedules.find { it.id == preferredScheduleId && it.enabled }?.takeIf { schedule ->
                val cal = java.util.Calendar.getInstance()
                val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                schedule.activeAt(day, minutes)
            } ?: findActiveScheduleNow()
            else -> findActiveScheduleNow()
        }
        scheduleOverrideActive = activeSchedule != null
        activeScheduleId = activeSchedule?.id
        return activeSchedule
    }

    fun exportScheduleJson(schedule: Schedule): JSONObject {
        return JSONObject().apply {
            put("version", EXPORT_VERSION)
            put("schedule", encodeSchedule(schedule))
        }
    }

    fun decodeExportedSchedule(jsonText: String): Schedule {
        val root = JSONObject(jsonText)
        require(root.optInt("version", -1) == EXPORT_VERSION) {
            "Unsupported schedule backup version: ${root.optInt("version", -1)}"
        }
        return decodeSchedule(root.optJSONObject("schedule"))
            ?: error("Schedule backup did not contain a valid schedule")
    }

    private fun schedulesOverlap(a: Schedule, b: Schedule): Boolean {
        val aSegments = weeklySegments(a)
        val bSegments = weeklySegments(b)
        return aSegments.any { (aStart, aEnd) ->
            bSegments.any { (bStart, bEnd) ->
                aStart < bEnd && bStart < aEnd
            }
        }
    }

    private fun weeklySegments(schedule: Schedule): List<Pair<Int, Int>> {
        val weekMinutes = 7 * 24 * 60
        val segments = mutableListOf<Pair<Int, Int>>()
        for (day in schedule.days) {
            val dayStart = (day - 1) * 24 * 60
            val start = dayStart + schedule.startMinutes()
            val end = start + schedule.durationMinutes()
            if (end <= weekMinutes) {
                segments += start to end
            } else {
                segments += start to weekMinutes
                segments += 0 to (end - weekMinutes)
            }
        }
        return segments
    }

    private fun encodeSchedule(schedule: Schedule): JSONObject {
        return JSONObject().apply {
            put("id", schedule.id)
            put("label", schedule.label)
            put("days", JSONArray(schedule.days.sorted()))
            put("startHour", schedule.startHour)
            put("startMinute", schedule.startMinute)
            put("endHour", schedule.endHour)
            put("endMinute", schedule.endMinute)
            put("enabled", schedule.enabled)
            put("profileMode", schedule.profileMode)
            put("profileWhitelist", JSONArray(schedule.profileWhitelist.sorted()))
            put("profileBlacklist", JSONArray(schedule.profileBlacklist.sorted()))
            put("appListProfileEnabled", schedule.appListProfileEnabled)
            put("perAppViewsProfileEnabled", schedule.perAppViewsProfileEnabled)
            put("perAppViewsBuiltInEnabled", JSONObject(schedule.perAppViewsBuiltInEnabled))
            put("perAppViewsBuiltInPatterns", JSONObject(schedule.perAppViewsBuiltInPatterns))
            put("perAppViewsCustomEntries", JSONArray().apply {
                schedule.perAppViewsCustomEntries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("packageName", entry.packageName)
                            put("classPattern", entry.classPattern)
                            put("enabled", entry.enabled)
                        }
                    )
                }
            })
            put("overlayProfileEnabled", schedule.overlayProfileEnabled)
            put("overlayGeminiIgnored", schedule.overlayGeminiIgnored)
            put("overlayUserPackages", JSONArray(schedule.overlayUserPackages.sorted()))
            put("webShortcutProfileEnabled", schedule.webShortcutProfileEnabled)
            put("webShortcutEntries", JSONArray().apply {
                schedule.webShortcutEntries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("id", entry.id)
                            put("label", entry.label)
                            put("url", entry.url)
                            put("browserPackage", entry.browserPackage)
                            put("isManual", entry.isManual)
                        }
                    )
                }
            })
            put("webShortcutRules", JSONObject(schedule.webShortcutRules))
            put("allowBedtimeOverride", schedule.allowBedtimeOverride)
        }
    }

    private fun decodeSchedule(obj: JSONObject?): Schedule? {
        if (obj == null) return null
        return try {
            Schedule(
                id = obj.getString("id"),
                label = obj.getString("label"),
                days = jsonArrayStrings(obj.optJSONArray("days")).map { it.toInt() }.toSet(),
                startHour = obj.getInt("startHour"),
                startMinute = obj.getInt("startMinute"),
                endHour = obj.getInt("endHour"),
                endMinute = obj.getInt("endMinute"),
                enabled = obj.optBoolean("enabled", true),
                profileMode = obj.optString("profileMode", "global"),
                profileWhitelist = jsonArrayStrings(obj.optJSONArray("profileWhitelist")).toSet(),
                profileBlacklist = jsonArrayStrings(obj.optJSONArray("profileBlacklist")).toSet(),
                appListProfileEnabled = obj.optBoolean("appListProfileEnabled", false),
                perAppViewsProfileEnabled = obj.optBoolean("perAppViewsProfileEnabled", false),
                perAppViewsBuiltInEnabled = jsonObjectBooleans(obj.optJSONObject("perAppViewsBuiltInEnabled")),
                perAppViewsBuiltInPatterns = jsonObjectStrings(obj.optJSONObject("perAppViewsBuiltInPatterns")),
                perAppViewsCustomEntries = obj.optJSONArray("perAppViewsCustomEntries")
                    ?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                val entry = array.optJSONObject(index) ?: continue
                                add(
                                    SchedulePerAppViewEntry(
                                        packageName = entry.optString("packageName"),
                                        classPattern = entry.optString("classPattern"),
                                        enabled = entry.optBoolean("enabled", true)
                                    )
                                )
                            }
                        }
                    }
                    ?: emptyList(),
                overlayProfileEnabled = obj.optBoolean("overlayProfileEnabled", false),
                overlayGeminiIgnored = obj.optBoolean("overlayGeminiIgnored", true),
                overlayUserPackages = jsonArrayStrings(obj.optJSONArray("overlayUserPackages")).toSet(),
                webShortcutProfileEnabled = obj.optBoolean("webShortcutProfileEnabled", false),
                webShortcutEntries = obj.optJSONArray("webShortcutEntries")
                    ?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                val entry = array.optJSONObject(index) ?: continue
                                add(
                                    WebShortcutEntry(
                                        id = entry.optString("id"),
                                        label = entry.optString("label"),
                                        url = entry.optString("url"),
                                        browserPackage = entry.optString("browserPackage").ifBlank { null },
                                        isManual = entry.optBoolean("isManual", true)
                                    )
                                )
                            }
                        }
                    }
                    ?: emptyList(),
                webShortcutRules = jsonObjectStrings(obj.optJSONObject("webShortcutRules")),
                allowBedtimeOverride = obj.optBoolean("allowBedtimeOverride", true)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLegacyLine(line: String): Schedule? {
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
                profileMode = if (p.size > 8) p[8] else "global",
                profileWhitelist = if (p.size > 9 && p[9].isNotBlank()) p[9].split(",").toSet() else emptySet(),
                profileBlacklist = if (p.size > 10 && p[10].isNotBlank()) p[10].split(",").toSet() else emptySet(),
                allowBedtimeOverride = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonArrayStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)?.toString()?.takeIf { it.isNotBlank() } ?: continue
                add(value)
            }
        }
    }

    private fun jsonObjectStrings(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, obj.optString(key))
            }
        }
    }

    private fun jsonObjectBooleans(obj: JSONObject?): Map<String, Boolean> {
        if (obj == null) return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, obj.optBoolean(key, false))
            }
        }
    }

    companion object {
        const val EXPORT_VERSION = 1
    }
}
