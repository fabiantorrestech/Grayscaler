package io.github.cloudburst.grayscaler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditScheduleScreen(existing: Schedule? = null, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ScheduleStore(context).also { it.load() } }

    var label by remember { mutableStateOf(existing?.label ?: "") }
    var selectedDays by remember { mutableStateOf(existing?.days ?: emptySet()) }

    val startState = rememberTimePickerState(
        initialHour = existing?.startHour ?: 8,
        initialMinute = existing?.startMinute ?: 0,
        is24Hour = false
    )
    val endState = rememberTimePickerState(
        initialHour = existing?.endHour ?: 17,
        initialMinute = existing?.endMinute ?: 0,
        is24Hour = false
    )

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var conflictSchedule by remember { mutableStateOf<Schedule?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val dayLabels = listOf(
        Calendar.MONDAY to "Mon",
        Calendar.TUESDAY to "Tue",
        Calendar.WEDNESDAY to "Wed",
        Calendar.THURSDAY to "Thu",
        Calendar.FRIDAY to "Fri",
        Calendar.SATURDAY to "Sat",
        Calendar.SUNDAY to "Sun"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New Schedule" else "Edit Schedule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dayLabels.forEach { (day, name) ->
                        FilterChip(
                            selected = selectedDays.contains(day),
                            onClick = {
                                selectedDays = if (selectedDays.contains(day))
                                    selectedDays - day else selectedDays + day
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start Time", style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(onClick = { showStartPicker = true }) {
                        Text(formatTime(startState.hour, startState.minute))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("End Time", style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(onClick = { showEndPicker = true }) {
                        Text(formatTime(endState.hour, endState.minute))
                    }
                }
            }

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    if (label.isBlank()) { validationError = "Label is required."; return@Button }
                    if (selectedDays.isEmpty()) { validationError = "Select at least one day."; return@Button }
                    if (startState.hour * 60 + startState.minute >= endState.hour * 60 + endState.minute) {
                        validationError = "End time must be after start time."
                        return@Button
                    }
                    val candidate = Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        label = label,
                        days = selectedDays,
                        startHour = startState.hour,
                        startMinute = startState.minute,
                        endHour = endState.hour,
                        endMinute = endState.minute
                    )
                    val conflict = store.conflictsWith(candidate)
                    if (conflict != null) { conflictSchedule = conflict; return@Button }
                    if (existing != null) store.update(candidate) else store.add(candidate)
                    ScheduleManager(context).apply {
                        cancel(candidate.id)
                        register(candidate)
                    }
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existing == null) "Add Schedule" else "Save Changes")
            }
        }

        if (showStartPicker) {
            TimePickerDialog(
                title = "Start Time",
                state = startState,
                onDismiss = { showStartPicker = false },
                onConfirm = { showStartPicker = false }
            )
        }
        if (showEndPicker) {
            TimePickerDialog(
                title = "End Time",
                state = endState,
                onDismiss = { showEndPicker = false },
                onConfirm = { showEndPicker = false }
            )
        }

        conflictSchedule?.let { conflict ->
            AlertDialog(
                onDismissRequest = { conflictSchedule = null },
                title = { Text("Schedule Conflict") },
                text = {
                    Text(
                        "This schedule overlaps with \"${conflict.label}\" " +
                                "(${formatDaysShort(conflict.days)}, " +
                                "${formatTime(conflict.startHour, conflict.startMinute)}–" +
                                "${formatTime(conflict.endHour, conflict.endMinute)}). " +
                                "Adjust the days or times to avoid the overlap."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { conflictSchedule = null }) { Text("OK") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    state: androidx.compose.material3.TimePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, minute, amPm)
}

private fun formatDaysShort(days: Set<Int>): String {
    val names = mapOf(
        Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
        Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri",
        Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun"
    )
    return days.sortedBy { if (it == Calendar.SUNDAY) 8 else it }
        .mapNotNull { names[it] }.joinToString(", ")
}
