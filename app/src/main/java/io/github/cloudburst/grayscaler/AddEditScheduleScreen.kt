package io.github.cloudburst.grayscaler

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditScheduleScreen(
    existing: Schedule? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenAppList: ((String) -> Unit)? = null
) {
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

    // Profile state — initialized from existing schedule, reloaded when returning from WhitelistScreen
    var profileMode by remember { mutableStateOf(existing?.profileMode ?: "global") }
    var profileWhitelist by remember { mutableStateOf(existing?.profileWhitelist ?: emptySet<String>()) }
    var profileBlacklist by remember { mutableStateOf(existing?.profileBlacklist ?: emptySet<String>()) }

    // Reload profile from ScheduleStore whenever this screen resumes (e.g. after returning from
    // the per-schedule WhitelistScreen where the user may have changed the app list or mode).
    if (existing != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    store.load()
                    val reloaded = store.schedules.find { it.id == existing.id }
                    if (reloaded != null) {
                        profileMode = reloaded.profileMode
                        profileWhitelist = reloaded.profileWhitelist
                        profileBlacklist = reloaded.profileBlacklist
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // App list section UI state
    var appListExpanded by remember { mutableStateOf(false) }
    var showImportSchedulePicker by remember { mutableStateOf(false) }
    var pendingImportSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showImportModePicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var conflictSchedule by remember { mutableStateOf<Schedule?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Saves import changes to ScheduleStore immediately (edit mode only).
    fun persistProfileIfEditing(newMode: String = profileMode, newWl: Set<String> = profileWhitelist, newBl: Set<String> = profileBlacklist) {
        if (existing != null) {
            store.update(existing.copy(profileMode = newMode, profileWhitelist = newWl, profileBlacklist = newBl))
        }
    }

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
                Text(
                    "If end time is earlier than start time, the schedule continues overnight into the next day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // App List row
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App List", style = MaterialTheme.typography.labelLarge)
                        Text(
                            when (profileMode) {
                                "whitelist" -> "Whitelist · ${profileWhitelist.size} apps"
                                "blacklist" -> "Blacklist · ${profileBlacklist.size} apps"
                                else -> "Global List"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { appListExpanded = !appListExpanded }) {
                        Icon(
                            if (appListExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Expand app list options"
                        )
                    }
                    OutlinedButton(
                        onClick = { existing?.id?.let { onOpenAppList?.invoke(it) } },
                        enabled = existing != null && onOpenAppList != null
                    ) { Text("Open") }
                }

                AnimatedVisibility(visible = appListExpanded) {
                    Column(
                        modifier = Modifier
                            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Populate from:", style = MaterialTheme.typography.labelMedium)

                        // Option 1: Import from Global App List
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Global App List", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                val appListStore = AppListStore(context).also { it.load() }
                                profileWhitelist = appListStore.whitelistedApps
                                profileBlacklist = appListStore.blacklistedApps
                                persistProfileIfEditing(newWl = profileWhitelist, newBl = profileBlacklist)
                                appListExpanded = false
                            }) { Text("Import") }
                        }

                        // Option 2: Import from Existing Schedule
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Existing Schedule", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showImportSchedulePicker = true }) { Text("Import") }
                        }

                        // Option 3: Clear current mode's list
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Clear ${when (profileMode) { "whitelist" -> "Whitelist"; "blacklist" -> "Blacklist"; else -> "lists" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = { showClearConfirm = true }) { Text("Clear") }
                        }

                        Button(
                            onClick = { appListExpanded = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Confirm") }
                    }
                }
            }

            HorizontalDivider()

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    if (label.isBlank()) { validationError = "Label is required."; return@Button }
                    if (selectedDays.isEmpty()) { validationError = "Select at least one day."; return@Button }
                    if (startState.hour == endState.hour && startState.minute == endState.minute) {
                        validationError = "Start time and end time cannot be the same."
                        return@Button
                    }
                    val candidate = Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        label = label,
                        days = selectedDays,
                        startHour = startState.hour,
                        startMinute = startState.minute,
                        endHour = endState.hour,
                        endMinute = endState.minute,
                        profileMode = profileMode,
                        profileWhitelist = profileWhitelist,
                        profileBlacklist = profileBlacklist
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

        // Import from schedule: picker
        if (showImportSchedulePicker) {
            val otherSchedules = store.schedules.filter { it.id != existing?.id }
            AlertDialog(
                onDismissRequest = { showImportSchedulePicker = false },
                title = { Text("Select Schedule") },
                text = {
                    Column {
                        if (otherSchedules.isEmpty()) {
                            Text("No other schedules available.")
                        } else {
                            otherSchedules.forEach { sched ->
                                TextButton(
                                    onClick = {
                                        pendingImportSchedule = sched
                                        showImportSchedulePicker = false
                                        showImportModePicker = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(sched.label) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showImportSchedulePicker = false }) { Text("Cancel") }
                }
            )
        }

        // Import from schedule: whitelist or blacklist choice
        pendingImportSchedule?.let { sourceSched ->
            if (showImportModePicker) {
                AlertDialog(
                    onDismissRequest = { showImportModePicker = false; pendingImportSchedule = null },
                    title = { Text("Import from \"${sourceSched.label}\"") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Which list? (current mode highlighted)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    when (profileMode) {
                                        "whitelist" -> profileWhitelist = sourceSched.profileWhitelist
                                        "blacklist" -> profileBlacklist = sourceSched.profileWhitelist
                                        else -> profileWhitelist = sourceSched.profileWhitelist
                                    }
                                    persistProfileIfEditing()
                                    showImportModePicker = false
                                    pendingImportSchedule = null
                                    appListExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Whitelist · ${sourceSched.profileWhitelist.size} apps",
                                    fontWeight = if (profileMode == "whitelist") FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            TextButton(
                                onClick = {
                                    when (profileMode) {
                                        "whitelist" -> profileWhitelist = sourceSched.profileBlacklist
                                        "blacklist" -> profileBlacklist = sourceSched.profileBlacklist
                                        else -> profileBlacklist = sourceSched.profileBlacklist
                                    }
                                    persistProfileIfEditing()
                                    showImportModePicker = false
                                    pendingImportSchedule = null
                                    appListExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Blacklist · ${sourceSched.profileBlacklist.size} apps",
                                    fontWeight = if (profileMode == "blacklist") FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showImportModePicker = false; pendingImportSchedule = null }) { Text("Cancel") }
                    }
                )
            }
        }

        // Clear current mode's list
        if (showClearConfirm) {
            val modeLabel = when (profileMode) { "whitelist" -> "Whitelist"; "blacklist" -> "Blacklist"; else -> "both lists" }
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("Clear $modeLabel?") },
                text = { Text("Remove all apps from the $modeLabel?") },
                confirmButton = {
                    TextButton(onClick = {
                        when (profileMode) {
                            "whitelist" -> profileWhitelist = emptySet()
                            "blacklist" -> profileBlacklist = emptySet()
                            else -> { profileWhitelist = emptySet(); profileBlacklist = emptySet() }
                        }
                        persistProfileIfEditing()
                        showClearConfirm = false
                        appListExpanded = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
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
