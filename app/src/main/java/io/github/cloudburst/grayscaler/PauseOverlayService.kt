package io.github.cloudburst.grayscaler

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.cloudburst.grayscaler.ui.theme.GrayscalerTheme

class PauseOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        showOverlay()
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        val prefs = getSharedPreferences("grayscaler_prefs", MODE_PRIVATE)

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@PauseOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PauseOverlayService)
            setContent {
                GrayscalerTheme {
                    var grayscalerEnabled by remember { mutableStateOf(prefs.getBoolean("grayscaler_enabled", true)) }
                    var lastDecision by remember { mutableStateOf(GrayscaleStateManager.lastDecision) }
                    var pauseUntil by remember { mutableStateOf(prefs.getLong("pause_until", 0L)) }
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    var customValue by remember { mutableStateOf("") }
                    var unitExpanded by remember { mutableStateOf(false) }
                    var selectedUnit by remember { mutableStateOf("Minutes") }
                    var confirmingPauseSeconds by remember { mutableStateOf<Long?>(null) }

                    LaunchedEffect(pauseUntil) {
                        while (pauseUntil > System.currentTimeMillis()) {
                            delay(1000L)
                            now = System.currentTimeMillis()
                        }
                    }

                    val applyPauseOrConfirm: (Long) -> Unit = { seconds ->
                        val activePauseUntil = prefs.getLong("pause_until", 0L)
                        if (activePauseUntil > System.currentTimeMillis()) {
                            confirmingPauseSeconds = seconds
                        } else {
                            applyPause(seconds)
                            dismiss()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(300.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { /* consume taps to prevent scrim dismiss */ },
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (confirmingPauseSeconds != null) {
                                    Text("Replace active pause?", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "A pause is already active. Replace it with the new duration?",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                    ) {
                                        TextButton(onClick = { confirmingPauseSeconds = null }) { Text("Cancel") }
                                        Button(onClick = {
                                            applyPause(confirmingPauseSeconds!!)
                                            dismiss()
                                        }) { Text("Replace") }
                                    }
                                    return@Column
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Pause Grayscaler",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        val launchIntent = Intent(this@PauseOverlayService, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        }
                                        startActivity(launchIntent)
                                        dismiss()
                                    }) {
                                        Icon(Icons.Filled.OpenInNew, contentDescription = "Open app")
                                    }
                                    IconButton(onClick = {
                                        grayscalerEnabled = prefs.getBoolean("grayscaler_enabled", true)
                                        pauseUntil = prefs.getLong("pause_until", 0L)
                                        now = System.currentTimeMillis()
                                        lastDecision = GrayscaleStateManager.lastDecision
                                    }) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh state")
                                    }
                                }

                                val isPaused = pauseUntil > now
                                val statusText = when {
                                    !grayscalerEnabled -> "GrayScaler Off"
                                    isPaused -> {
                                        val totalSec = (pauseUntil - now) / 1000
                                        val m = totalSec / 60
                                        val s = totalSec % 60
                                        val countdown = if (m > 0) "${m}m ${s}s" else "${s}s"
                                        "GrayScaler On · Paused · $countdown"
                                    }
                                    lastDecision == GrayscaleStateManager.Decision.ENABLE -> "GrayScaler On · Enabled"
                                    lastDecision == GrayscaleStateManager.Decision.DISABLE -> "GrayScaler On · Disabled"
                                    else -> "GrayScaler On · Not Available"
                                }
                                val statusColor = when {
                                    !grayscalerEnabled -> MaterialTheme.colorScheme.error
                                    isPaused -> MaterialTheme.colorScheme.tertiary
                                    lastDecision == GrayscaleStateManager.Decision.ENABLE -> MaterialTheme.colorScheme.primary
                                    lastDecision == GrayscaleStateManager.Decision.DISABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = statusColor,
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                    Text(
                                        statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Global Toggle",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Switch(
                                        checked = grayscalerEnabled,
                                        onCheckedChange = { enabled ->
                                            grayscalerEnabled = enabled
                                            prefs.edit().putBoolean("grayscaler_enabled", enabled).apply()
                                            GrayscaleStateManager.invalidate(this@PauseOverlayService)
                                            lastDecision = GrayscaleStateManager.lastDecision
                                        }
                                    )
                                }

                                HorizontalDivider()

                                // Preset quick-pause buttons
                                Text(
                                    "Quick pause",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                val row1 = listOf("30s" to 30L, "1m" to 60L, "3m" to 180L, "5m" to 300L)
                                val row2 = listOf("10m" to 600L, "15m" to 900L, "30m" to 1800L)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    row1.forEach { (label, seconds) ->
                                        FilledTonalButton(
                                            onClick = { applyPauseOrConfirm(seconds) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    row2.forEach { (label, seconds) ->
                                        FilledTonalButton(
                                            onClick = { applyPauseOrConfirm(seconds) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                HorizontalDivider()

                                // Custom duration
                                Text(
                                    "Custom duration",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customValue,
                                        onValueChange = { customValue = it.filter { c -> c.isDigit() } },
                                        label = { Text("Amount") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    ExposedDropdownMenuBox(
                                        expanded = unitExpanded,
                                        onExpandedChange = { unitExpanded = it },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = selectedUnit,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("Unit") },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                                            modifier = Modifier.menuAnchor(),
                                            singleLine = true
                                        )
                                        ExposedDropdownMenu(
                                            expanded = unitExpanded,
                                            onDismissRequest = { unitExpanded = false }
                                        ) {
                                            listOf("Seconds", "Minutes", "Hours").forEach { unit ->
                                                DropdownMenuItem(
                                                    text = { Text(unit) },
                                                    onClick = { selectedUnit = unit; unitExpanded = false }
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                ) {
                                    TextButton(onClick = { dismiss() }) { Text("Cancel") }
                                    Button(
                                        onClick = {
                                            val value = customValue.toLongOrNull() ?: return@Button
                                            val seconds = when (selectedUnit) {
                                                "Hours" -> value * 3600L
                                                "Minutes" -> value * 60L
                                                else -> value
                                            }
                                            if (seconds > 0) applyPauseOrConfirm(seconds)
                                        }
                                    ) { Text("Apply") }
                                }
                            }
                        }
                    }
                }
            }
        }

        overlayView = view
        windowManager.addView(view, params)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun applyPause(seconds: Long) {
        val intent = Intent(ScheduleReceiver.ACTION_APPLY_PAUSE).apply {
            setPackage(packageName)
            putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
        }
        sendBroadcast(intent)
    }

    private fun dismiss() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        stopSelf()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
