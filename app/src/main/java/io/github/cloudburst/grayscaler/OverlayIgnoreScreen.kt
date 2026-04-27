package io.github.cloudburst.grayscaler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayIgnoreScreen(onBack: () -> Unit, scheduleId: String? = null) {
    val context = LocalContext.current
    val globalStore = remember { OverlayIgnoreStore(context) }
    data class ScheduleContext(val scheduleStore: ScheduleStore, val schedule: Schedule)
    val scheduleContext = remember(scheduleId) {
        if (scheduleId == null) return@remember null
        val scheduleStore = ScheduleStore(context).also { it.load() }
        val schedule = scheduleStore.schedules.find { it.id == scheduleId }
            ?: DraftScheduleSession.get(scheduleId)
            ?: return@remember null
        ScheduleContext(scheduleStore, schedule)
    }

    fun updateSchedule(transform: (Schedule) -> Schedule) {
        val ctx = scheduleContext ?: return
        val latest = ctx.scheduleStore.schedules.find { it.id == ctx.schedule.id }
            ?: DraftScheduleSession.get(ctx.schedule.id)
            ?: ctx.schedule
        val updated = transform(latest)
        if (ctx.scheduleStore.schedules.any { it.id == updated.id }) ctx.scheduleStore.update(updated)
        else DraftScheduleSession.put(updated)
    }

    var geminiIgnored by remember { mutableStateOf(true) }
    var userPackages by remember { mutableStateOf(emptySet<String>()) }
    var userApps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(globalStore, scheduleContext) {
        loading = true
        val loadedState = withContext(Dispatchers.IO) {
            if (scheduleContext != null) {
                scheduleContext.scheduleStore.load()
                val schedule = scheduleContext.scheduleStore.schedules.find { it.id == scheduleContext.schedule.id }
                    ?: DraftScheduleSession.get(scheduleContext.schedule.id)
                    ?: scheduleContext.schedule
                schedule.overlayGeminiIgnored to schedule.overlayUserPackages
            } else {
                globalStore.load()
                globalStore.geminiIgnored to globalStore.userPackages.toSet()
            }
        }
        geminiIgnored = loadedState.first
        userPackages = loadedState.second
        loading = false
    }

    LaunchedEffect(userPackages) {
        userApps = withContext(Dispatchers.Default) {
            userPackages.mapNotNull { AppCatalogRepository.appForPackage(context, it) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ignored Overlay Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add app")
            }
        }
    ) { innerPadding ->
        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                item {
                    // Gemini / Google Assistant group toggle
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Google Assistant / Gemini", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Ignores overlays from Google Search, Gemini, and Google Assistant",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = geminiIgnored,
                            onCheckedChange = {
                                geminiIgnored = it
                                if (scheduleContext != null) {
                                    updateSchedule { schedule -> schedule.copy(overlayGeminiIgnored = it) }
                                } else {
                                    globalStore.updateGeminiIgnored(it)
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    if (userPackages.isNotEmpty()) {
                        Text(
                            "Custom ignored apps",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(userApps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                bitmap = app.icon,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).padding(end = 8.dp)
                            )
                            Column {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = {
                            if (scheduleContext != null) {
                                updateSchedule { schedule ->
                                    schedule.copy(overlayUserPackages = schedule.overlayUserPackages - app.packageName)
                                }
                                userPackages -= app.packageName
                            } else {
                                globalStore.removePackage(app.packageName)
                                userPackages = globalStore.userPackages.toSet()
                            }
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        if (showAddDialog) {
            AddOverlayAppDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { pkg ->
                    if (OverlayIgnoreStore.GEMINI_PACKAGES.contains(pkg)) {
                        // Already covered by the Gemini toggle — inform but don't add to custom list
                    } else {
                        if (scheduleContext != null) {
                            updateSchedule { schedule ->
                                schedule.copy(overlayUserPackages = schedule.overlayUserPackages + pkg)
                            }
                            userPackages += pkg
                        } else {
                            globalStore.addPackage(pkg)
                            userPackages = globalStore.userPackages.toSet()
                        }
                    }
                    showAddDialog = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOverlayAppDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var manualInput by remember { mutableStateOf("") }
    var overlayApps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loadingApps = true
        overlayApps = withContext(Dispatchers.Default) {
            AppCatalogRepository.overlayApps(context)
        }
        loadingApps = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Ignored App") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text("Installed", modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text("Manual", modifier = Modifier.padding(8.dp)) }
                }
                if (selectedTab == 0) {
                    if (loadingApps) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(overlayApps, key = { it.packageName }) { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp).padding(end = 8.dp)
                                        )
                                        Column {
                                            Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                app.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(onClick = { onAdd(app.packageName) }) { Text("Add") }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        label = { Text("Package name") },
                        placeholder = { Text("com.example.app") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            if (selectedTab == 1) {
                TextButton(
                    onClick = { if (manualInput.isNotBlank()) onAdd(manualInput.trim()) },
                    enabled = manualInput.isNotBlank()
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
