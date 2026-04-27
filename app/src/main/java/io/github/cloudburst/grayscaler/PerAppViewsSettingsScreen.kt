package io.github.cloudburst.grayscaler

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppViewsSettingsScreen(onBack: () -> Unit, scheduleId: String? = null) {
    val context = LocalContext.current
    val store = remember { PerAppViewsStore(context) }
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE) }
    data class ScheduleContext(val scheduleStore: ScheduleStore, val schedule: Schedule)
    val scheduleContext = remember(scheduleId) {
        if (scheduleId == null) return@remember null
        val scheduleStore = ScheduleStore(context).also { it.load() }
        val schedule = scheduleStore.schedules.find { it.id == scheduleId }
            ?: DraftScheduleSession.get(scheduleId)
            ?: return@remember null
        ScheduleContext(scheduleStore, schedule)
    }

    fun currentSchedule(): Schedule? {
        val ctx = scheduleContext ?: return null
        ctx.scheduleStore.load()
        return ctx.scheduleStore.schedules.find { it.id == ctx.schedule.id }
            ?: DraftScheduleSession.get(ctx.schedule.id)
            ?: ctx.schedule
    }

    fun updateSchedule(transform: (Schedule) -> Schedule) {
        val ctx = scheduleContext ?: return
        val latest = currentSchedule() ?: return
        val updated = transform(latest)
        if (ctx.scheduleStore.schedules.any { it.id == updated.id }) ctx.scheduleStore.update(updated)
        else DraftScheduleSession.put(updated)
    }

    var masterEnabled by remember {
        mutableStateOf(scheduleContext?.schedule?.perAppViewsProfileEnabled ?: store.masterEnabled)
    }
    var diagnosticEnabled by remember { mutableStateOf(store.diagnosticEnabled) }

    val builtInEnabled = remember {
        PerAppViewsStore.BUILT_IN_ENTRIES.map { entry ->
            mutableStateOf(
                scheduleContext?.schedule?.perAppViewsBuiltInEnabled?.get(entry.id) ?: store.isBuiltInEnabled(entry.id)
            )
        }
    }
    val builtInPatterns = remember {
        PerAppViewsStore.BUILT_IN_ENTRIES.map { entry ->
            mutableStateOf(
                scheduleContext?.schedule?.perAppViewsBuiltInPatterns?.get(entry.id) ?: store.getBuiltInPattern(entry)
            )
        }
    }

    var customEntries by remember {
        mutableStateOf(
            scheduleContext?.schedule?.perAppViewsCustomEntries?.map {
                PerAppViewsStore.CustomEntry(it.packageName, it.classPattern, it.enabled)
            } ?: store.getCustomEntries()
        )
    }
    var lastWindowClass by remember { mutableStateOf(prefs.getString("last_window_class", null)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
                lastWindowClass = prefs.getString("last_window_class", null)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var editingBuiltInIndex by remember { mutableStateOf<Int?>(null) }
    var editPatternInput by remember { mutableStateOf("") }
    LaunchedEffect(editingBuiltInIndex) {
        editPatternInput = editingBuiltInIndex?.let { builtInPatterns[it].value } ?: ""
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var newPkg by remember { mutableStateOf("") }
    var newPattern by remember { mutableStateOf("") }
    LaunchedEffect(showAddDialog) {
        if (showAddDialog) { newPkg = ""; newPattern = "" }
    }

    var editingCustomIndex by remember { mutableStateOf<Int?>(null) }
    var editCustomPkg by remember { mutableStateOf("") }
    var editCustomPattern by remember { mutableStateOf("") }
    LaunchedEffect(editingCustomIndex) {
        editingCustomIndex?.let { i ->
            editCustomPkg = customEntries[i].packageName
            editCustomPattern = customEntries[i].classPattern
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Per-App Views") },
            text = {
                Text(
                    "For certain applications, you may only need 1 view to be whitelisted " +
                        "(commonly, photo and video viewers), but want the rest of the application " +
                        "to remain in Grayscale.\n\n" +
                        "You can utilize the \"Show last seen activity\" toggle to see if your apps " +
                        "utilize different views/activities that you can whitelist here.\n\n" +
                        "1. Add your app's package name (found in Settings > your application > App Info > " +
                        "scroll to the bottom > <com.orgname.packagename>) " +
                        "(e.g. io.github.cloudburst.grayscaler).\n\n" +
                        "2. Once you have \"Show last seen activity\" toggled, you can switch to your app, " +
                        "invoke the photo viewer or whatever view you want whitelisted, then switch back " +
                        "here via the app switcher. You will see the class/view/activity name.\n\n" +
                        "3. Scroll down to the bottom and press \"Add custom app\" to input both the " +
                        "package name and the view/class/activity name."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-App Views") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About per-app views")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (scheduleContext == null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Auto-disable per-app views whitelisting",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(checked = masterEnabled, onCheckedChange = {
                            masterEnabled = it; store.setMasterEnabled(it)
                        })
                    }
                    HorizontalDivider()
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show last seen activity", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Captures the last window class to help find photo viewer patterns",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = diagnosticEnabled, onCheckedChange = {
                        diagnosticEnabled = it; store.setDiagnosticEnabled(it)
                    })
                }
                if (diagnosticEnabled) {
                    val parts = lastWindowClass?.split("\n", limit = 2)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        if (parts != null) {
                            Text(
                                "Package",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                parts[0],
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            val cls = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                            if (cls != null) {
                                Text(
                                    "Class",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    cls,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        } else {
                            Text(
                                "Nothing captured yet — switch to another app and come back",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Text(
                    "Compatible Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(PerAppViewsStore.BUILT_IN_ENTRIES) { index, entry ->
                val enabled = builtInEnabled[index]
                val pattern = builtInPatterns[index]
                val hasPattern = pattern.value.isNotBlank()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                entry.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editingBuiltInIndex = index }) {
                                Text(if (hasPattern) "Edit" else "Set")
                            }
                            Switch(
                                checked = enabled.value && hasPattern,
                                enabled = hasPattern,
                                onCheckedChange = {
                                    enabled.value = it
                                    if (scheduleContext != null) {
                                        updateSchedule { schedule ->
                                            schedule.copy(
                                                perAppViewsBuiltInEnabled = schedule.perAppViewsBuiltInEnabled + (entry.id to it)
                                            )
                                        }
                                    } else {
                                        store.setBuiltInEnabled(entry.id, it)
                                    }
                                }
                            )
                        }
                    }
                    when {
                        hasPattern -> Text(
                            "Pattern: ${pattern.value}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                        entry.patternNote.isNotBlank() -> Text(
                            entry.patternNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                        else -> Text(
                            "Pattern not set — use diagnostic above to find it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }
                    HorizontalDivider()
                }
            }
            item {
                Text(
                    "Custom Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(customEntries) { index, entry ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.packageName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                "Pattern: ${entry.classPattern}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { v ->
                                    val updated = customEntries.toMutableList()
                                        .also { it[index] = entry.copy(enabled = v) }
                                    customEntries = updated
                                    if (scheduleContext != null) {
                                        updateSchedule { schedule ->
                                            schedule.copy(
                                                perAppViewsCustomEntries = updated.map {
                                                    SchedulePerAppViewEntry(it.packageName, it.classPattern, it.enabled)
                                                }
                                            )
                                        }
                                    } else {
                                        store.saveCustomEntries(updated)
                                    }
                                }
                            )
                            IconButton(onClick = { editingCustomIndex = index }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = {
                                val updated = customEntries.toMutableList()
                                    .also { it.removeAt(index) }
                                customEntries = updated
                                if (scheduleContext != null) {
                                    updateSchedule { schedule ->
                                        schedule.copy(
                                            perAppViewsCustomEntries = updated.map {
                                                SchedulePerAppViewEntry(it.packageName, it.classPattern, it.enabled)
                                            }
                                        )
                                    }
                                } else {
                                    store.saveCustomEntries(updated)
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Add custom app")
                }
            }
        }
    }

    if (editingCustomIndex != null) {
        val index = editingCustomIndex!!
        AlertDialog(
            onDismissRequest = { editingCustomIndex = null },
            title = { Text("Edit Custom App") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editCustomPkg,
                        onValueChange = { editCustomPkg = it },
                        label = { Text("Package name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCustomPattern,
                        onValueChange = { editCustomPattern = it },
                        label = { Text("Class pattern") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = customEntries.toMutableList().also {
                            it[index] = PerAppViewsStore.CustomEntry(
                                editCustomPkg.trim(),
                                editCustomPattern.trim(),
                                it[index].enabled
                            )
                        }
                        customEntries = updated
                        if (scheduleContext != null) {
                            updateSchedule { schedule ->
                                schedule.copy(
                                    perAppViewsCustomEntries = updated.map {
                                        SchedulePerAppViewEntry(it.packageName, it.classPattern, it.enabled)
                                    }
                                )
                            }
                        } else {
                            store.saveCustomEntries(updated)
                        }
                        editingCustomIndex = null
                    },
                    enabled = editCustomPkg.isNotBlank() && editCustomPattern.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingCustomIndex = null }) { Text("Cancel") }
            }
        )
    }

    if (editingBuiltInIndex != null) {
        val index = editingBuiltInIndex!!
        val entry = PerAppViewsStore.BUILT_IN_ENTRIES[index]
        AlertDialog(
            onDismissRequest = { editingBuiltInIndex = null },
            title = { Text("Edit Pattern — ${entry.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Class name substring to match when the photo viewer opens.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (entry.patternNote.isNotBlank()) {
                        Text(
                            entry.patternNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = editPatternInput,
                        onValueChange = { editPatternInput = it },
                        label = { Text("Class pattern") },
                        placeholder = { Text("e.g. MediaView") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = editPatternInput.trim()
                    builtInPatterns[index].value = trimmed
                    if (scheduleContext != null) {
                        updateSchedule { schedule ->
                            schedule.copy(
                                perAppViewsBuiltInPatterns = schedule.perAppViewsBuiltInPatterns + (entry.id to trimmed)
                            )
                        }
                    } else {
                        store.setBuiltInPattern(entry.id, trimmed)
                    }
                    editingBuiltInIndex = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingBuiltInIndex = null }) { Text("Cancel") }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Custom App") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Use the diagnostic above to find the package name and class pattern.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newPkg,
                        onValueChange = { newPkg = it },
                        label = { Text("Package name") },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        label = { Text("Class pattern") },
                        placeholder = { Text("PhotoView") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val e = PerAppViewsStore.CustomEntry(newPkg.trim(), newPattern.trim(), true)
                        val updated = customEntries + e
                        customEntries = updated
                        if (scheduleContext != null) {
                            updateSchedule { schedule ->
                                schedule.copy(
                                    perAppViewsCustomEntries = updated.map {
                                        SchedulePerAppViewEntry(it.packageName, it.classPattern, it.enabled)
                                    }
                                )
                            }
                        } else {
                            store.saveCustomEntries(updated)
                        }
                        showAddDialog = false
                    },
                    enabled = newPkg.isNotBlank() && newPattern.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
