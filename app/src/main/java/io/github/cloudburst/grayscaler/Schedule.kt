package io.github.cloudburst.grayscaler

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

@Parcelize
data class SchedulePerAppViewEntry(
    val packageName: String,
    val classPattern: String,
    val enabled: Boolean,
) : Parcelable

// days uses Calendar.DAY_OF_WEEK values: Calendar.SUNDAY=1, MONDAY=2, ..., SATURDAY=7
@Parcelize
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val days: Set<Int>,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    val profileMode: String = "global",         // "global" | "whitelist" | "blacklist"
    val profileWhitelist: Set<String> = emptySet(),
    val profileBlacklist: Set<String> = emptySet(),
    val appListProfileEnabled: Boolean = false,
    val perAppViewsProfileEnabled: Boolean = false,
    val perAppViewsBuiltInEnabled: Map<String, Boolean> = emptyMap(),
    val perAppViewsBuiltInPatterns: Map<String, String> = emptyMap(),
    val perAppViewsCustomEntries: List<SchedulePerAppViewEntry> = emptyList(),
    val overlayProfileEnabled: Boolean = false,
    val overlayGeminiIgnored: Boolean = true,
    val overlayUserPackages: Set<String> = emptySet(),
    val webShortcutProfileEnabled: Boolean = false,
    val webShortcutEntries: List<WebShortcutEntry> = emptyList(),
    val webShortcutRules: Map<String, String> = emptyMap(),
    val allowBedtimeOverride: Boolean = true
) : Parcelable {
    fun startMinutes() = startHour * 60 + startMinute
    fun endMinutes() = endHour * 60 + endMinute
    fun isOvernight() = endMinutes() < startMinutes()
    fun durationMinutes() = if (isOvernight()) (24 * 60 - startMinutes()) + endMinutes() else endMinutes() - startMinutes()

    fun profileApps(): Set<String> = when (profileMode) {
        "whitelist" -> profileWhitelist
        "blacklist" -> profileBlacklist
        else -> emptySet()
    }

    fun effectiveAppListMode(): String = when (profileMode) {
        "blacklist" -> "blacklist"
        else -> "whitelist"
    }

    fun activeAt(dayOfWeek: Int, minutesOfDay: Int): Boolean {
        return if (!isOvernight()) {
            days.contains(dayOfWeek) && minutesOfDay >= startMinutes() && minutesOfDay < endMinutes()
        } else {
            (days.contains(dayOfWeek) && minutesOfDay >= startMinutes()) ||
                (days.contains(previousDay(dayOfWeek)) && minutesOfDay < endMinutes())
        }
    }

    companion object {
        fun nextDay(dayOfWeek: Int): Int = if (dayOfWeek == java.util.Calendar.SATURDAY) java.util.Calendar.SUNDAY else dayOfWeek + 1
        fun previousDay(dayOfWeek: Int): Int = if (dayOfWeek == java.util.Calendar.SUNDAY) java.util.Calendar.SATURDAY else dayOfWeek - 1
    }
}
