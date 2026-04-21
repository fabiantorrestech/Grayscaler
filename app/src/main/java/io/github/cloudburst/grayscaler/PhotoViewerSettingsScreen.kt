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
fun PhotoViewerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PhotoViewerStore(context) }
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE) }

    var masterEnabled by remember { mutableStateOf(store.masterEnabled) }
    var diagnosticEnabled by remember { mutableStateOf(store.diagnosticEnabled) }

    val builtInEnabled = remember {
        PhotoViewerStore.BUILT_IN_ENTRIES.map { entry ->
            mutableStateOf(store.isBuiltInEnabled(entry.id))
        }
    }
    val builtInPatterns = remember {
        PhotoViewerStore.BUILT_IN_ENTRIES.map { entry ->
            mutableStateOf(store.getBuiltInPattern(entry))
        }
    }

    var customEntries by remember { mutableStateOf(store.getCustomEntries()) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-disable in photo viewers", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = masterEnabled, onCheckedChange = {
                        masterEnabled = it; store.setMasterEnabled(it)
                    })
                }
                HorizontalDivider()
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(PhotoViewerStore.BUILT_IN_ENTRIES) { index, entry ->
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
                                    store.setBuiltInEnabled(entry.id, it)
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
                            color = MaterialTheme.colorScheme.error,
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
                    color = MaterialTheme.colorScheme.primary,
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
                                    store.saveCustomEntries(updated)
                                }
                            )
                            IconButton(onClick = { editingCustomIndex = index }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = {
                                val updated = customEntries.toMutableList()
                                    .also { it.removeAt(index) }
                                customEntries = updated
                                store.saveCustomEntries(updated)
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
                            it[index] = PhotoViewerStore.CustomEntry(
                                editCustomPkg.trim(),
                                editCustomPattern.trim(),
                                it[index].enabled
                            )
                        }
                        customEntries = updated
                        store.saveCustomEntries(updated)
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
        val entry = PhotoViewerStore.BUILT_IN_ENTRIES[index]
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
                    store.setBuiltInPattern(entry.id, trimmed)
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
                        val e = PhotoViewerStore.CustomEntry(newPkg.trim(), newPattern.trim(), true)
                        val updated = customEntries + e
                        customEntries = updated
                        store.saveCustomEntries(updated)
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
