package io.github.cloudburst.grayscaler

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    lateinit var store: AppListStore
    lateinit var webShortcutStore: WebShortcutStore

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

        webShortcutStore = WebShortcutStore(this)
        webShortcutStore.load()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    ScheduleReceiver.CHANNEL_ID_STATIC,
                    "Grayscaler+ Paused",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    ScheduleReceiver.CHANNEL_ID_COUNTDOWN,
                    "Grayscaler+ Countdown",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        thread {
            store.preloadApps()
            val launcherEntries = loadCCLauncherShortcuts()
            webShortcutStore.mergeLauncherEntries(launcherEntries)
        }

        setContent {
            GrayscalerTheme {
                AppNavigation(store, webShortcutStore)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        store.save()
        webShortcutStore.save()
        GrayscaleStateManager.invalidate(this)
    }

    override fun onResume() {
        super.onResume()
        val decision = GrayscaleStateManager.evaluateCurrentApp(this, javaClass.name)
        GrayscaleStateManager.applyToSystem(this, decision)
    }

    private fun loadCCLauncherShortcuts(): List<WebShortcutEntry> {
        return try {
            val uri = android.net.Uri.parse("content://app.cclauncher.shortcuts/pinned")
            val cursor = contentResolver.query(uri, null, null, null, null)
                ?: return emptyList()
            val entries = mutableListOf<WebShortcutEntry>()
            cursor.use {
                val labelIdx = it.getColumnIndex("label")
                val urlIdx = it.getColumnIndex("url")
                val browserPkgIdx = it.getColumnIndex("browser_package")
                val shortcutIdIdx = it.getColumnIndex("shortcut_id")
                if (labelIdx < 0 || urlIdx < 0) return@use
                while (it.moveToNext()) {
                    val shortcutId = if (shortcutIdIdx >= 0) it.getString(shortcutIdIdx) else ""
                    val browserPkg = if (browserPkgIdx >= 0) it.getString(browserPkgIdx) else null
                    entries.add(
                        WebShortcutEntry(
                            id = "cclauncher_${shortcutId}_${browserPkg.orEmpty()}",
                            label = it.getString(labelIdx),
                            url = it.getString(urlIdx),
                            browserPackage = browserPkg,
                            isManual = false
                        )
                    )
                }
            }
            entries
        } catch (_: Exception) {
            emptyList()
        }
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
private fun AppNavigation(store: AppListStore, webShortcutStore: WebShortcutStore) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        }
    ) {
        composable("main") {
            MainScreen(
                store = store,
                onOpenSchedules = { navController.navigate("schedules") },
                onOpenOverlayIgnore = { navController.navigate("overlay_ignore") },
                onOpenPermissions = { navController.navigate("permissions") },
                onOpenAppearance = { navController.navigate("appearance") },
                onOpenPerAppViews = { navController.navigate("per_app_views") },
                onOpenWhitelist = { navController.navigate("whitelist") },
                onOpenWebShortcuts = { navController.navigate("web_shortcuts") },
                onOpenBackupRestore = { navController.navigate("backup_restore") },
                onOpenPause = { navController.navigate("pause") },
                onOpenDeveloper = { navController.navigate("developer") }
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
                onSaved = { navController.popBackStack() },
                onOpenAppList = null
            )
        }
        composable("edit_schedule") {
            val schedule = navController.previousBackStackEntry
                ?.savedStateHandle?.get<Schedule>("edit_schedule")
            AddEditScheduleScreen(
                existing = schedule,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onOpenAppList = { scheduleId -> navController.navigate("schedule_whitelist/$scheduleId") }
            )
        }
        composable("schedule_whitelist/{scheduleId}") { backStackEntry ->
            val scheduleId = backStackEntry.arguments?.getString("scheduleId") ?: return@composable
            WhitelistScreen(
                store = store,
                onBack = { navController.popBackStack() },
                scheduleId = scheduleId
            )
        }
        composable("overlay_ignore") {
            OverlayIgnoreScreen(onBack = { navController.popBackStack() })
        }
        composable("permissions") {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
        composable("appearance") {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("per_app_views") {
            PerAppViewsSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("whitelist") {
            WhitelistScreen(store = store, onBack = { navController.popBackStack() })
        }
        composable("web_shortcuts") {
            WebShortcutScreen(store = webShortcutStore, onBack = { navController.popBackStack() })
        }
        composable("backup_restore") {
            BackupRestoreScreen(onBack = { navController.popBackStack() })
        }
        composable("pause") {
            PauseScreen(
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate("permissions") }
            )
        }
        composable("developer") {
            DeveloperSettingsScreen(onBack = { navController.popBackStack() })
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
    onOpenAppearance: () -> Unit,
    onOpenPerAppViews: () -> Unit,
    onOpenWhitelist: () -> Unit,
    onOpenWebShortcuts: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenPause: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE) }

    var grayscalerEnabled by remember { mutableStateOf(prefs.getBoolean("grayscaler_enabled", true)) }
    var showHelp by remember { mutableStateOf(false) }
    var showNotifPrompt by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notif_permission_prompted", false)
        )
    }
    var appSwitcherMode by remember { mutableStateOf(prefs.getString("app_switcher_mode", "ignore") ?: "ignore") }
    var lockscreenMode by remember { mutableStateOf(prefs.getString("lockscreen_mode", "ignore") ?: "ignore") }
    var powerMenuMode by remember { mutableStateOf(prefs.getString("power_menu_mode", "disable") ?: "disable") }
    var inlineReplyMode by remember { mutableStateOf(prefs.getString("inline_reply_mode", "ignore") ?: "ignore") }

    var hasWriteSecure by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val accessibilityComponent = remember { ComponentName(context, MainService::class.java) }
    var accessibilityEnabled by remember {
        mutableStateOf(checkAccessibilityServiceEnabled(context, accessibilityComponent))
    }
    var showAdbDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
                hasWriteSecure = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
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
    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Grayscaler+",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = MaterialTheme.typography.headlineSmall.fontSize * 1.25f
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(Unit) {
                                    detectTapGestures(onLongPress = { onOpenDeveloper() })
                                }
                        )
                        Switch(
                            checked = grayscalerEnabled,
                            onCheckedChange = { enabled ->
                                grayscalerEnabled = enabled
                                prefs.edit().putBoolean("grayscaler_enabled", enabled).apply()
                                GrayscaleStateManager.invalidate(context)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showHelp = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "Shortcut setup help")
                        }
                        IconButton(onClick = onOpenSchedules) {
                            Icon(Icons.Filled.DateRange, contentDescription = "Schedules")
                        }
                        IconButton(onClick = onOpenAppearance) {
                            Icon(Icons.Filled.Palette, contentDescription = "Appearance")
                        }
                        IconButton(onClick = onOpenPause) {
                            Icon(Icons.Filled.PauseCircle, contentDescription = "Pause")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (!hasWriteSecure) {
                PermissionBanner(
                    message = "Secure settings permission missing — grayscale cannot be applied",
                    actionLabel = "Permissions",
                    onAction = onOpenPermissions,
                    onAdb = { showAdbDialog = true }
                )
            }
            if (!accessibilityEnabled) {
                PermissionBanner(
                    message = "Accessibility service disabled — grayscale won't respond to app changes",
                    actionLabel = "Enable",
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    }
                )
            }

            HomeSectionHeader(
                title = "Quick Controls",
                subtitle = "Core rules and app-specific grayscale behavior."
            )
            HomeGroupCard {
                HomeNavigationRow(
                    title = "Global App List",
                    subtitle = "Whitelist / Blacklist settings",
                    onClick = onOpenWhitelist
                )
                HorizontalDivider()
                HomeNavigationRow(
                    title = "Per-App Views",
                    subtitle = "Per-app auto-disable settings",
                    onClick = onOpenPerAppViews
                )
            }

            HomeSectionHeader(
                title = "Automation",
                subtitle = "How Grayscaler+ behaves during system and app events."
            )
            HomeGroupCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "OS Events",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    HomeModeSetting(
                        title = "App Switcher",
                        subtitle = "Control grayscale behavior while recent apps are visible.",
                        current = appSwitcherMode,
                        onSelect = { mode ->
                            appSwitcherMode = mode
                            prefs.edit().putString("app_switcher_mode", mode).apply()
                        }
                    )
                    HorizontalDivider()
                    HomeModeSetting(
                        title = "Lock Screen",
                        subtitle = "Choose whether grayscale stays active while the device is locked.",
                        current = lockscreenMode,
                        onSelect = { mode ->
                            lockscreenMode = mode
                            prefs.edit().putString("lockscreen_mode", mode).apply()
                        }
                    )
                    HorizontalDivider()
                    HomeModeSetting(
                        title = "Notification Reply",
                        subtitle = "Applies while typing a reply directly inside a notification.",
                        current = inlineReplyMode,
                        onSelect = { mode ->
                            inlineReplyMode = mode
                            prefs.edit().putString("inline_reply_mode", mode).apply()
                        }
                    )
                    HorizontalDivider()
                    HomeModeSetting(
                        title = "Power Menu",
                        subtitle = "Disable recommended — keeps emergency controls visible.",
                        current = powerMenuMode,
                        onSelect = { mode ->
                            powerMenuMode = mode
                            prefs.edit().putString("power_menu_mode", mode).apply()
                        }
                    )
                }
            }

            HomeGroupCard {
                HomeNavigationRow(
                    title = "Ignored Overlays",
                    subtitle = "Apps to ignore for overlay detection",
                    onClick = onOpenOverlayIgnore
                )
                HorizontalDivider()
                HomeNavigationRow(
                    title = "Web Shortcuts",
                    subtitle = "PWAs and browser shortcuts",
                    onClick = onOpenWebShortcuts
                )
            }

            HomeSectionHeader(
                title = "Utilities",
                subtitle = "Permissions, exports, and maintenance tools."
            )
            HomeGroupCard {
                HomeNavigationRow(
                    title = "Permissions",
                    subtitle = "Grant required app permissions",
                    onClick = onOpenPermissions
                )
                HorizontalDivider()
                HomeNavigationRow(
                    title = "Backup & Restore",
                    subtitle = "Export or import all saved settings",
                    onClick = onOpenBackupRestore
                )
            }
        }
    }

    if (showNotifPrompt) {
        AlertDialog(
            onDismissRequest = {
                showNotifPrompt = false
                prefs.edit().putBoolean("notif_permission_prompted", true).apply()
            },
            title = { Text("Enable Notifications") },
            text = {
                Text("Grayscaler+ can show a persistent notification while a pause is active so you can track when grayscale will re-enable. Grant notification permission in Permissions.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotifPrompt = false
                    prefs.edit().putBoolean("notif_permission_prompted", true).apply()
                    onOpenPermissions()
                }) { Text("Open Permissions") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotifPrompt = false
                    prefs.edit().putBoolean("notif_permission_prompted", true).apply()
                }) { Text("Not now") }
            }
        )
    }

    if (showAdbDialog) {
        AlertDialog(
            onDismissRequest = { showAdbDialog = false },
            title = { Text("Grant via ADB") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("With ADB connected, run:", style = MaterialTheme.typography.bodySmall)
                    AdbCommand("adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    AdbCommand("adb shell pm grant ${context.packageName} android.permission.PACKAGE_USAGE_STATS")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        AdbCommand("adb shell pm grant ${context.packageName} android.permission.QUERY_ALL_PACKAGES")
                    AdbCommand("adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdbDialog = false }) { Text("Got it") }
            }
        )
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

@Composable
private fun PermissionBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onAdb: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (onAdb != null) {
                TextButton(
                    onClick = onAdb,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                ) { Text("ADB") }
            }
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) { Text(actionLabel) }
        }
    }
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(content = content)
    }
}

@Composable
private fun HomeNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MainScreenActionButton(onClick = onClick)
    }
}

@Composable
private fun HomeModeSetting(
    title: String,
    subtitle: String,
    current: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SystemUiModeToggle(current) { mode -> onSelect(mode) }
    }
}

@Composable
private fun AdbCommand(cmd: String) {
    Text(cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

@Composable
private fun MainScreenActionButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
    ) {
        Text("Open")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemUiModeToggle(current: String, onSelect: (String) -> Unit) {
    SystemUiModeToggle(current = current, enabled = true, onSelect = onSelect)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemUiModeToggle(current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val options = listOf("ignore" to "Ignore", "enable" to "Enable", "disable" to "Disable")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = current == value,
                enabled = enabled,
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
