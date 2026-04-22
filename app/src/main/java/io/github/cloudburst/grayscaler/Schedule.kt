package io.github.cloudburst.grayscaler

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

// days uses Calendar.DAY_OF_WEEK values: Calendar.SUNDAY=1, MONDAY=2, ..., SATURDAY=7

// TODO (Q1 - per-schedule profiles): Add a `profileMode: String` field ("global" | "whitelist" |
//  "blacklist") and a `profileApps: Set<String>` field (empty = inherit global). When profileMode
//  is not "global", MainService should use this schedule's list instead of AppListStore.
//  Requires: ScheduleStore serialization update + migration, AddEditScheduleScreen app-picker UI,
//  and MainService to receive the active schedule ID from ScheduleReceiver on start.
@Parcelize
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val days: Set<Int>,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true
) : Parcelable {
    fun startMinutes() = startHour * 60 + startMinute
    fun endMinutes() = endHour * 60 + endMinute
}
