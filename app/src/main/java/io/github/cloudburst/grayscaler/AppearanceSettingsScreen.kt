package io.github.cloudburst.grayscaler

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val FONT_TARGET_MAIN = "main"
private const val FONT_TARGET_HEADER = "header"
private const val FONT_TARGET_SUBHEADER = "subheader"
private const val FONT_TARGET_TERTIARY = "tertiary"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppearancePreferences.prefs(context) }
    val settings = rememberAppearanceSettings(context)

    var primaryInput by remember(settings.primaryColor) { mutableStateOf(formatColorForInput(settings.primaryColor)) }
    var accentInput by remember(settings.accentColor) { mutableStateOf(formatColorForInput(settings.accentColor)) }
    var backgroundInput by remember(settings.backgroundColor) { mutableStateOf(formatColorForInput(settings.backgroundColor)) }
    var pendingFontTarget by remember { mutableStateOf<String?>(null) }

    val pickFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingFontTarget
        pendingFontTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        runCatching {
            when (target) {
                FONT_TARGET_MAIN -> saveFont(context, AppearancePreferences.KEY_MAIN_FONT_PATH, uri, "main_font")
                FONT_TARGET_HEADER -> saveFont(context, AppearancePreferences.KEY_HEADER_FONT_PATH, uri, "header_font")
                FONT_TARGET_SUBHEADER -> saveFont(context, AppearancePreferences.KEY_SUBHEADER_FONT_PATH, uri, "subheader_font")
                FONT_TARGET_TERTIARY -> saveFont(context, AppearancePreferences.KEY_TERTIARY_FONT_PATH, uri, "tertiary_font")
            }
        }.onFailure {
            Toast.makeText(context, "Failed to import font", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(settings.primaryColor) {
        primaryInput = formatColorForInput(settings.primaryColor)
    }
    LaunchedEffect(settings.accentColor) {
        accentInput = formatColorForInput(settings.accentColor)
    }
    LaunchedEffect(settings.backgroundColor) {
        backgroundInput = formatColorForInput(settings.backgroundColor)
    }

    val primaryParsed = parseHexColor(primaryInput)
    val accentParsed = parseHexColor(accentInput)
    val backgroundParsed = parseHexColor(backgroundInput)
    val primaryError = primaryInput.isNotBlank() && primaryParsed == null
    val accentError = accentInput.isNotBlank() && accentParsed == null
    val backgroundError = backgroundInput.isNotBlank() && backgroundParsed == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    ThemeModeSelector(
                        selected = settings.themeMode,
                        onSelect = { prefs.edit().putString(AppearancePreferences.KEY_THEME_MODE, it).apply() }
                    )
                    SettingRow(
                        title = "Use Material You",
                        description = "Use Android dynamic colors when available."
                    ) {
                        Switch(
                            checked = settings.useDynamicTheme,
                            onCheckedChange = {
                                prefs.edit().putBoolean(AppearancePreferences.KEY_USE_DYNAMIC_THEME, it).apply()
                            }
                        )
                    }
                    SettingRow(
                        title = "OLED black mode",
                        description = "Force a black dark background even when dynamic colors are enabled."
                    ) {
                        Switch(
                            checked = settings.oledMode,
                            onCheckedChange = {
                                prefs.edit().putBoolean(AppearancePreferences.KEY_OLED_MODE, it).apply()
                            }
                        )
                    }
                    HorizontalDivider()
                    Text("Display Scale", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Scale in-app text and related UI from 25% to 200%. Default is 100%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DisplayScaleSelector(
                        selectedPercent = settings.displayScalePercent,
                        onSelect = {
                            prefs.edit().putInt(AppearancePreferences.KEY_DISPLAY_SCALE_PERCENT, it).apply()
                        }
                    )
                    if (!settings.useDynamicTheme) {
                        HorizontalDivider()
                        Text("Custom Colors", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Available when Material You is off. Background color applies when OLED mode is also off. Enter colors as #RRGGBB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ColorField(
                            label = "Main Accent Color",
                            value = primaryInput,
                            isError = primaryError,
                            previewColor = primaryParsed,
                            onValueChange = { primaryInput = it },
                            onApply = {
                                primaryParsed?.let {
                                    prefs.edit().putInt(AppearancePreferences.KEY_PRIMARY_COLOR, it.toArgbCompat()).apply()
                                }
                            },
                            onReset = {
                                prefs.edit().remove(AppearancePreferences.KEY_PRIMARY_COLOR).apply()
                            }
                        )
                        ColorField(
                            label = "Secondary Accent Color",
                            value = accentInput,
                            isError = accentError,
                            previewColor = accentParsed,
                            onValueChange = { accentInput = it },
                            onApply = {
                                accentParsed?.let {
                                    prefs.edit().putInt(AppearancePreferences.KEY_ACCENT_COLOR, it.toArgbCompat()).apply()
                                }
                            },
                            onReset = {
                                prefs.edit().remove(AppearancePreferences.KEY_ACCENT_COLOR).apply()
                            }
                        )
                        ColorField(
                            label = "Background Color",
                            value = backgroundInput,
                            isError = backgroundError,
                            previewColor = backgroundParsed,
                            onValueChange = { backgroundInput = it },
                            onApply = {
                                backgroundParsed?.let {
                                    prefs.edit().putInt(AppearancePreferences.KEY_BACKGROUND_COLOR, it.toArgbCompat()).apply()
                                }
                            },
                            onReset = {
                                prefs.edit().remove(AppearancePreferences.KEY_BACKGROUND_COLOR).apply()
                            }
                        )
                    }
                    HorizontalDivider()
                    Text("Fonts", style = MaterialTheme.typography.titleSmall)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Preview", style = MaterialTheme.typography.titleLarge)
                            Text("Header text sample", style = MaterialTheme.typography.headlineSmall)
                            Text("Subheader text sample", style = MaterialTheme.typography.titleMedium)
                            Text("Body text sample for regular content.", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Tertiary text sample for labels and supporting copy.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SettingRow(
                        title = "Use system font",
                        description = "Disable this to apply custom header, main, and tertiary fonts."
                    ) {
                        Switch(
                            checked = settings.useSystemFont,
                            onCheckedChange = {
                                prefs.edit().putBoolean(AppearancePreferences.KEY_USE_SYSTEM_FONT, it).apply()
                            }
                        )
                    }
                    FontPickerRow(
                        label = "Main Font",
                        value = fontDisplayName(settings.mainFontPath, settings.mainFontName),
                        enabled = !settings.useSystemFont,
                        onPick = {
                            pendingFontTarget = FONT_TARGET_MAIN
                            pickFontLauncher.launch("font/*")
                        },
                        onReset = {
                            AppearancePreferences.clearFont(
                                context,
                                AppearancePreferences.KEY_MAIN_FONT_PATH,
                                AppearancePreferences.KEY_MAIN_FONT_NAME
                            )
                        }
                    )
                    FontPickerRow(
                        label = "Header Font",
                        value = fontDisplayName(settings.headerFontPath, settings.headerFontName),
                        enabled = !settings.useSystemFont,
                        onPick = {
                            pendingFontTarget = FONT_TARGET_HEADER
                            pickFontLauncher.launch("font/*")
                        },
                        onReset = {
                            AppearancePreferences.clearFont(
                                context,
                                AppearancePreferences.KEY_HEADER_FONT_PATH,
                                AppearancePreferences.KEY_HEADER_FONT_NAME
                            )
                        }
                    )
                    FontPickerRow(
                        label = "Subheader Font",
                        value = fontDisplayName(settings.subheaderFontPath, settings.subheaderFontName),
                        enabled = !settings.useSystemFont,
                        onPick = {
                            pendingFontTarget = FONT_TARGET_SUBHEADER
                            pickFontLauncher.launch("font/*")
                        },
                        onReset = {
                            AppearancePreferences.clearFont(
                                context,
                                AppearancePreferences.KEY_SUBHEADER_FONT_PATH,
                                AppearancePreferences.KEY_SUBHEADER_FONT_NAME
                            )
                        }
                    )
                    FontPickerRow(
                        label = "Tertiary Font",
                        value = fontDisplayName(settings.tertiaryFontPath, settings.tertiaryFontName),
                        enabled = !settings.useSystemFont,
                        onPick = {
                            pendingFontTarget = FONT_TARGET_TERTIARY
                            pickFontLauncher.launch("font/*")
                        },
                        onReset = {
                            AppearancePreferences.clearFont(
                                context,
                                AppearancePreferences.KEY_TERTIARY_FONT_PATH,
                                AppearancePreferences.KEY_TERTIARY_FONT_NAME
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayScaleSelector(
    selectedPercent: Int,
    onSelect: (Int) -> Unit,
) {
    val rows = listOf(
        listOf(25, 50, 75, 100),
        listOf(125, 150, 175, 200)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { percent ->
                    val selected = selectedPercent == percent
                    if (selected) {
                        FilledTonalButton(
                            onClick = { onSelect(percent) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("${percent}%")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(percent) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("${percent}%")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        AppearancePreferences.THEME_MODE_SYSTEM to "System",
        AppearancePreferences.THEME_MODE_LIGHT to "Light",
        AppearancePreferences.THEME_MODE_DARK to "Dark",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ColorField(
    label: String,
    value: String,
    isError: Boolean,
    previewColor: Color?,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        color = previewColor ?: MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = true,
                isError = isError,
                supportingText = {
                    if (isError) Text("Use a valid hex color like #2E7D32")
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onApply,
                enabled = !isError && value.isNotBlank(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("Apply")
            }
            FilledTonalButton(
                onClick = onReset,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun FontPickerRow(
    label: String,
    value: String,
    enabled: Boolean,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onPick,
                enabled = enabled,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("Select Font")
            }
            FilledTonalButton(
                onClick = onReset,
                enabled = enabled,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("Reset")
            }
        }
    }
}

private fun saveFont(context: Context, key: String, uri: android.net.Uri, prefix: String) {
    val nameKey = when (key) {
        AppearancePreferences.KEY_MAIN_FONT_PATH -> AppearancePreferences.KEY_MAIN_FONT_NAME
        AppearancePreferences.KEY_HEADER_FONT_PATH -> AppearancePreferences.KEY_HEADER_FONT_NAME
        AppearancePreferences.KEY_SUBHEADER_FONT_PATH -> AppearancePreferences.KEY_SUBHEADER_FONT_NAME
        else -> AppearancePreferences.KEY_TERTIARY_FONT_NAME
    }
    val (path, displayName) = AppearancePreferences.importFontFile(context, uri, prefix)
    AppearancePreferences.clearFont(context, key, nameKey)
    AppearancePreferences.prefs(context).edit()
        .putString(key, path)
        .putString(nameKey, displayName)
        .apply()
}

private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int = toArgb()
