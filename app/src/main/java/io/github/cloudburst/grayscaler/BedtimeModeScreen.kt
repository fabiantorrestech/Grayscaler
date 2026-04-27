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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BedtimeModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BedtimeStore(context) }
    val manager = remember { BedtimeManager(context) }
    val initial = remember { store.loadSettings() }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var selectedDays by remember { mutableStateOf(initial.days) }
    var activateUponChargingOnly by remember { mutableStateOf(initial.activateUponChargingOnly) }
    var stayActivatedAfterUnplugging by remember { mutableStateOf(initial.stayActivatedAfterUnplugging) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val startState = rememberTimePickerState(
        initialHour = initial.startHour,
        initialMinute = initial.startMinute,
        is24Hour = false
    )
    val endState = rememberTimePickerState(
        initialHour = initial.endHour,
        initialMinute = initial.endMinute,
        is24Hour = false
    )

    fun currentSettings(): BedtimeSettings = BedtimeSettings(
        enabled = enabled,
        days = selectedDays,
        startHour = startState.hour,
        startMinute = startState.minute,
        endHour = endState.hour,
        endMinute = endState.minute,
        activateUponChargingOnly = activateUponChargingOnly,
        stayActivatedAfterUnplugging = stayActivatedAfterUnplugging
    )

    fun persist() {
        val settings = currentSettings()
        if (settings.startHour == settings.endHour && settings.startMinute == settings.endMinute) {
            validationError = "Start time and end time cannot be the same."
            return
        }
        validationError = null
        manager.updateSettings(settings)
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
                title = { Text("Bedtime Mode") },
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
            BedtimeToggleRow("Enable Bedtime mode", enabled) {
                enabled = it
                persist()
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dayLabels.forEach { (day, name) ->
                        FilterChip(
                            selected = selectedDays.contains(day),
                            onClick = {
                                selectedDays = if (selectedDays.contains(day)) selectedDays - day else selectedDays + day
                                persist()
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start Time", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showStartPicker = true }) {
                    Text(formatBedtimeTime(startState.hour, startState.minute))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("End Time", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showEndPicker = true }) {
                    Text(formatBedtimeTime(endState.hour, endState.minute))
                }
                Text(
                    "If end time is earlier than start time, Bedtime Mode continues overnight into the next day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            Text("Activation", style = MaterialTheme.typography.labelLarge)
            BedtimeToggleRow("Activate upon charging only", activateUponChargingOnly) {
                activateUponChargingOnly = it
                persist()
            }
            Text(
                "Bedtime Mode starts only if the phone is charging during bedtime hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            BedtimeToggleRow("Stay activated after unplugging", stayActivatedAfterUnplugging) {
                stayActivatedAfterUnplugging = it
                persist()
            }
            Text(
                "If unplugged after Bedtime Mode qualifies, keep grayscale on until bedtime ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            BedtimeStatusRow("Currently charging", if (store.isChargingNow()) "Yes" else "No")
            BedtimeStatusRow("Within bedtime window", if (store.isWithinWindow(currentSettings())) "Yes" else "No")
            BedtimeStatusRow("Bedtime override active", if (store.bedtimeOverrideActive) "Yes" else "No")

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showStartPicker) {
        BedtimeTimePickerDialog(
            title = "Bedtime Start",
            state = startState,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                showStartPicker = false
                persist()
            }
        )
    }
    if (showEndPicker) {
        BedtimeTimePickerDialog(
            title = "Bedtime End",
            state = endState,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                showEndPicker = false
                persist()
            }
        )
    }
}

@Composable
private fun BedtimeToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BedtimeTimePickerDialog(
    title: String,
    state: androidx.compose.material3.TimePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { androidx.compose.material3.TimePicker(state = state) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BedtimeStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBedtimeTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, minute, amPm)
}
