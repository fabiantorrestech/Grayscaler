package io.github.cloudburst.grayscaler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE)
    }

    var experimentalShadeEnabled by remember {
        mutableStateOf(prefs.getBoolean(MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED, false))
    }
    var experimentalShadeMode by remember {
        mutableStateOf(
            prefs.getString(MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE, "ignore") ?: "ignore"
        )
    }
    var debugState by remember {
        mutableStateOf(
            prefs.getString(MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG, "No events yet")
                ?: "No events yet"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Experimental Notification Shade Detection",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Hidden because this is heuristic and OEM-sensitive. Volume and other System UI overlays are intentionally excluded, but false positives/negatives are still possible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Enable experimental shade detection", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Uses Accessibility window heuristics to approximate notification shade state",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = experimentalShadeEnabled,
                    onCheckedChange = { enabled ->
                        experimentalShadeEnabled = enabled
                        prefs.edit()
                            .putBoolean(MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED, enabled)
                            .apply()
                    }
                )
            }

            Text(
                "Experimental shade mode",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SystemUiModeToggle(
                current = experimentalShadeMode,
                enabled = experimentalShadeEnabled,
                onSelect = { mode ->
                    experimentalShadeMode = mode
                    prefs.edit().putString(MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE, mode).apply()
                }
            )

            HorizontalDivider()

            Text(
                "Last debug state",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                debugState,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Re-open this screen after testing to inspect the latest heuristic decision.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
