package io.github.cloudburst.grayscaler

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE) }

    var pauseUntil by remember { mutableStateOf(prefs.getLong("pause_until", 0L)) }
    var remainingMs by remember { mutableStateOf(0L) }
    var pendingPauseSeconds by remember { mutableStateOf<Long?>(null) }
    var customValue by remember { mutableStateOf("") }
    var unitExpanded by remember { mutableStateOf(false) }
    var selectedUnit by remember { mutableStateOf("Minutes") }
    var countdownNotifEnabled by remember {
        mutableStateOf(prefs.getBoolean("pause_countdown_notif_enabled", true))
    }

    val hasNotifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true

    LaunchedEffect(Unit) {
        while (true) {
            val until = prefs.getLong("pause_until", 0L)
            pauseUntil = until
            remainingMs = (until - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(1000L)
        }
    }

    val isPaused = remainingMs > 0L

    fun applyPause(seconds: Long) {
        val currentUntil = prefs.getLong("pause_until", 0L)
        if (currentUntil > System.currentTimeMillis()) {
            pendingPauseSeconds = seconds
        } else {
            context.sendBroadcast(Intent(ScheduleReceiver.ACTION_APPLY_PAUSE).apply {
                setPackage(context.packageName)
                putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
            })
        }
    }

    if (pendingPauseSeconds != null) {
        AlertDialog(
            onDismissRequest = { pendingPauseSeconds = null },
            title = { Text("Replace active pause?") },
            text = { Text("A pause is already active. Do you want to replace it with the new duration?") },
            confirmButton = {
                TextButton(onClick = {
                    val seconds = pendingPauseSeconds!!
                    pendingPauseSeconds = null
                    context.sendBroadcast(Intent(ScheduleReceiver.ACTION_APPLY_PAUSE).apply {
                        setPackage(context.packageName)
                        putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
                    })
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPauseSeconds = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pause") },
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Status Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPaused) {
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        val reEnableTime = timeFormat.format(Date(pauseUntil))
                        val totalSeconds = remainingMs / 1000
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        val countdownText = if (minutes > 0) "${minutes}m ${seconds}s remaining" else "${seconds}s remaining"

                        Text("Paused until $reEnableTime", style = MaterialTheme.typography.titleMedium)
                        Text(
                            countdownText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            context.sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_END).apply {
                                setPackage(context.packageName)
                            })
                            remainingMs = 0L
                        }) { Text("Cancel Pause") }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("Grayscaler is active", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // 2. Quick Pause
            Text("Quick Pause", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            val row1 = listOf("5s" to 5L, "15s" to 15L, "30s" to 30L, "1m" to 60L)
            val row2 = listOf("3m" to 180L, "5m" to 300L, "10m" to 600L, "15m" to 900L)
            val row3 = listOf("30m" to 1800L, "1h" to 3600L)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row1.forEach { (label, secs) ->
                    FilledTonalButton(
                        onClick = { applyPause(secs) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row2.forEach { (label, secs) ->
                    FilledTonalButton(
                        onClick = { applyPause(secs) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row3.forEach { (label, secs) ->
                    FilledTonalButton(
                        onClick = { applyPause(secs) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }

            HorizontalDivider()

            // 3. Custom Duration
            Text("Custom Duration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customValue,
                    onValueChange = { customValue = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                ExposedDropdownMenuBox(
                    expanded = unitExpanded,
                    onExpandedChange = { unitExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedUnit,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unit") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false }
                    ) {
                        listOf("Seconds", "Minutes", "Hours").forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit) },
                                onClick = { selectedUnit = unit; unitExpanded = false }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val value = customValue.toLongOrNull() ?: return@Button
                    val secs = when (selectedUnit) {
                        "Hours" -> value * 3600L
                        "Minutes" -> value * 60L
                        else -> value
                    }
                    if (secs > 0) applyPause(secs)
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("Apply") }

            HorizontalDivider()

            // 4. Notification Behavior
            Text(
                "Notification Behavior",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (hasNotifPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Show countdown timer notification", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Switches to a live timer in the last 5 minutes of the pause",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = countdownNotifEnabled,
                        onCheckedChange = { enabled ->
                            countdownNotifEnabled = enabled
                            prefs.edit().putBoolean("pause_countdown_notif_enabled", enabled).apply()
                        }
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Notifications are disabled. Enable them in Permissions to track pause status.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenPermissions) { Text("Open") }
                    }
                }
            }
        }
    }
}
