package io.github.cloudburst.grayscaler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var liveCountdown by remember { mutableStateOf(WidgetSettingsStore.isLiveCountdown(context)) }
    var statusMonitoring by remember { mutableStateOf(WidgetSettingsStore.isStatusMonitoring(context)) }
    var widgetCustomFont by remember { mutableStateOf(WidgetSettingsStore.isWidgetCustomFont(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget Settings") },
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Widget Display",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "Configure what the homescreen widget shows and how it updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Column {
                    WidgetToggleRow(
                        title = "Live Countdown",
                        subtitle = "Show a live countdown timer while paused, refreshing every minute. " +
                            "Off shows a static end time instead.",
                        checked = liveCountdown,
                        onCheckedChange = { enabled ->
                            WidgetSettingsStore.setLiveCountdown(context, enabled)
                            liveCountdown = enabled
                            val prefs = GrayscalerToggleCoordinator.prefs(context)
                            val pauseUntil = prefs.getLong("pause_until", 0L)
                            if (enabled && pauseUntil > System.currentTimeMillis()) {
                                GrayscalerWidgetReceiver.scheduleTickAlarm(context)
                            } else if (!enabled) {
                                GrayscalerWidgetReceiver.cancelTickAlarm(context)
                            }
                            scope.launch { GrayscalerWidgetReceiver.updateAllWidgets(context) }
                        }
                    )
                    HorizontalDivider()
                    WidgetToggleRow(
                        title = "Status Monitoring",
                        subtitle = "Show grayscaler status (enabled / paused / disabled) in the widget. " +
                            "Disable to reduce widget update overhead.",
                        checked = statusMonitoring,
                        onCheckedChange = { enabled ->
                            WidgetSettingsStore.setStatusMonitoring(context, enabled)
                            statusMonitoring = enabled
                            scope.launch { GrayscalerWidgetReceiver.updateAllWidgets(context) }
                        }
                    )
                    HorizontalDivider()
                    WidgetToggleRow(
                        title = "Use App Font",
                        subtitle = "Apply a distinct built-in fallback font to widget text. Off uses the launcher/system default. " +
                            "Note: the app's actual custom font files cannot be loaded in widgets.",
                        checked = widgetCustomFont,
                        onCheckedChange = { enabled ->
                            WidgetSettingsStore.setWidgetCustomFont(context, enabled)
                            widgetCustomFont = enabled
                            scope.launch { GrayscalerWidgetReceiver.updateAllWidgets(context) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
