package io.github.cloudburst.grayscaler

import android.content.Intent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

    var diagnosticLoggingEnabled by remember {
        mutableStateOf(prefs.getBoolean(GrayscaleLog.PREF_ENABLED, false))
    }
    var forceRetoggleEnabled by remember {
        mutableStateOf(prefs.getBoolean(GrayscaleLog.PREF_FORCE_RETOGGLE, false))
    }
    var diagRefreshTick by remember { mutableStateOf(0) }
    var perfRefreshTick by remember { mutableStateOf(0) }
    val perfReport = remember(perfRefreshTick) { PerfStats.report(context) }
    var perfGpuCrop by remember {
        mutableStateOf(prefs.getBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_GPU_CROP, false))
    }
    var perfTreePrefetch by remember {
        mutableStateOf(prefs.getBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_TREE_PREFETCH, false))
    }
    var perfKeepLabels by remember {
        mutableStateOf(prefs.getBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_KEEP_LABELS, false))
    }
    val grayscaleStateSnapshot = remember(diagRefreshTick) {
        // Same snapshot the global toggle logs before and after a reset, one field per line.
        GrayscaleStateManager.stateSnapshot(context).replace(' ', '\n')
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
    var whatsAppReadIndicatorEnabled by remember {
        mutableStateOf(prefs.getBoolean(MainService.PREF_WHATSAPP_READ_INDICATOR_ENABLED, false))
    }
    var whatsAppScanDebounceMs by remember {
        mutableStateOf(
            prefs.getInt(MainService.PREF_WHATSAPP_SCAN_DEBOUNCE_MS, MainService.WHATSAPP_SCAN_DEBOUNCE_DEFAULT_MS)
        )
    }
    var whatsAppRescanRetryMs by remember {
        mutableStateOf(
            prefs.getInt(MainService.PREF_WHATSAPP_RESCAN_RETRY_MS, MainService.WHATSAPP_RESCAN_RETRY_DEFAULT_MS)
        )
    }
    var whatsAppRescanRetryCount by remember {
        mutableStateOf(
            prefs.getInt(MainService.PREF_WHATSAPP_RESCAN_RETRY_COUNT, MainService.WHATSAPP_RESCAN_RETRY_COUNT_DEFAULT)
        )
    }
    var whatsAppProbeEnabled by remember {
        mutableStateOf(prefs.getBoolean(MainService.PREF_WHATSAPP_COLOR_PROBE_ENABLED, false))
    }
    var whatsAppProbeResult by remember {
        mutableStateOf(
            prefs.getString(MainService.PREF_WHATSAPP_COLOR_PROBE_RESULT, "No probes yet")
                ?: "No probes yet"
        )
    }
    val whatsAppChatListDebug by remember {
        mutableStateOf(
            prefs.getString(WhatsAppReadIndicatorScanner.PREF_WHATSAPP_CHATLIST_DEBUG, "No chat-list scan yet")
                ?: "No chat-list scan yet"
        )
    }
    val whatsAppConvoDebug by remember {
        mutableStateOf(
            prefs.getString(WhatsAppReadIndicatorScanner.PREF_WHATSAPP_CONVO_DEBUG, "No conversation scan yet")
                ?: "No conversation scan yet"
        )
    }
    val whatsAppShadeDebug by remember {
        mutableStateOf(
            prefs.getString(MainService.PREF_WHATSAPP_SHADE_DEBUG, "No shade transitions yet")
                ?: "No shade transitions yet"
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
                "Performance counters",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Always counting, in memory only. Reset, use the phone for a while, then Refresh and Copy. Rates are per minute since the last reset; a service restart also resets them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(perfReport, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { perfRefreshTick++ }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    PerfStats.reset()
                    perfRefreshTick++
                }) { Text("Reset") }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Grayscaler+ counters", PerfStats.report(context))
                    )
                }) { Text("Copy") }
            }

            Text(
                "Performance experiments (WhatsApp labels)",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "Each one is off by default. Turn one on, Reset the counters, use WhatsApp for a few minutes and compare. Labels must look the same as with it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PerfExperimentRow(
                title = "Crop screenshot on the GPU",
                description = "Reads back only the strip with the ticks instead of the whole screen. Android 10+. Compare 'bitmap copy' and 'bitmap KB copied'.",
                checked = perfGpuCrop,
                onCheckedChange = { enabled ->
                    perfGpuCrop = enabled
                    prefs.edit().putBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_GPU_CROP, enabled).apply()
                }
            )
            PerfExperimentRow(
                title = "Depth-first tree prefetch",
                description = "Fetches WhatsApp's screen tree in the order it is walked. Android 13+. Compare 'WhatsApp tree walk'.",
                checked = perfTreePrefetch,
                onCheckedChange = { enabled ->
                    perfTreePrefetch = enabled
                    prefs.edit().putBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_TREE_PREFETCH, enabled).apply()
                }
            )
            PerfExperimentRow(
                title = "Keep labels until the rescan",
                description = "Labels stay up while WhatsApp changes and are replaced in one go; captures only WhatsApp's window. Android 14+. After a new message they may sit on the old rows for up to a second. Compare 'label overlay redraws'.",
                checked = perfKeepLabels,
                onCheckedChange = { enabled ->
                    perfKeepLabels = enabled
                    prefs.edit().putBoolean(WhatsAppReadIndicatorScanner.PREF_PERF_KEEP_LABELS, enabled).apply()
                }
            )

            HorizontalDivider()

            Text(
                "Grayscale state diagnostics",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Logs every grayscale apply with the value read back from the system, plus which caller and thread triggered it. Enable this, reproduce the stuck-in-color state, then Share log and send the file over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Enable diagnostic logging", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off by default. Writes to an app-private file that self-trims at ~256 KB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = diagnosticLoggingEnabled,
                    onCheckedChange = { enabled ->
                        diagnosticLoggingEnabled = enabled
                        prefs.edit().putBoolean(GrayscaleLog.PREF_ENABLED, enabled).apply()
                        diagRefreshTick++
                    }
                )
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Force daltonizer re-toggle on enable", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "RC6 test. When grayscale is switched on but the value is already 1, force a 0→1 transition (may briefly flash to color). Reproduce the stuck-in-color state with this OFF first; if flipping it ON restores gray, idempotent writes are the cause.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = forceRetoggleEnabled,
                    onCheckedChange = { enabled ->
                        forceRetoggleEnabled = enabled
                        prefs.edit().putBoolean(GrayscaleLog.PREF_FORCE_RETOGGLE, enabled).apply()
                        diagRefreshTick++
                    }
                )
            }
            Text(
                "Belief vs screen (tap Refresh after reproducing):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(grayscaleStateSnapshot, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { diagRefreshTick++ }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    val text = GrayscaleLog.read(context).ifEmpty { "(log empty)" }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Grayscaler diagnostic log")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share grayscale log"))
                }) { Text("Share log") }
                OutlinedButton(onClick = {
                    GrayscaleLog.clear(context)
                    diagRefreshTick++
                }) { Text("Clear log") }
            }

            HorizontalDivider()

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

            HorizontalDivider()

            Text(
                "WhatsApp read indicators (experimental)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Annotates each outgoing message's checkmark with a small Read / Delivered / Sent chip while inside WhatsApp. Uses the accessibility tree to locate checkmarks and a screenshot to interpret their underlying color (color survives the system grayscale transform). A floating bubble in the WhatsApp window lets you trigger an immediate scan. Heuristic — WhatsApp updates may break detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Enable WhatsApp read indicators", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Adds inline chips next to checkmarks and a tap-to-scan bubble inside WhatsApp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = whatsAppReadIndicatorEnabled,
                    onCheckedChange = { enabled ->
                        whatsAppReadIndicatorEnabled = enabled
                        prefs.edit()
                            .putBoolean(MainService.PREF_WHATSAPP_READ_INDICATOR_ENABLED, enabled)
                            .apply()
                    }
                )
            }

            Text(
                "Scan interval",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "How long to wait after WhatsApp's content settles before re-scanning. Shorter = labels track new messages faster but cost more battery; longer = easier on battery but labels lag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val scanIntervalOptions = remember {
                listOf(
                    200 to "Responsive",
                    MainService.WHATSAPP_SCAN_DEBOUNCE_DEFAULT_MS to "Default",
                    500 to "Balanced",
                    1500 to "Power saver",
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                scanIntervalOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = whatsAppScanDebounceMs == value,
                        enabled = whatsAppReadIndicatorEnabled,
                        onClick = {
                            whatsAppScanDebounceMs = value
                            prefs.edit()
                                .putInt(MainService.PREF_WHATSAPP_SCAN_DEBOUNCE_MS, value)
                                .apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, scanIntervalOptions.size),
                        label = { Text("$label\n${value}ms") }
                    )
                }
            }

            Text(
                "Empty-scan retry delay",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "When a scan returns no labels (often because WhatsApp's accessibility tree hasn't fully settled after a navigation), retry once after this delay. Reset on the next content change. Higher = slower recovery but more reliable; lower = snappier but may double-fail on slower devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val rescanRetryOptions = remember {
                listOf(
                    500 to "Fast",
                    MainService.WHATSAPP_RESCAN_RETRY_DEFAULT_MS to "Default",
                    1000 to "Patient",
                    1500 to "Slow device",
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                rescanRetryOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = whatsAppRescanRetryMs == value,
                        enabled = whatsAppReadIndicatorEnabled,
                        onClick = {
                            whatsAppRescanRetryMs = value
                            prefs.edit()
                                .putInt(MainService.PREF_WHATSAPP_RESCAN_RETRY_MS, value)
                                .apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, rescanRetryOptions.size),
                        label = { Text("$label\n${value}ms") }
                    )
                }
            }

            Text(
                "Empty-scan retry attempts",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "How many times to retry an empty scan before giving up. A fresh content-change mid-chain will NOT reset this budget — that's the whole point: it keeps retrying through WhatsApp's settling animations (e.g. chat → all-chats) instead of being nuked by every new event. Total recovery window ≈ retry-delay × attempts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val rescanRetryCountOptions = remember {
                listOf(
                    2 to "Snappy",
                    MainService.WHATSAPP_RESCAN_RETRY_COUNT_DEFAULT to "Default",
                    4 to "Persistent",
                    5 to "Stubborn",
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                rescanRetryCountOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = whatsAppRescanRetryCount == value,
                        enabled = whatsAppReadIndicatorEnabled,
                        onClick = {
                            whatsAppRescanRetryCount = value
                            prefs.edit()
                                .putInt(MainService.PREF_WHATSAPP_RESCAN_RETRY_COUNT, value)
                                .apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, rescanRetryCountOptions.size),
                        label = { Text("$label\n$value×") }
                    )
                }
            }

            HorizontalDivider()

            Text(
                "WhatsApp screenshot color probe",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Temporary diagnostic. When enabled, opening WhatsApp triggers an AccessibilityService screenshot, samples the center pixel, and toasts the RGB. If R≈G≈B, the system grayscale transform is baked into the screenshot and the color-detection path for the read/delivered/sent feature cannot work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Enable color probe on WhatsApp", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Throttled to one screenshot every 2 s when WhatsApp foregrounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = whatsAppProbeEnabled,
                    onCheckedChange = { enabled ->
                        whatsAppProbeEnabled = enabled
                        prefs.edit()
                            .putBoolean(MainService.PREF_WHATSAPP_COLOR_PROBE_ENABLED, enabled)
                            .apply()
                    }
                )
            }
            Text(
                "Last probe result",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                whatsAppProbeResult,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Re-open this screen after a probe to inspect the latest result.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text(
                "Chat-list scan diagnostics",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "When the color probe is enabled, each chat-list scan dumps per-row anchor rects, search regions, blob results (or rejection reason), background RGB, and the classified label here. Use this to figure out why the chat-list path is missing checkmarks on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                whatsAppChatListDebug,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Re-open this screen after a scan to inspect the latest diagnostic dump.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text(
                "Conversation scan diagnostics",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "When the color probe is enabled, each in-conversation scan dumps every contentDescription that matched a Read/Delivered/Sent regex, with the node className, bounds, width-in-dp, and whether the width sanity check accepted or rejected it. After classification, each accepted candidate's iconBox, sampled RGB, and final label are logged. Use this to figure out why image-bubble status indicators aren't getting chips.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                whatsAppConvoDebug,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Re-open this screen after a scan to inspect the latest diagnostic dump.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text(
                "Shade / obstruction debug",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Records the latest transition when SystemUI (notification shade, app switcher, lockscreen) covers or uncovers WhatsApp. If the WhatsApp chips don't hide when you pull down the shade, this line will tell us whether the active-root signal fired.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                whatsAppShadeDebug,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Re-open this screen after pulling the shade to inspect the latest transition.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PerfExperimentRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
