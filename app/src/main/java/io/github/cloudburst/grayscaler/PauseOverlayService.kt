package io.github.cloudburst.grayscaler

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            @Suppress("DEPRECATION")
            flags = (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@PauseOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PauseOverlayService)
            setContent {
                GrayscalerTheme {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp).width(280.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Pause Grayscaler", style = MaterialTheme.typography.titleMedium)

                            // Preset buttons
                            val presets = listOf(3, 5, 10, 15, 30)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                presets.forEach { minutes ->
                                    OutlinedButton(
                                        onClick = { applyPause(minutes); dismiss() },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("${minutes}m", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            // Custom duration
                            var customValue by remember { mutableStateOf("") }
                            var unitExpanded by remember { mutableStateOf(false) }
                            var selectedUnit by remember { mutableStateOf("Minutes") }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customValue,
                                    onValueChange = { customValue = it.filter { c -> c.isDigit() } },
                                    label = { Text("Duration") },
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
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                                        modifier = Modifier.menuAnchor(),
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = unitExpanded,
                                        onDismissRequest = { unitExpanded = false }
                                    ) {
                                        listOf("Minutes", "Hours").forEach { unit ->
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
                                OutlinedButton(onClick = { dismiss() }) { Text("Cancel") }
                                Button(
                                    onClick = {
                                        val value = customValue.toIntOrNull() ?: return@Button
                                        val minutes = if (selectedUnit == "Hours") value * 60 else value
                                        if (minutes > 0) { applyPause(minutes); dismiss() }
                                    }
                                ) { Text("Apply") }
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

    private fun applyPause(minutes: Int) {
        val intent = Intent(ScheduleReceiver.ACTION_APPLY_PAUSE).apply {
            setPackage(packageName)
            putExtra(ScheduleReceiver.EXTRA_MINUTES, minutes)
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
