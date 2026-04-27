package io.github.cloudburst.grayscaler

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class ScheduleProfileSection {
    APP_LIST,
    PER_APP_VIEWS,
    OVERLAYS,
    WEB_SHORTCUTS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditScheduleScreen(
    existing: Schedule? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenAppList: ((String) -> Unit)? = null,
    onOpenPerAppViews: ((String) -> Unit)? = null,
    onOpenOverlayIgnore: ((String) -> Unit)? = null,
    onOpenWebShortcuts: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val store = remember { ScheduleStore(context).also { it.load() } }
    val scheduleId = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    val isDraft = existing == null

    var label by remember { mutableStateOf(existing?.label ?: "") }
    var selectedDays by remember { mutableStateOf(existing?.days ?: emptySet()) }
    var profileMode by remember { mutableStateOf(existing?.profileMode?.takeIf { it == "blacklist" } ?: "whitelist") }
    var profileWhitelist by remember { mutableStateOf(existing?.profileWhitelist ?: emptySet<String>()) }
    var profileBlacklist by remember { mutableStateOf(existing?.profileBlacklist ?: emptySet<String>()) }
    var appListProfileEnabled by remember { mutableStateOf(existing?.appListProfileEnabled ?: false) }
    var perAppViewsProfileEnabled by remember { mutableStateOf(existing?.perAppViewsProfileEnabled ?: false) }
    var perAppViewsBuiltInEnabled by remember { mutableStateOf(existing?.perAppViewsBuiltInEnabled ?: emptyMap()) }
    var perAppViewsBuiltInPatterns by remember { mutableStateOf(existing?.perAppViewsBuiltInPatterns ?: emptyMap()) }
    var perAppViewsCustomEntries by remember { mutableStateOf(existing?.perAppViewsCustomEntries ?: emptyList()) }
    var overlayProfileEnabled by remember { mutableStateOf(existing?.overlayProfileEnabled ?: false) }
    var overlayGeminiIgnored by remember { mutableStateOf(existing?.overlayGeminiIgnored ?: true) }
    var overlayUserPackages by remember { mutableStateOf(existing?.overlayUserPackages ?: emptySet()) }
    var webShortcutProfileEnabled by remember { mutableStateOf(existing?.webShortcutProfileEnabled ?: false) }
    var webShortcutEntries by remember { mutableStateOf(existing?.webShortcutEntries ?: emptyList()) }
    var webShortcutRules by remember { mutableStateOf(existing?.webShortcutRules ?: emptyMap()) }
    var allowBedtimeOverride by remember { mutableStateOf(existing?.allowBedtimeOverride ?: true) }

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

    fun scheduleFromState(): Schedule = Schedule(
        id = scheduleId,
        label = label,
        days = selectedDays,
        startHour = startState.hour,
        startMinute = startState.minute,
        endHour = endState.hour,
        endMinute = endState.minute,
        enabled = existing?.enabled ?: true,
        profileMode = profileMode,
        profileWhitelist = profileWhitelist,
        profileBlacklist = profileBlacklist,
        appListProfileEnabled = appListProfileEnabled,
        perAppViewsProfileEnabled = perAppViewsProfileEnabled,
        perAppViewsBuiltInEnabled = perAppViewsBuiltInEnabled,
        perAppViewsBuiltInPatterns = perAppViewsBuiltInPatterns,
        perAppViewsCustomEntries = perAppViewsCustomEntries,
        overlayProfileEnabled = overlayProfileEnabled,
        overlayGeminiIgnored = overlayGeminiIgnored,
        overlayUserPackages = overlayUserPackages,
        webShortcutProfileEnabled = webShortcutProfileEnabled,
        webShortcutEntries = webShortcutEntries,
        webShortcutRules = webShortcutRules,
        allowBedtimeOverride = allowBedtimeOverride
    )

    fun persistIfEditing() {
        if (existing != null) store.update(scheduleFromState()) else DraftScheduleSession.put(scheduleFromState())
    }

    fun reloadFromStore() {
        if (existing == null) return
        store.load()
        val reloaded = store.schedules.find { it.id == existing.id } ?: return
        label = reloaded.label
        selectedDays = reloaded.days
        profileMode = reloaded.profileMode.takeIf { it == "blacklist" } ?: "whitelist"
        profileWhitelist = reloaded.profileWhitelist
        profileBlacklist = reloaded.profileBlacklist
        appListProfileEnabled = reloaded.appListProfileEnabled
        perAppViewsProfileEnabled = reloaded.perAppViewsProfileEnabled
        perAppViewsBuiltInEnabled = reloaded.perAppViewsBuiltInEnabled
        perAppViewsBuiltInPatterns = reloaded.perAppViewsBuiltInPatterns
        perAppViewsCustomEntries = reloaded.perAppViewsCustomEntries
        overlayProfileEnabled = reloaded.overlayProfileEnabled
        overlayGeminiIgnored = reloaded.overlayGeminiIgnored
        overlayUserPackages = reloaded.overlayUserPackages
        webShortcutProfileEnabled = reloaded.webShortcutProfileEnabled
        webShortcutEntries = reloaded.webShortcutEntries
        webShortcutRules = reloaded.webShortcutRules
        allowBedtimeOverride = reloaded.allowBedtimeOverride
    }

    if (existing != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) reloadFromStore()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(
        label,
        selectedDays,
        startState.hour,
        startState.minute,
        endState.hour,
        endState.minute,
        profileMode,
        profileWhitelist,
        profileBlacklist,
        appListProfileEnabled,
        perAppViewsProfileEnabled,
        perAppViewsBuiltInEnabled,
        perAppViewsBuiltInPatterns,
        perAppViewsCustomEntries,
        overlayProfileEnabled,
        overlayGeminiIgnored,
        overlayUserPackages,
        webShortcutProfileEnabled,
        webShortcutEntries,
        webShortcutRules,
        allowBedtimeOverride
    ) {
        if (isDraft) DraftScheduleSession.put(scheduleFromState())
    }

    var appListExpanded by remember { mutableStateOf(false) }
    var perAppViewsExpanded by remember { mutableStateOf(false) }
    var overlaysExpanded by remember { mutableStateOf(false) }
    var webShortcutsExpanded by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var conflictSchedule by remember { mutableStateOf<Schedule?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var importPickerSection by remember { mutableStateOf<ScheduleProfileSection?>(null) }
    var confirmTitle by remember { mutableStateOf<String?>(null) }
    var confirmMessage by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val schedule = if (existing != null) {
                store.load()
                store.schedules.find { it.id == scheduleId } ?: scheduleFromState()
            } else {
                scheduleFromState()
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(store.exportScheduleJson(schedule).toString(2))
            } ?: error("Unable to open export destination")
        }.onSuccess {
            Toast.makeText(context, "Schedule backup saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, it.message ?: "Schedule export failed", Toast.LENGTH_LONG).show()
        }
    }

    fun requestConfirmation(title: String, message: String, action: () -> Unit) {
        confirmTitle = title
        confirmMessage = message
        confirmAction = action
    }

    fun applySectionImportFromGlobal(section: ScheduleProfileSection) {
        when (section) {
            ScheduleProfileSection.APP_LIST -> {
                val imported = ScheduleProfiles.importAppListFromGlobal(context, scheduleFromState())
                profileWhitelist = imported.profileWhitelist
                profileBlacklist = imported.profileBlacklist
            }
            ScheduleProfileSection.PER_APP_VIEWS -> {
                val imported = ScheduleProfiles.importPerAppViewsFromGlobal(context, scheduleFromState())
                perAppViewsBuiltInEnabled = imported.perAppViewsBuiltInEnabled
                perAppViewsBuiltInPatterns = imported.perAppViewsBuiltInPatterns
                perAppViewsCustomEntries = imported.perAppViewsCustomEntries
            }
            ScheduleProfileSection.OVERLAYS -> {
                val imported = ScheduleProfiles.importOverlayProfileFromGlobal(context, scheduleFromState())
                overlayGeminiIgnored = imported.overlayGeminiIgnored
                overlayUserPackages = imported.overlayUserPackages
            }
            ScheduleProfileSection.WEB_SHORTCUTS -> {
                val imported = ScheduleProfiles.importWebShortcutsFromGlobal(context, scheduleFromState())
                webShortcutEntries = imported.webShortcutEntries
                webShortcutRules = imported.webShortcutRules
            }
        }
        persistIfEditing()
    }

    fun applySectionImportFromSchedule(section: ScheduleProfileSection, source: Schedule) {
        when (section) {
            ScheduleProfileSection.APP_LIST -> {
                profileMode = source.effectiveAppListMode()
                profileWhitelist = source.profileWhitelist
                profileBlacklist = source.profileBlacklist
            }
            ScheduleProfileSection.PER_APP_VIEWS -> {
                perAppViewsBuiltInEnabled = source.perAppViewsBuiltInEnabled
                perAppViewsBuiltInPatterns = source.perAppViewsBuiltInPatterns
                perAppViewsCustomEntries = source.perAppViewsCustomEntries
            }
            ScheduleProfileSection.OVERLAYS -> {
                overlayGeminiIgnored = source.overlayGeminiIgnored
                overlayUserPackages = source.overlayUserPackages
            }
            ScheduleProfileSection.WEB_SHORTCUTS -> {
                webShortcutEntries = source.webShortcutEntries
                webShortcutRules = source.webShortcutRules
            }
        }
        persistIfEditing()
    }

    fun clearSection(section: ScheduleProfileSection) {
        when (section) {
            ScheduleProfileSection.APP_LIST -> {
                profileWhitelist = emptySet()
                profileBlacklist = emptySet()
            }
            ScheduleProfileSection.PER_APP_VIEWS -> {
                val cleared = ScheduleProfiles.clearPerAppViews(scheduleFromState())
                perAppViewsBuiltInEnabled = cleared.perAppViewsBuiltInEnabled
                perAppViewsBuiltInPatterns = cleared.perAppViewsBuiltInPatterns
                perAppViewsCustomEntries = cleared.perAppViewsCustomEntries
            }
            ScheduleProfileSection.OVERLAYS -> {
                val cleared = ScheduleProfiles.clearOverlayProfile(scheduleFromState())
                overlayGeminiIgnored = cleared.overlayGeminiIgnored
                overlayUserPackages = cleared.overlayUserPackages
            }
            ScheduleProfileSection.WEB_SHORTCUTS -> {
                webShortcutEntries = emptyList()
                webShortcutRules = emptyMap()
            }
        }
        persistIfEditing()
    }

    fun hasSectionData(section: ScheduleProfileSection): Boolean = when (section) {
        ScheduleProfileSection.APP_LIST -> profileWhitelist.isNotEmpty() || profileBlacklist.isNotEmpty()
        ScheduleProfileSection.PER_APP_VIEWS -> perAppViewsBuiltInEnabled.isNotEmpty() ||
            perAppViewsBuiltInPatterns.isNotEmpty() || perAppViewsCustomEntries.isNotEmpty()
        ScheduleProfileSection.OVERLAYS -> overlayUserPackages.isNotEmpty() || !overlayGeminiIgnored
        ScheduleProfileSection.WEB_SHORTCUTS -> webShortcutEntries.isNotEmpty() || webShortcutRules.isNotEmpty()
    }

    fun importFromGlobal(section: ScheduleProfileSection) {
        val label = sectionLabel(section)
        if (hasSectionData(section)) {
            requestConfirmation(
                "Replace $label?",
                "Replace this schedule's $label with the global profile?"
            ) { applySectionImportFromGlobal(section) }
        } else {
            applySectionImportFromGlobal(section)
        }
    }

    fun importFromExisting(section: ScheduleProfileSection) {
        importPickerSection = section
    }

    fun clearProfile(section: ScheduleProfileSection) {
        val label = sectionLabel(section)
        requestConfirmation(
            "Clear $label?",
            "Remove all schedule-specific data from $label?"
        ) { clearSection(section) }
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

    val handleBack: () -> Unit = {
        if (isDraft) DraftScheduleSession.remove(scheduleId)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New Schedule" else "Edit Schedule") },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
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
            androidx.compose.material3.OutlinedTextField(
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
                                selectedDays = if (selectedDays.contains(day)) selectedDays - day else selectedDays + day
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start Time", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showStartPicker = true }) {
                    Text(formatTime(startState.hour, startState.minute))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("End Time", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showEndPicker = true }) {
                    Text(formatTime(endState.hour, endState.minute))
                }
                Text(
                    "If end time is earlier than start time, the schedule continues overnight into the next day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            ProfileSectionRow(
                title = "App List",
                summary = if (appListProfileEnabled) {
                    "${if (profileMode == "blacklist") "Blacklist" else "Whitelist"} · ${if (profileMode == "blacklist") profileBlacklist.size else profileWhitelist.size} apps"
                } else {
                    "Uses Global"
                },
                expanded = appListExpanded,
                onToggleExpanded = { appListExpanded = !appListExpanded },
                onOpen = { onOpenAppList?.invoke(scheduleId) },
                canOpen = onOpenAppList != null
            ) {
                ProfileActionContent(
                    onImportGlobal = { importFromGlobal(ScheduleProfileSection.APP_LIST) },
                    onImportExisting = { importFromExisting(ScheduleProfileSection.APP_LIST) },
                    onClear = { clearProfile(ScheduleProfileSection.APP_LIST) }
                )
            }

            ProfileSectionRow(
                title = "Per-App Views",
                summary = if (perAppViewsProfileEnabled) {
                    "${perAppViewsBuiltInPatterns.count { it.value.isNotBlank() } + perAppViewsCustomEntries.size} rules"
                } else {
                    "Uses Global"
                },
                expanded = perAppViewsExpanded,
                onToggleExpanded = { perAppViewsExpanded = !perAppViewsExpanded },
                onOpen = { onOpenPerAppViews?.invoke(scheduleId) },
                canOpen = onOpenPerAppViews != null
            ) {
                ProfileActionContent(
                    onImportGlobal = { importFromGlobal(ScheduleProfileSection.PER_APP_VIEWS) },
                    onImportExisting = { importFromExisting(ScheduleProfileSection.PER_APP_VIEWS) },
                    onClear = { clearProfile(ScheduleProfileSection.PER_APP_VIEWS) }
                )
            }

            ProfileSectionRow(
                title = "Ignored Overlays",
                summary = if (overlayProfileEnabled) {
                    "${overlayUserPackages.size} custom apps${if (overlayGeminiIgnored) " · Gemini on" else " · Gemini off"}"
                } else {
                    "Uses Global"
                },
                expanded = overlaysExpanded,
                onToggleExpanded = { overlaysExpanded = !overlaysExpanded },
                onOpen = { onOpenOverlayIgnore?.invoke(scheduleId) },
                canOpen = onOpenOverlayIgnore != null
            ) {
                ProfileActionContent(
                    onImportGlobal = { importFromGlobal(ScheduleProfileSection.OVERLAYS) },
                    onImportExisting = { importFromExisting(ScheduleProfileSection.OVERLAYS) },
                    onClear = { clearProfile(ScheduleProfileSection.OVERLAYS) }
                )
            }

            ProfileSectionRow(
                title = "Web Shortcuts",
                summary = if (webShortcutProfileEnabled) {
                    "${webShortcutEntries.size} entries"
                } else {
                    "Uses Global"
                },
                expanded = webShortcutsExpanded,
                onToggleExpanded = { webShortcutsExpanded = !webShortcutsExpanded },
                onOpen = { onOpenWebShortcuts?.invoke(scheduleId) },
                canOpen = onOpenWebShortcuts != null
            ) {
                ProfileActionContent(
                    onImportGlobal = { importFromGlobal(ScheduleProfileSection.WEB_SHORTCUTS) },
                    onImportExisting = { importFromExisting(ScheduleProfileSection.WEB_SHORTCUTS) },
                    onClear = { clearProfile(ScheduleProfileSection.WEB_SHORTCUTS) }
                )
            }

            HorizontalDivider()

            Text("Enable per profile rules", style = MaterialTheme.typography.labelLarge)
            ToggleRow("Use schedule-specific app list", appListProfileEnabled) {
                appListProfileEnabled = it
                persistIfEditing()
            }
            ToggleRow("Use schedule-specific per-app views", perAppViewsProfileEnabled) {
                perAppViewsProfileEnabled = it
                persistIfEditing()
            }
            ToggleRow("Use schedule-specific ignored overlays", overlayProfileEnabled) {
                overlayProfileEnabled = it
                persistIfEditing()
            }
            ToggleRow("Use schedule-specific web shortcuts", webShortcutProfileEnabled) {
                webShortcutProfileEnabled = it
                persistIfEditing()
            }

            HorizontalDivider()

            Text("Bedtime Mode", style = MaterialTheme.typography.labelLarge)
            ToggleRow("Allow Bedtime mode to override this schedule", allowBedtimeOverride) {
                allowBedtimeOverride = it
                persistIfEditing()
                BedtimeManager(context).resync()
            }

            HorizontalDivider()

            Text("Backup", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("${label.ifBlank { "schedule" }}-$stamp.json")
                },
                enabled = existing != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Schedule to file")
            }

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    if (label.isBlank()) {
                        validationError = "Label is required."
                        return@Button
                    }
                    if (selectedDays.isEmpty()) {
                        validationError = "Select at least one day."
                        return@Button
                    }
                    if (startState.hour == endState.hour && startState.minute == endState.minute) {
                        validationError = "Start time and end time cannot be the same."
                        return@Button
                    }

                    val candidate = scheduleFromState()
                    val conflict = store.conflictsWith(candidate)
                    if (conflict != null) {
                        conflictSchedule = conflict
                        return@Button
                    }
                    if (existing != null) store.update(candidate) else store.add(candidate)
                    store.syncRuntimeStateNow()
                    DraftScheduleSession.remove(candidate.id)
                    ScheduleManager(context).apply {
                        cancel(candidate.id)
                        register(candidate)
                    }
                    BedtimeManager(context).resync()
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existing == null) "Add Schedule" else "Save Changes")
            }
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

    importPickerSection?.let { section ->
        val otherSchedules = store.schedules.filter { it.id != existing?.id }
        AlertDialog(
            onDismissRequest = { importPickerSection = null },
            title = { Text("Import ${sectionLabel(section)}") },
            text = {
                Column {
                    if (otherSchedules.isEmpty()) {
                        Text("No other schedules available.")
                    } else {
                        otherSchedules.forEach { sched ->
                            TextButton(
                                onClick = {
                                    importPickerSection = null
                                    val action = { applySectionImportFromSchedule(section, sched) }
                                    if (hasSectionData(section)) {
                                        requestConfirmation(
                                            "Replace ${sectionLabel(section)}?",
                                            "Replace this schedule's ${sectionLabel(section)} with data from \"${sched.label}\"?",
                                            action
                                        )
                                    } else {
                                        action()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(sched.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { importPickerSection = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmTitle != null && confirmMessage != null && confirmAction != null) {
        AlertDialog(
            onDismissRequest = {
                confirmTitle = null
                confirmMessage = null
                confirmAction = null
            },
            title = { Text(confirmTitle!!) },
            text = { Text(confirmMessage!!) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction?.invoke()
                    confirmTitle = null
                    confirmMessage = null
                    confirmAction = null
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmTitle = null
                    confirmMessage = null
                    confirmAction = null
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProfileSectionRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: () -> Unit,
    canOpen: Boolean,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle $title actions"
                )
            }
            OutlinedButton(onClick = onOpen, enabled = canOpen) {
                Text("Open")
            }
        }
        AnimatedVisibility(visible = expanded) {
            content()
        }
    }
}

@Composable
private fun ProfileActionContent(
    onImportGlobal: () -> Unit,
    onImportExisting: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Populate from:", style = MaterialTheme.typography.labelMedium)
        ActionRow("Global", "Import", onImportGlobal)
        ActionRow("Existing Schedule", "Import", onImportExisting)
        ActionRow("Clear to Default", "Clear", onClear)
    }
}

@Composable
private fun ActionRow(label: String, buttonLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onClick) {
            Text(buttonLabel)
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

private fun sectionLabel(section: ScheduleProfileSection): String = when (section) {
    ScheduleProfileSection.APP_LIST -> "App List"
    ScheduleProfileSection.PER_APP_VIEWS -> "Per-App Views"
    ScheduleProfileSection.OVERLAYS -> "Ignored Overlays"
    ScheduleProfileSection.WEB_SHORTCUTS -> "Web Shortcuts"
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
