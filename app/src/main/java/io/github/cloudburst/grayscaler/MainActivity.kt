package io.github.cloudburst.grayscaler

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.cloudburst.grayscaler.ShizukuRunner.Companion.command
import io.github.cloudburst.grayscaler.ShizukuRunner.Companion.shizukuEnabled
import io.github.cloudburst.grayscaler.ui.theme.GrayscalerTheme
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    lateinit var store: AppListStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasAllPermissions()) {
            if (shizukuEnabled(this)) {
                thread {
                    val barrier = CyclicBarrier(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) 5 else 4)
                    command("pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS") { msg, _, error ->
                        if (error) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        barrier.await()
                    }
                    command("pm grant ${packageName} android.permission.PACKAGE_USAGE_STATS") { msg, _, error ->
                        if (error) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        barrier.await()
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        command("pm grant ${packageName} android.permission.QUERY_ALL_PACKAGES") { msg, _, error ->
                            if (error) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            barrier.await()
                        }
                    command("appops set ${packageName} SYSTEM_ALERT_WINDOW allow") { msg, _, error ->
                        if (error) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        barrier.await()
                    }
                    barrier.await()
                    runOnUiThread {
                        finish()
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                }
            } else {
                val prefs = getSharedPreferences("grayscaler_prefs", MODE_PRIVATE)
                val shizukuPkg = prefs.getString("shizuku_package", null)
                    ?: OverlayIgnoreStore.KNOWN_SHIZUKU_PACKAGES.firstOrNull { pkg ->
                        try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
                    }
                if (shizukuPkg != null) {
                    startActivity(packageManager.getLaunchIntentForPackage(shizukuPkg))
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                }
                finish()
            }
        }

        store = AppListStore(this)
        store.load()

        setContent {
            GrayscalerTheme {
                AppNavigation(store)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        store.save()
    }

    private fun hasAllPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.PACKAGE_USAGE_STATS) != PackageManager.PERMISSION_GRANTED) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.QUERY_ALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }
}

