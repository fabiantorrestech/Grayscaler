package io.github.cloudburst.grayscaler

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(store: AppListStore, onBack: () -> Unit) {
    var whitelist by remember { mutableStateOf(store.whitelist) }
    val (apps, setApps) = remember { mutableStateOf(store.apps) }
    var selectedTab by remember { mutableIntStateOf(1) }

    val tabLabels = if (whitelist) listOf("Whitelisted", "All", "Others") else listOf("Blacklisted", "All", "Others")
    val filteredApps = when (selectedTab) {
        0 -> apps.filter { it.second }
        2 -> apps.filter { !it.second }
        else -> apps
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (whitelist) "Whitelist" else "Blacklist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Switch(
                        checked = whitelist,
                        onCheckedChange = { newValue ->
                            store.whitelist = newValue
                            store.invalidate()
                            whitelist = newValue
                            setApps(store.apps)
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
                        text = { Text(label) }
                    )
                }
            }
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
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
                                    bitmap = app.icon.current.toBitmap().asImageBitmap(),
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
                                        store.toggleApp(app.packageName)
                                        store.invalidate()
                                        setApps(store.apps)
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
}
