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
    val enabled: Boolean = true
) : Parcelable {
    fun startMinutes() = startHour * 60 + startMinute
    fun endMinutes() = endHour * 60 + endMinute
}
