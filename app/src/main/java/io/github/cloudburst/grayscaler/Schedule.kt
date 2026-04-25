package io.github.cloudburst.grayscaler

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

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
    val profileBlacklist: Set<String> = emptySet()
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
