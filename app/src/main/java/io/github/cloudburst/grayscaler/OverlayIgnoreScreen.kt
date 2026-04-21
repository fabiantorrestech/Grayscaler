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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayIgnoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { OverlayIgnoreStore(context).also { it.load() } }

    var geminiIgnored by remember { mutableStateOf(store.geminiIgnored) }
    var userPackages by remember { mutableStateOf(store.userPackages.toSet()) }
    var showAddDialog by remember { mutableStateOf(false) }

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
                            store.updateGeminiIgnored(it)
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

            items(userPackages.toList(), key = { it }) { pkg ->
                val appName = remember(pkg) {
                    try {
                        val info = context.packageManager.getApplicationInfo(pkg, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    } catch (e: Exception) { null }
                }
                val icon = remember(pkg) {
                    try { context.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icon?.let {
                            Image(
                                bitmap = it.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).padding(end = 8.dp)
                            )
                        }
                        Column {
                            if (appName != null) Text(appName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        store.removePackage(pkg)
                        userPackages = store.userPackages.toSet()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        if (showAddDialog) {
            AddOverlayAppDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { pkg ->
                    if (OverlayIgnoreStore.GEMINI_PACKAGES.contains(pkg)) {
                        // Already covered by the Gemini toggle — inform but don't add to custom list
                    } else {
                        store.addPackage(pkg)
                        userPackages = store.userPackages.toSet()
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

    // Installed apps that hold SYSTEM_ALERT_WINDOW permission
    val overlayApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { info ->
                try {
                    val perms = pm.getPackageInfo(info.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                    perms.requestedPermissions?.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == true &&
                            info.packageName != context.packageName
                } catch (e: Exception) { false }
            }
            .map { info ->
                Triple(
                    info.packageName,
                    pm.getApplicationLabel(info).toString(),
                    try { pm.getApplicationIcon(info) } catch (e: Exception) { null }
                )
            }
            .sortedBy { it.second }
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
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(overlayApps, key = { it.first }) { (pkg, name, icon) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    icon?.let {
                                        Image(
                                            bitmap = it.toBitmap().asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp).padding(end = 8.dp)
                                        )
                                    }
                                    Column {
                                        Text(name, style = MaterialTheme.typography.bodyMedium)
                                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(onClick = { onAdd(pkg) }) { Text("Add") }
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
