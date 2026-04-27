package io.github.cloudburst.grayscaler

import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(store: AppListStore, onBack: () -> Unit, scheduleId: String? = null) {
    val context = LocalContext.current

    // When scheduleId is provided, build a local AppListStore pre-loaded from the schedule's
    // profile so all existing toggle logic works unchanged. On back, flush state to ScheduleStore.
    data class ScheduleContext(val ss: ScheduleStore, val sched: Schedule, val localStore: AppListStore)
    val scheduleContext: ScheduleContext? = remember(scheduleId) {
        if (scheduleId == null) return@remember null
        val ss = ScheduleStore(context).also { it.load() }
        val sched = ss.schedules.find { it.id == scheduleId } ?: DraftScheduleSession.get(scheduleId) ?: return@remember null
        val localStore = AppListStore(context).also { a ->
            a.whitelist = sched.profileMode != "blacklist"
            a.whitelistedApps = sched.profileWhitelist
            a.blacklistedApps = sched.profileBlacklist
        }
        ScheduleContext(ss, sched, localStore)
    }

    val effectiveStore = scheduleContext?.localStore ?: store

    fun persistState() {
        scheduleContext?.let { (ss, sched, localStore) ->
            val updatedMode = if (localStore.whitelist) "whitelist" else "blacklist"
            val updated = sched.copy(
                profileMode = updatedMode,
                profileWhitelist = localStore.whitelistedApps,
                profileBlacklist = localStore.blacklistedApps
            )
            if (ss.schedules.any { it.id == sched.id }) ss.update(updated) else DraftScheduleSession.put(updated)
        } ?: effectiveStore.save()
    }

    fun refreshCurrentApp() {
        GrayscaleStateManager.invalidate(context)
        val currentClassName = (context as? Activity)?.javaClass?.name ?: context.javaClass.name
        val decision = GrayscaleStateManager.evaluateCurrentApp(context, currentClassName)
        GrayscaleStateManager.applyToSystem(context, decision)
    }

    val handleBack: () -> Unit = {
        persistState()
        refreshCurrentApp()
        onBack()
    }

    BackHandler(onBack = handleBack)

    var whitelist by remember { mutableStateOf(effectiveStore.whitelist) }
    val (apps, setApps) = remember { mutableStateOf<List<Pair<CatalogApp, Boolean>>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(1) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun updateAppSnapshot() {
        setApps(AppCatalogRepository.snapshotApps(context, effectiveStore.toggledApps))
    }

    fun persistAndRefresh() {
        persistState()
        updateAppSnapshot()
        refreshCurrentApp()
    }

    suspend fun refreshApps() {
        appsLoading = true
        val loadedApps = withContext(Dispatchers.Default) {
            AppCatalogRepository.snapshotApps(context, effectiveStore.toggledApps)
        }
        setApps(loadedApps)
        appsLoading = false
    }

    LaunchedEffect(effectiveStore) {
        refreshApps()
    }

    val tabLabels = if (whitelist) listOf("Whitelisted", "All", "Others") else listOf("Blacklisted", "All", "Others")
    val filteredApps = when (selectedTab) {
        0 -> apps.filter { it.second }
        2 -> apps.filter { !it.second }
        else -> apps
    }.filter { (app, _) ->
        searchQuery.isBlank() ||
        app.appName.contains(searchQuery, ignoreCase = true) ||
        app.packageName.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (whitelist) "Whitelist" else "Blacklist") },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear list")
                    }
                    Switch(
                        checked = whitelist,
                        onCheckedChange = { newValue ->
                            effectiveStore.whitelist = newValue
                            effectiveStore.invalidate()
                            whitelist = newValue
                            persistAndRefresh()
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        selectedContentColor = MaterialTheme.colorScheme.secondary,
                        unselectedContentColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f),
                        text = { Text(label) }
                    )
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                if (appsLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredApps.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) "No apps" else "No apps match \"$searchQuery\"",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 20.dp)
                ) {
                    items(filteredApps.size) { i ->
                        val (app, enabled) = filteredApps[i]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )
                                Column(
                                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                                ) {
                                    Text(text = app.appName)
                                    Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        effectiveStore.toggleApp(app.packageName)
                                        effectiveStore.invalidate()
                                        persistAndRefresh()
                                    }
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear list?") },
            text = { Text("Remove all apps from the ${if (whitelist) "whitelist" else "blacklist"}?") },
            confirmButton = {
                TextButton(onClick = {
                    if (whitelist) effectiveStore.whitelistedApps = emptySet()
                    else effectiveStore.blacklistedApps = emptySet()
                    effectiveStore.invalidate()
                    persistAndRefresh()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
