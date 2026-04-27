package io.github.cloudburst.grayscaler

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebShortcutScreen(store: WebShortcutStore, onBack: () -> Unit, scheduleId: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

    var entries by remember { mutableStateOf(scheduleContext?.schedule?.webShortcutEntries ?: store.entries) }
    var showAddDialog by remember { mutableStateOf(false) }
    val rulesState = remember {
        mutableStateMapOf<String, String>().also { map ->
            if (scheduleContext != null) {
                scheduleContext.schedule.webShortcutEntries.forEach { entry ->
                    map[entry.id] = scheduleContext.schedule.webShortcutRules[entry.id] ?: "ignore"
                }
            } else {
                store.entries.forEach { map[it.id] = store.ruleFor(it.id) }
            }
        }
    }

    val launcherEntries = entries.filter { !it.isManual }
    val manualEntries = entries.filter { it.isManual }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Shortcuts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add web shortcut")
            }
        }
    ) { innerPadding ->
        val listState = rememberLazyListState()
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 20.dp)
            ) {
                if (launcherEntries.isNotEmpty()) {
                    item {
                        Text(
                            "From Launcher",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(launcherEntries.size) { i ->
                        val entry = launcherEntries[i]
                        WebShortcutRow(
                            entry = entry,
                            rule = rulesState[entry.id] ?: "ignore",
                            onRuleChange = { mode ->
                                rulesState[entry.id] = mode
                                if (scheduleContext != null) {
                                    updateSchedule { schedule ->
                                        schedule.copy(webShortcutRules = schedule.webShortcutRules + (entry.id to mode))
                                    }
                                } else {
                                    store.setRule(entry.id, mode)
                                    store.save()
                                }
                            },
                            onDelete = null
                        )
                    }
                }

                if (manualEntries.isNotEmpty()) {
                    item {
                        if (launcherEntries.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                        Text(
                            "Manual",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(manualEntries.size) { i ->
                        val entry = manualEntries[i]
                        WebShortcutRow(
                            entry = entry,
                            rule = rulesState[entry.id] ?: "ignore",
                            onRuleChange = { mode ->
                                rulesState[entry.id] = mode
                                if (scheduleContext != null) {
                                    updateSchedule { schedule ->
                                        schedule.copy(webShortcutRules = schedule.webShortcutRules + (entry.id to mode))
                                    }
                                } else {
                                    store.setRule(entry.id, mode)
                                    store.save()
                                }
                            },
                            onDelete = {
                                rulesState.remove(entry.id)
                                if (scheduleContext != null) {
                                    updateSchedule { schedule ->
                                        schedule.copy(
                                            webShortcutEntries = schedule.webShortcutEntries.filter { it.id != entry.id },
                                            webShortcutRules = schedule.webShortcutRules - entry.id
                                        )
                                    }
                                    entries = entries.filter { it.id != entry.id }
                                } else {
                                    store.removeManualEntry(entry.id)
                                    store.save()
                                    entries = store.entries
                                }
                            }
                        )
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        Text(
                            "No web shortcuts found.\n\nIf you use CCLauncher, pinned browser shortcuts will appear here automatically.\nOr tap + to add a URL manually.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
            VerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }

    if (showAddDialog) {
        AddWebShortcutDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, url ->
                val added = WebShortcutEntry(
                    id = "manual_${System.currentTimeMillis()}",
                    label = label,
                    url = url,
                    browserPackage = null,
                    isManual = true
                )
                rulesState[added.id] = "ignore"
                if (scheduleContext != null) {
                    updateSchedule { schedule ->
                        schedule.copy(webShortcutEntries = schedule.webShortcutEntries + added)
                    }
                    entries = entries + added
                } else {
                    store.addManualEntry(label, url)
                    store.save()
                    entries = store.entries
                }
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebShortcutRow(
    entry: WebShortcutEntry,
    rule: String,
    onRuleChange: (String) -> Unit,
    onDelete: (() -> Unit)?
) {
    val options = listOf("ignore" to "Ignore", "enable" to "Enable", "disable" to "Disable")

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FaviconImage(
                url = entry.url,
                modifier = Modifier.size(36.dp).padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    WebShortcutStore.extractOrigin(entry.url) ?: entry.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = rule == value,
                    onClick = { onRuleChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label) }
                )
            }
        }
        Text(
            "Applies whenever any browser is open at this URL, not only when launched via shortcut",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun FaviconImage(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val faviconUrl = WebShortcutStore.faviconUrl(url)
                val connection = URL(faviconUrl).openConnection()
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val bmp = BitmapFactory.decodeStream(connection.getInputStream())
                withContext(Dispatchers.Main) { bitmap = bmp }
            } catch (_: Exception) { }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddWebShortcutDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    val urlValid = url.startsWith("http://") || url.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Web Shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (https://...)") },
                    singleLine = true,
                    isError = url.isNotEmpty() && !urlValid,
                    supportingText = if (url.isNotEmpty() && !urlValid) {
                        { Text("Must start with http:// or https://") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.trim(), url.trim()) },
                enabled = label.isNotBlank() && urlValid
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
