package io.github.cloudburst.grayscaler

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.cloudburst.grayscaler.ShizukuRunner.Companion.command
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE) }

    var shizukuPackage by remember {
        mutableStateOf(prefs.getString("shizuku_package", null))
    }
    var showShizukuPicker by remember { mutableStateOf(false) }

    val hasWriteSecure = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    val hasUsageStats = ContextCompat.checkSelfPermission(context, android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    val hasQueryAll = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
    else true
    val hasOverlay = Settings.canDrawOverlays(context)
    val accessibilityComponent = remember { ComponentName(context, MainService::class.java) }
    var accessibilityEnabled by remember {
        mutableStateOf(checkAccessibilityServiceEnabled(context, accessibilityComponent))
    }
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                accessibilityEnabled = checkAccessibilityServiceEnabled(context, accessibilityComponent)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotifPermission = granted }

    DisposableEffect(lifecycleOwner) {
        val notifObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasNotifPermission = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(notifObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(notifObserver) }
    }

    val detectedShizukuApps = remember {
        OverlayIgnoreStore.KNOWN_SHIZUKU_PACKAGES
            .filter { pkg ->
                try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                SectionHeader("Shizuku-granted permissions")
                Text(
                    "These are granted automatically. Tap \"Grant All\" if any are missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                PermissionRow("Write Secure Settings", hasWriteSecure)
                PermissionRow("Package Usage Stats", hasUsageStats)
                PermissionRow("Query All Packages", hasQueryAll)
                PermissionRow("Draw Over Other Apps", hasOverlay)
                Button(
                    onClick = {
                        try {
                            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(0)
                                Toast.makeText(context, "Authorize Grayscaler in Shizuku, then try again", Toast.LENGTH_LONG).show()
                            } else {
                                thread {
                                    command("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS") { _, _, _ -> }
                                    command("pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS") { _, _, _ -> }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                                        command("pm grant ${context.packageName} android.permission.QUERY_ALL_PACKAGES") { _, _, _ -> }
                                    command("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow") { _, _, _ -> }
                                }
                            }
                        } catch (e: IllegalStateException) {
                            Toast.makeText(context, "Shizuku is not running", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) { Text("Grant All via Shizuku") }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                SectionHeader("Accessibility Service")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(accessibilityEnabled)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("Grayscaler Service", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (accessibilityEnabled) "Enabled" else "Disabled — tap to enable",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).also {
                            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                    ) { Text("Open") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                SectionHeader("Notifications")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(hasNotifPermission)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("Post Notifications", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (hasNotifPermission) "Granted" else "Required for pause timer notification",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasNotifPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(onClick = {
                                requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Request") }
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text("Settings") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                SectionHeader("Shizuku App")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Selected app", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            shizukuPackage ?: "Not selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showShizukuPicker = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                        ) { Text("Change") }
                        if (shizukuPackage != null) {
                            Button(onClick = {
                                try {
                                    context.startActivity(
                                        context.packageManager.getLaunchIntentForPackage(shizukuPackage!!)
                                            ?: Intent().apply { `package` = shizukuPackage }
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open Shizuku", Toast.LENGTH_SHORT).show()
                                }
                            }, colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )) { Text("Open") }
                        }
                    }
                }
            }
        }

        if (showShizukuPicker) {
            ShizukuPickerDialog(
                detected = detectedShizukuApps,
                current = shizukuPackage,
                onDismiss = { showShizukuPicker = false },
                onSelect = { pkg ->
                    shizukuPackage = pkg
                    prefs.edit().putString("shizuku_package", pkg).apply()
                    showShizukuPicker = false
                }
            )
        }
    }
}

@Composable
private fun ShizukuPickerDialog(
    detected: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    var manualInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Shizuku App") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detected.isNotEmpty()) {
                    Text("Detected on device:", style = MaterialTheme.typography.labelMedium)
                    detected.forEach { pkg ->
                        val name = remember(pkg) {
                            try {
                                val info = context.packageManager.getApplicationInfo(pkg, 0)
                                context.packageManager.getApplicationLabel(info).toString()
                            } catch (e: Exception) { pkg }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onSelect(pkg) }) { Text(if (pkg == current) "Selected" else "Use") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                Text("Or enter package name manually:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    label = { Text("Package name") },
                    placeholder = { Text("moe.shizuku.privileged.api") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (manualInput.isNotBlank()) onSelect(manualInput.trim()) },
                enabled = manualInput.isNotBlank()
            ) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        StatusIcon(granted)
    }
}

@Composable
private fun StatusIcon(ok: Boolean) {
    if (ok) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Granted",
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
        Icon(Icons.Filled.Warning, contentDescription = "Missing", tint = MaterialTheme.colorScheme.error)
    }
}
