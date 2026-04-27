package io.github.cloudburst.grayscaler

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseOverlayContent(
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { GrayscalerToggleCoordinator.prefs(context) }

    var grayscalerEnabled by remember { mutableStateOf(GrayscalerToggleCoordinator.isEnabled(context)) }
    var lastDecision by remember { mutableStateOf(GrayscaleStateManager.lastDecision) }
    var pauseUntil by remember { mutableStateOf(prefs.getLong("pause_until", 0L)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var customValue by remember { mutableStateOf("") }
    var unitExpanded by remember { mutableStateOf(false) }
    var selectedUnit by remember { mutableStateOf("Minutes") }
    var confirmingPauseSeconds by remember { mutableStateOf<Long?>(null) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(pauseUntil) {
        while (pauseUntil > System.currentTimeMillis()) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                GrayscalerToggleCoordinator.KEY_ENABLED -> {
                    grayscalerEnabled = GrayscalerToggleCoordinator.isEnabled(context)
                    lastDecision = GrayscaleStateManager.lastDecision
                }
                "pause_until" -> {
                    pauseUntil = prefs.getLong("pause_until", 0L)
                    now = System.currentTimeMillis()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun applyPause(seconds: Long) {
        context.sendBroadcast(Intent(ScheduleReceiver.ACTION_APPLY_PAUSE).apply {
            setPackage(context.packageName)
            putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
        })
    }

    fun cancelPause() {
        context.sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_END).apply {
            setPackage(context.packageName)
        })
    }

    val applyPauseOrConfirm: (Long) -> Unit = { seconds ->
        val activePauseUntil = prefs.getLong("pause_until", 0L)
        if (activePauseUntil > System.currentTimeMillis()) {
            confirmingPauseSeconds = seconds
        } else {
            applyPause(seconds)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .shadow(28.dp, MaterialTheme.shapes.extraLarge, clip = false)
                .widthIn(max = if (isLandscape) 640.dp else 300.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume taps to prevent scrim dismiss */ },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (confirmingPauseSeconds != null) {
                    Text("Replace active pause?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "A pause is already active. Replace it with the new duration?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = { confirmingPauseSeconds = null },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) { Text("Cancel") }
                        Button(
                            onClick = {
                                applyPause(confirmingPauseSeconds!!)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) { Text("Replace") }
                    }
                    return@Column
                }

                val isPaused = pauseUntil > now
                val statusText = when {
                    !grayscalerEnabled -> "Grayscaler+ Off"
                    isPaused -> {
                        val totalSec = (pauseUntil - now) / 1000
                        val m = totalSec / 60
                        val s = totalSec % 60
                        val countdown = if (m > 0) "${m}m ${s}s" else "${s}s"
                        "Grayscaler+ On · Paused · $countdown"
                    }
                    lastDecision == GrayscaleStateManager.Decision.ENABLE -> "Grayscaler+ On · Enabled"
                    lastDecision == GrayscaleStateManager.Decision.DISABLE -> "Grayscaler+ On · Disabled"
                    else -> "Grayscaler+ On · Not Available"
                }
                val statusColor = when {
                    !grayscalerEnabled -> MaterialTheme.colorScheme.error
                    isPaused -> MaterialTheme.colorScheme.tertiary
                    lastDecision == GrayscaleStateManager.Decision.ENABLE -> MaterialTheme.colorScheme.primary
                    lastDecision == GrayscaleStateManager.Decision.DISABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Pause Grayscaler+",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(0.9f)
                        )
                        VerticalDivider(
                            modifier = Modifier.size(width = 1.dp, height = 42.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Row(
                            modifier = Modifier.weight(1.35f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = statusColor,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor
                                )
                            }
                            if (isPaused) {
                                TextButton(
                                    onClick = {
                                        cancelPause()
                                        pauseUntil = 0L
                                        now = System.currentTimeMillis()
                                        lastDecision = GrayscaleStateManager.lastDecision
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) { Text("Cancel") }
                            }
                        }
                        IconButton(onClick = onOpenApp) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = "Open app")
                        }
                        IconButton(onClick = {
                            grayscalerEnabled = prefs.getBoolean("grayscaler_enabled", true)
                            pauseUntil = prefs.getLong("pause_until", 0L)
                            now = System.currentTimeMillis()
                            lastDecision = GrayscaleStateManager.lastDecision
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh state")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Pause Grayscaler+",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onOpenApp) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = "Open app")
                        }
                        IconButton(onClick = {
                            grayscalerEnabled = prefs.getBoolean("grayscaler_enabled", true)
                            pauseUntil = prefs.getLong("pause_until", 0L)
                            now = System.currentTimeMillis()
                            lastDecision = GrayscaleStateManager.lastDecision
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh state")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = statusColor,
                                modifier = Modifier.size(8.dp)
                            ) {}
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                        if (isPaused) {
                            TextButton(
                                onClick = {
                                    cancelPause()
                                    pauseUntil = 0L
                                    now = System.currentTimeMillis()
                                    lastDecision = GrayscaleStateManager.lastDecision
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) { Text("Cancel") }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Global Toggle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = grayscalerEnabled,
                        onCheckedChange = { enabled ->
                            GrayscalerToggleCoordinator.setEnabled(context, enabled)
                            grayscalerEnabled = enabled
                            lastDecision = GrayscaleStateManager.lastDecision
                        }
                    )
                }

                HorizontalDivider()

                val row1 = listOf("5s" to 5L, "15s" to 15L, "30s" to 30L, "1m" to 60L)
                val row2 = listOf("3m" to 180L, "5m" to 300L, "10m" to 600L, "15m" to 900L)
                val row3 = listOf("30m" to 1800L, "1h" to 3600L)

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "Quick pause",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            QuickPauseRow(row1, applyPauseOrConfirm)
                            QuickPauseRow(row2, applyPauseOrConfirm)
                            QuickPauseRow(items = row3, onClick = applyPauseOrConfirm, fillEmptySlots = false)
                        }
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Column(
                            modifier = Modifier.weight(0.95f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PauseCustomDurationSection(
                                customValue = customValue,
                                onCustomValueChange = { customValue = it },
                                unitExpanded = unitExpanded,
                                onUnitExpandedChange = { unitExpanded = it },
                                selectedUnit = selectedUnit,
                                onSelectedUnitChange = { selectedUnit = it },
                                onCancel = onDismiss,
                                stackFields = true,
                                showTopDivider = false,
                                landscapeActions = true,
                                onApply = {
                                    val value = customValue.toLongOrNull() ?: return@PauseCustomDurationSection
                                    val seconds = when (selectedUnit) {
                                        "Hours" -> value * 3600L
                                        "Minutes" -> value * 60L
                                        else -> value
                                    }
                                    if (seconds > 0) applyPauseOrConfirm(seconds)
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        "Quick pause",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    QuickPauseRow(row1, applyPauseOrConfirm)
                    QuickPauseRow(row2, applyPauseOrConfirm)
                    QuickPauseRow(items = row3, onClick = applyPauseOrConfirm, fillEmptySlots = false)
                    PauseCustomDurationSection(
                        customValue = customValue,
                        onCustomValueChange = { customValue = it },
                        unitExpanded = unitExpanded,
                        onUnitExpandedChange = { unitExpanded = it },
                        selectedUnit = selectedUnit,
                        onSelectedUnitChange = { selectedUnit = it },
                        onCancel = onDismiss,
                        onApply = {
                            val value = customValue.toLongOrNull() ?: return@PauseCustomDurationSection
                            val seconds = when (selectedUnit) {
                                "Hours" -> value * 3600L
                                "Minutes" -> value * 60L
                                else -> value
                            }
                            if (seconds > 0) applyPauseOrConfirm(seconds)
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickPauseRow(
    items: List<Pair<String, Long>>,
    onClick: (Long) -> Unit,
    fillEmptySlots: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (label, seconds) ->
            FilledTonalButton(
                onClick = { onClick(seconds) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (fillEmptySlots) {
            repeat((4 - items.size).coerceAtLeast(0)) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseCustomDurationSection(
    customValue: String,
    onCustomValueChange: (String) -> Unit,
    unitExpanded: Boolean,
    onUnitExpandedChange: (Boolean) -> Unit,
    selectedUnit: String,
    onSelectedUnitChange: (String) -> Unit,
    onCancel: () -> Unit,
    stackFields: Boolean = false,
    showTopDivider: Boolean = true,
    landscapeActions: Boolean = false,
    onApply: () -> Unit
) {
    if (showTopDivider) {
        HorizontalDivider()
    }

    Text(
        "Custom duration",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary
    )

    if (stackFields) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customValue,
                onValueChange = { onCustomValueChange(it.filter { c -> c.isDigit() }) },
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            ExposedDropdownMenuBox(
                expanded = unitExpanded,
                onExpandedChange = { onUnitExpandedChange(it) },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedUnit,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Unit") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { onUnitExpandedChange(false) }
                ) {
                    listOf("Seconds", "Minutes", "Hours").forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(unit) },
                            onClick = { onSelectedUnitChange(unit); onUnitExpandedChange(false) }
                        )
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = customValue,
                onValueChange = { onCustomValueChange(it.filter { c -> c.isDigit() }) },
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            ExposedDropdownMenuBox(
                expanded = unitExpanded,
                onExpandedChange = { onUnitExpandedChange(it) },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedUnit,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Unit") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { onUnitExpandedChange(false) }
                ) {
                    listOf("Seconds", "Minutes", "Hours").forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(unit) },
                            onClick = { onSelectedUnitChange(unit); onUnitExpandedChange(false) }
                        )
                    }
                }
            }
        }
    }

    if (landscapeActions) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) { Text("Cancel") }
            Button(
                onClick = onApply,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("Apply") }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) { Text("Cancel") }
            Button(
                onClick = onApply,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) { Text("Apply") }
        }
    }
}