@Composable
private fun AppNavigation(store: AppListStore) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                store = store,
                onOpenSchedules = { navController.navigate("schedules") },
                onOpenOverlayIgnore = { navController.navigate("overlay_ignore") },
                onOpenPermissions = { navController.navigate("permissions") },
                onOpenPhotoViewer = { navController.navigate("photo_viewer") },
                onOpenWhitelist = { navController.navigate("whitelist") }
            )
        }
        composable("schedules") {
            ScheduleScreen(
                onBack = { navController.popBackStack() },
                onAddSchedule = { navController.navigate("add_schedule") },
                onEditSchedule = { schedule ->
                    navController.currentBackStackEntry?.savedStateHandle?.set("edit_schedule", schedule)
                    navController.navigate("edit_schedule")
                }
            )
        }
        composable("add_schedule") {
            AddEditScheduleScreen(
                existing = null,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable("edit_schedule") {
            val schedule = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Schedule>("edit_schedule")
            AddEditScheduleScreen(
                existing = schedule,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable("overlay_ignore") {
            OverlayIgnoreScreen(onBack = { navController.popBackStack() })
        }
        composable("permissions") {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
        composable("photo_viewer") {
            PhotoViewerSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("whitelist") {
            WhitelistScreen(store = store, onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    store: AppListStore,
    onOpenSchedules: () -> Unit,
    onOpenOverlayIgnore: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPhotoViewer: () -> Unit,
    onOpenWhitelist: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE) }

    var grayscalerEnabled by remember { mutableStateOf(prefs.getBoolean("grayscaler_enabled", true)) }
    var showHelp by remember { mutableStateOf(false) }
    var appSwitcherMode by remember { mutableStateOf(prefs.getString("app_switcher_mode", "ignore") ?: "ignore") }
    var notifCenterMode by remember { mutableStateOf(prefs.getString("notification_center_mode", "ignore") ?: "ignore") }
    var lockscreenMode by remember { mutableStateOf(prefs.getString("lockscreen_mode", "ignore") ?: "ignore") }
    var powerMenuMode by remember { mutableStateOf(prefs.getString("power_menu_mode", "disable") ?: "disable") }
    var inlineReplyMode by remember { mutableStateOf(prefs.getString("inline_reply_mode", "ignore") ?: "ignore") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grayscaler") },
                actions = {
                    // Global on/off master switch
                    Switch(
                        checked = grayscalerEnabled,
                        onCheckedChange = { enabled ->
                            grayscalerEnabled = enabled
                            prefs.edit().putBoolean("grayscaler_enabled", enabled).apply()
                            if (!enabled) {
                                Settings.Secure.putInt(
                                    context.contentResolver,
                                    MainService.DISPLAY_DALTONIZER_ENABLED,
                                    MainService.OFF
                                )
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Shortcut setup help")
                    }
                    IconButton(onClick = onOpenSchedules) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Schedules")
                    }
                    IconButton(onClick = onOpenOverlayIgnore) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Ignored overlays")
                    }
                    IconButton(onClick = onOpenPermissions) {
                        Icon(Icons.Filled.Lock, contentDescription = "Permissions")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Text(
                "System UI Behavior",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Photo Viewer", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Per-app auto-disable settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onOpenPhotoViewer) { Text("Open") }
                }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "App Switcher",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    SystemUiModeToggle(appSwitcherMode) { mode ->
                        appSwitcherMode = mode
                        prefs.edit().putString("app_switcher_mode", mode).apply()
                    }
                }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "Notification Center",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    SystemUiModeToggle(notifCenterMode) { mode ->
                        notifCenterMode = mode
                        prefs.edit().putString("notification_center_mode", mode).apply()
                    }
                }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "Lock Screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    SystemUiModeToggle(lockscreenMode) { mode ->
                        lockscreenMode = mode
                        prefs.edit().putString("lockscreen_mode", mode).apply()
                    }
                }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "Notification Reply (Typing)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        "Applies when typing a reply directly in a notification",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    SystemUiModeToggle(inlineReplyMode) { mode ->
                        inlineReplyMode = mode
                        prefs.edit().putString("inline_reply_mode", mode).apply()
                    }
                }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "Power Menu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        "Disable recommended — keeps emergency button visible",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    SystemUiModeToggle(powerMenuMode) { mode ->
                        powerMenuMode = mode
                        prefs.edit().putString("power_menu_mode", mode).apply()
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("App List", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Whitelist / Blacklist settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onOpenWhitelist) { Text("Open") }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Shortcut Setup") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Trigger the pause overlay from Key Mapper, Macrodroid, Tasker, or any automation app — including from the lock screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                    Text("Broadcast Intent", style = MaterialTheme.typography.labelLarge)
                    IntentField("Action", "io.github.cloudburst.grayscaler\n.ACTION_PAUSE_GRAYSCALER")
                    IntentField("Package", "io.github.cloudburst.grayscaler")
                    IntentField("Class", "io.github.cloudburst.grayscaler\n.ScheduleReceiver")
                    HorizontalDivider()
                    Text("Key Mapper", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "1. New mapping → your trigger\n2. Add Action → Intent → Send Broadcast\n3. Set Action, Package, and Class as above",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text("Macrodroid / Tasker", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "1. New macro → your trigger\n2. Add Action → Send Intent → Broadcast\n3. Set Action and Package as above",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Got it") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemUiModeToggle(current: String, onSelect: (String) -> Unit) {
    val options = listOf("ignore" to "Ignore", "enable" to "Enable", "disable" to "Disable")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = current == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label) }
            )
        }
    }
}

@Composable
internal fun VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbHeight: Dp = 48.dp,
    width: Dp = 12.dp
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    val targetAlpha = if (isDragging || state.isScrollInProgress) 1f else 0.35f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbar_alpha"
    )

    BoxWithConstraints(modifier = modifier) {
        val thumbHeightPx = with(LocalDensity.current) { thumbHeight.toPx() }
        val trackHeightPx = constraints.maxHeight.toFloat()
        val thumbRange = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

        val layoutInfo = state.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        val scrollableItems = (totalItems - visibleItems.size).coerceAtLeast(1)

        val thumbY = if (visibleItems.isNotEmpty()) {
            val fraction = visibleItems.first().index.toFloat() / scrollableItems
            (fraction * thumbRange).coerceIn(0f, thumbRange)
        } else 0f

        val showScrollbar = totalItems > visibleItems.size

        if (showScrollbar) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val info = state.layoutInfo
                            val total = info.totalItemsCount
                            val visible = info.visibleItemsInfo
                            val scrollable = (total - visible.size).coerceAtLeast(1)
                            val firstIndex = visible.firstOrNull()?.index ?: 0
                            val currentThumbY = (firstIndex.toFloat() / scrollable) * thumbRange
                            val newThumbY = (currentThumbY + dragAmount.y).coerceIn(0f, thumbRange)
                            val newFraction = if (thumbRange > 0f) newThumbY / thumbRange else 0f
                            val targetIndex = (newFraction * scrollable).roundToInt().coerceIn(0, total - 1)
                            coroutineScope.launch { state.scrollToItem(targetIndex) }
                        }
                    }
            ) {
                drawRoundRect(
                    color = Color.Gray.copy(alpha = alpha),
                    topLeft = Offset(0f, thumbY),
                    size = Size(size.width, thumbHeightPx),
                    cornerRadius = CornerRadius(size.width / 2)
                )
            }
        }
    }
}

@Composable
private fun IntentField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
