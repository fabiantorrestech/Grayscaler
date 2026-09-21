package io.github.cloudburst.grayscaler

import android.app.Activity
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    var autoEnabled by remember { mutableStateOf(AutoBackup.isEnabled(context)) }
    var folderUri by remember { mutableStateOf(AutoBackup.directoryUri(context)) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var folderAccess by remember { mutableStateOf(true) }
    var lastSuccessMs by remember { mutableStateOf(AutoBackup.lastSuccessMs(context)) }
    var lastError by remember { mutableStateOf(AutoBackup.lastError(context)) }
    var backingUp by remember { mutableStateOf(false) }

    fun refreshAutoBackupStatus() {
        autoEnabled = AutoBackup.isEnabled(context)
        folderUri = AutoBackup.directoryUri(context)
        lastSuccessMs = AutoBackup.lastSuccessMs(context)
        lastError = AutoBackup.lastError(context)
        folderAccess = folderUri == null || AutoBackup.hasFolderAccess(context)
    }

    // Background backups update these prefs; reflect them while the screen is open.
    DisposableEffect(Unit) {
        val backupPrefs = AutoBackup.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshAutoBackupStatus() }
        backupPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { backupPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(folderUri) {
        folderAccess = folderUri == null || AutoBackup.hasFolderAccess(context)
        folderName = withContext(Dispatchers.IO) { AutoBackup.folderDisplayName(context) }
    }

    fun backUpNow() {
        backingUp = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { AutoBackup.backupNow(context, force = true) }
            backingUp = false
            refreshAutoBackupStatus()
            val message = when (result) {
                AutoBackup.Result.WRITTEN, AutoBackup.Result.UNCHANGED -> "Backup saved"
                AutoBackup.Result.NO_FOLDER -> "Choose a backup folder first"
                AutoBackup.Result.FAILED -> AutoBackup.lastError(context) ?: "Backup failed"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { AutoBackup.setFolder(context, uri) }
            .onSuccess {
                refreshAutoBackupStatus()
                if (autoEnabled) backUpNow()
            }
            .onFailure {
                Toast.makeText(context, "Could not get lasting access to that folder", Toast.LENGTH_LONG).show()
            }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SettingsBackupManager.exportToUri(context, uri) }
            }
            result.onSuccess {
                Toast.makeText(context, "Settings backup saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Backup export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingImportUri = uri
        showImportConfirm = uri != null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Auto-backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keeps a backup of your settings in a folder you choose, updated a few seconds after any setting changes. It writes ${AutoBackup.SUBFOLDER}/${AutoBackup.FILE_NAME} inside that folder. Point a sync app at it to keep a copy off the phone, and restore it with Import Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable auto-backup", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = autoEnabled,
                            onCheckedChange = { on ->
                                autoEnabled = on
                                AutoBackup.setEnabled(context, on)
                                if (on && folderUri != null) backUpNow()
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(folderUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (folderUri == null) "Choose backup folder" else "Change backup folder")
                    }
                    if (folderUri == null) {
                        Text(
                            "No folder selected. Auto-backup will not run until a folder is chosen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (autoEnabled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Folder: ${folderName ?: "…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Last auto-backup: " + if (lastSuccessMs > 0L) {
                                SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(lastSuccessMs))
                            } else {
                                "Never"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val problem = if (!folderAccess) "Folder access was lost. Choose the folder again." else lastError
                        if (problem != null) {
                            Text(
                                problem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        TextButton(
                            onClick = { backUpNow() },
                            enabled = !backingUp
                        ) {
                            Text(if (backingUp) "Backing up…" else "Back up now")
                        }
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Manual backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Create a portable backup of Grayscaler+ settings, schedules, app lists, web shortcuts, photo viewer rules, overlay ignore rules, and custom fonts.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Import replaces the current saved configuration on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("grayscaler-backup-$stamp.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Settings")
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Settings")
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Included", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Global toggles, UI preferences, schedules, global app list, ignored overlays, web shortcuts, photo viewer settings, Shizuku app selection, and imported fonts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Excluded", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Active pauses and app sessions, schedule and bedtime state that is recalculated from the clock, debug and diagnostic data, and the auto-backup settings themselves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showImportConfirm && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirm = false
                pendingImportUri = null
            },
            title = { Text("Import Settings") },
            text = {
                Text("This will replace the current saved configuration on this device with the selected backup.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImportUri ?: return@TextButton
                    showImportConfirm = false
                    pendingImportUri = null
                    // Stays on the main thread: it re-registers alarms and re-applies grayscale
                    // through code that is not safe to run alongside the accessibility service.
                    runCatching {
                        SettingsBackupManager.importFromUri(context, uri)
                    }.onSuccess {
                        Toast.makeText(context, "Settings imported", Toast.LENGTH_SHORT).show()
                        (context as? Activity)?.recreate()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    pendingImportUri = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
