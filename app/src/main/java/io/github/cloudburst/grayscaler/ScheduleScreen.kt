package io.github.cloudburst.grayscaler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit, onAddSchedule: () -> Unit, onEditSchedule: (Schedule) -> Unit) {
    val context = LocalContext.current
    val store = remember { ScheduleStore(context).also { it.load() } }
    var schedules by remember { mutableStateOf(store.schedules.toList()) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddSchedule) {
                Icon(Icons.Filled.Add, contentDescription = "Add schedule")
            }
        }
    ) { innerPadding ->
        if (schedules.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No schedules yet", style = MaterialTheme.typography.bodyLarge)
                Text("Tap + to add one", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        onToggle = { enabled ->
                            val updated = schedule.copy(enabled = enabled)
                            store.update(updated)
                            ScheduleManager(context).apply {
                                cancel(schedule.id)
                                if (enabled) register(updated)
                            }
                            schedules = store.schedules.toList()
                        },
                        onDelete = { pendingDeleteId = schedule.id },
                        onEdit = { onEditSchedule(schedule) }
                    )
                }
            }
        }

        pendingDeleteId?.let { id ->
            val target = schedules.find { it.id == id }
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("Delete schedule?") },
                text = { Text("\"${target?.label}\" will be removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        store.remove(id)
                        ScheduleManager(context).cancel(id)
                        schedules = store.schedules.toList()
                        pendingDeleteId = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(schedule.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatDays(schedule.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatTime(schedule.startHour, schedule.startMinute) +
                            " – " + formatTime(schedule.endHour, schedule.endMinute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, minute, amPm)
}

// Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon, ..., 7=Sat
private fun formatDays(days: Set<Int>): String {
    if (days.size == 7) return "Every day"
    val weekdays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    val weekend = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
    if (days == weekdays) return "Weekdays"
    if (days == weekend) return "Weekends"
    val names = mapOf(
        Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
        Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri",
        Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun"
    )
    return days.sortedBy { if (it == Calendar.SUNDAY) 8 else it }
        .mapNotNull { names[it] }.joinToString(", ")
}
