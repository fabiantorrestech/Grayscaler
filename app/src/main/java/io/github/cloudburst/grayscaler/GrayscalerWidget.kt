package io.github.cloudburst.grayscaler

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.cloudburst.grayscaler.ui.theme.Pink40
import io.github.cloudburst.grayscaler.ui.theme.Pink80
import io.github.cloudburst.grayscaler.ui.theme.Purple40
import io.github.cloudburst.grayscaler.ui.theme.Purple80
import io.github.cloudburst.grayscaler.ui.theme.PurpleGrey40
import io.github.cloudburst.grayscaler.ui.theme.PurpleGrey80
import io.github.cloudburst.grayscaler.ui.theme.applyOledOverride
import io.github.cloudburst.grayscaler.ui.theme.createCustomColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GrayscalerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val context = LocalContext.current
        val prefs = GrayscalerToggleCoordinator.prefs(context)

        val grayscalerEnabled = prefs.getBoolean(GrayscalerToggleCoordinator.KEY_ENABLED, true)
        val pauseUntil = prefs.getLong("pause_until", 0L)
        val now = System.currentTimeMillis()
        val isPaused = pauseUntil > now
        val lastDecision = GrayscaleStateManager.lastDecision
        val liveCountdown = WidgetSettingsStore.isLiveCountdown(context)
        val statusMonitoring = WidgetSettingsStore.isStatusMonitoring(context)

        val size = LocalSize.current
        val isLandscape = size.width >= 360.dp && size.width > size.height + 20.dp

        val scheme = resolveScheme(context)
        val bg = ColorProvider(scheme.background)
        val onSurface = ColorProvider(scheme.onSurface)
        val onSurfaceVariant = ColorProvider(scheme.onSurfaceVariant)
        val secondary = ColorProvider(scheme.secondary)
        val onSecondary = ColorProvider(scheme.onSecondary)
        val tertiary = ColorProvider(scheme.tertiary)
        val error = ColorProvider(scheme.error)
        val primary = ColorProvider(scheme.primary)
        val outlineVariant = ColorProvider(scheme.outlineVariant)
        val surfaceVariant = ColorProvider(scheme.surfaceVariant)
        val primaryContainer = ColorProvider(scheme.primaryContainer)
        val outline = ColorProvider(scheme.outline)

        val statusText: String
        val statusColor: ColorProvider
        when {
            !grayscalerEnabled -> {
                statusText = "Grayscaler+ Off"
                statusColor = error
            }
            isPaused -> {
                statusText = if (liveCountdown) {
                    val totalSec = (pauseUntil - now) / 1000
                    val m = totalSec / 60; val s = totalSec % 60
                    "Paused · " + if (m > 0) "${m}m ${s}s" else "${s}s"
                } else {
                    "Paused until ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(pauseUntil))}"
                }
                statusColor = tertiary
            }
            lastDecision == GrayscaleStateManager.Decision.ENABLE -> {
                statusText = "On · Enabled"
                statusColor = primary
            }
            lastDecision == GrayscaleStateManager.Decision.DISABLE -> {
                statusText = "On · Disabled"
                statusColor = onSurfaceVariant
            }
            else -> {
                statusText = "On · Unavailable"
                statusColor = onSurfaceVariant
            }
        }

        val refreshAction = actionSendBroadcast(
            Intent(context, GrayscalerWidgetReceiver::class.java).apply {
                action = GrayscalerWidgetReceiver.ACTION_WIDGET_STATE_CHANGED
            }
        )
        val openAppAction = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        val openPauseAction = actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "pause")
            }
        )
        val cancelPauseAction = actionSendBroadcast(
            Intent(context, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_PAUSE_END
            }
        )
        val toggleAction = actionSendBroadcast(
            Intent(context, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_SET_ENABLED
                putExtra(ScheduleReceiver.EXTRA_ENABLED, !grayscalerEnabled)
            }
        )

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .cornerRadius(16.dp)
                .padding(12.dp)
        ) {
            if (isLandscape) {
                LandscapeLayout(
                    grayscalerEnabled, isPaused, statusText, statusColor,
                    statusMonitoring, onSurface, onSurfaceVariant, secondary, onSecondary,
                    tertiary, outlineVariant, surfaceVariant, primaryContainer, outline,
                    toggleAction, cancelPauseAction, refreshAction, openAppAction,
                    openPauseAction, context
                )
            } else {
                PortraitLayout(
                    grayscalerEnabled, isPaused, statusText, statusColor,
                    statusMonitoring, onSurface, onSurfaceVariant, secondary, onSecondary,
                    tertiary, outlineVariant, surfaceVariant, primaryContainer, outline,
                    toggleAction, cancelPauseAction, refreshAction, openAppAction,
                    openPauseAction, context
                )
            }
        }
    }

    @Composable
    private fun PortraitLayout(
        grayscalerEnabled: Boolean,
        isPaused: Boolean,
        statusText: String,
        statusColor: ColorProvider,
        statusMonitoring: Boolean,
        onSurface: ColorProvider,
        onSurfaceVariant: ColorProvider,
        secondary: ColorProvider,
        onSecondary: ColorProvider,
        tertiary: ColorProvider,
        outlineVariant: ColorProvider,
        surfaceVariant: ColorProvider,
        primaryContainer: ColorProvider,
        outline: ColorProvider,
        toggleAction: androidx.glance.action.Action,
        cancelPauseAction: androidx.glance.action.Action,
        refreshAction: androidx.glance.action.Action,
        openAppAction: androidx.glance.action.Action,
        openPauseAction: androidx.glance.action.Action,
        context: Context
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pause Grayscaler+",
                    style = TextStyle(
                        color = onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                IconButton(refreshAction, R.drawable.ic_widget_refresh, "Refresh", onSurface, surfaceVariant)
                Spacer(GlanceModifier.width(4.dp))
                IconButton(openAppAction, R.drawable.ic_widget_open, "Open app", onSurface, surfaceVariant)
            }

            if (statusMonitoring) {
                Spacer(GlanceModifier.height(6.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .size(8.dp)
                            .background(statusColor)
                            .cornerRadius(4.dp)
                    ) {}
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = TextStyle(color = statusColor, fontSize = 11.sp),
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1
                    )
                    if (isPaused) {
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            text = "Cancel",
                            style = TextStyle(color = tertiary, fontSize = 11.sp),
                            modifier = GlanceModifier.clickable(cancelPauseAction)
                        )
                    }
                }
            }

            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(outlineVariant)) {}
            Spacer(GlanceModifier.height(8.dp))

            // Global toggle
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Global Toggle",
                    style = TextStyle(color = onSurfaceVariant, fontSize = 13.sp),
                    modifier = GlanceModifier.defaultWeight()
                )
                Switch(
                    checked = grayscalerEnabled,
                    onCheckedChange = toggleAction,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ColorProvider(androidx.compose.ui.graphics.Color.White),
                        checkedTrackColor = primaryContainer,
                        uncheckedThumbColor = outline,
                        uncheckedTrackColor = surfaceVariant
                    )
                )
            }

            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(outlineVariant)) {}
            Spacer(GlanceModifier.height(8.dp))

            // Quick pause section
            Text(
                text = "Quick pause",
                style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(GlanceModifier.height(6.dp))
            QuickPauseRow(
                listOf("5s" to 5L, "15s" to 15L, "30s" to 30L, "1m" to 60L),
                secondary, onSecondary, context
            )
            Spacer(GlanceModifier.height(4.dp))
            QuickPauseRow(
                listOf("3m" to 180L, "5m" to 300L, "10m" to 600L, "15m" to 900L),
                secondary, onSecondary, context
            )
            Spacer(GlanceModifier.height(4.dp))
            // Third row: 30m, 1h with empty fillers to match button width
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                PauseButton("30m", 1800L, secondary, onSecondary, context, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(4.dp))
                PauseButton("1h", 3600L, secondary, onSecondary, context, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(4.dp))
                Spacer(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(4.dp))
                Spacer(GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.height(6.dp))

            // Custom pause shortcut
            Button(
                text = "Set custom pause...",
                onClick = openPauseAction,
                modifier = GlanceModifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = secondary,
                    contentColor = onSecondary
                )
            )
        }
    }

    @Composable
    private fun LandscapeLayout(
        grayscalerEnabled: Boolean,
        isPaused: Boolean,
        statusText: String,
        statusColor: ColorProvider,
        statusMonitoring: Boolean,
        onSurface: ColorProvider,
        onSurfaceVariant: ColorProvider,
        secondary: ColorProvider,
        onSecondary: ColorProvider,
        tertiary: ColorProvider,
        outlineVariant: ColorProvider,
        surfaceVariant: ColorProvider,
        primaryContainer: ColorProvider,
        outline: ColorProvider,
        toggleAction: androidx.glance.action.Action,
        cancelPauseAction: androidx.glance.action.Action,
        refreshAction: androidx.glance.action.Action,
        openAppAction: androidx.glance.action.Action,
        openPauseAction: androidx.glance.action.Action,
        context: Context
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header: title | status | toggle + icons
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pause Grayscaler+",
                    style = TextStyle(
                        color = onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (statusMonitoring) {
                    Spacer(GlanceModifier.width(8.dp))
                    Box(
                        modifier = GlanceModifier
                            .size(8.dp)
                            .background(statusColor)
                            .cornerRadius(4.dp)
                    ) {}
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = TextStyle(color = statusColor, fontSize = 11.sp),
                        maxLines = 1
                    )
                    if (isPaused) {
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            text = "Cancel",
                            style = TextStyle(color = tertiary, fontSize = 11.sp),
                            modifier = GlanceModifier.clickable(cancelPauseAction)
                        )
                    }
                }
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = "Toggle",
                    style = TextStyle(color = onSurfaceVariant, fontSize = 11.sp)
                )
                Spacer(GlanceModifier.width(4.dp))
                Switch(
                    checked = grayscalerEnabled,
                    onCheckedChange = toggleAction,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ColorProvider(androidx.compose.ui.graphics.Color.White),
                        checkedTrackColor = primaryContainer,
                        uncheckedThumbColor = outline,
                        uncheckedTrackColor = surfaceVariant
                    )
                )
                Spacer(GlanceModifier.width(4.dp))
                IconButton(refreshAction, R.drawable.ic_widget_refresh, "Refresh", onSurface, surfaceVariant)
                Spacer(GlanceModifier.width(4.dp))
                IconButton(openAppAction, R.drawable.ic_widget_open, "Open app", onSurface, surfaceVariant)
            }

            Spacer(GlanceModifier.height(8.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(outlineVariant)) {}
            Spacer(GlanceModifier.height(8.dp))

            // Body: quick pause (left) | vertical divider | custom (right)
            Row(modifier = GlanceModifier.fillMaxSize()) {
                // Left: pause buttons
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "Quick pause",
                        style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    QuickPauseRow(
                        listOf("5s" to 5L, "15s" to 15L, "30s" to 30L, "1m" to 60L),
                        secondary, onSecondary, context
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    QuickPauseRow(
                        listOf("3m" to 180L, "5m" to 300L, "10m" to 600L, "15m" to 900L),
                        secondary, onSecondary, context
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        PauseButton("30m", 1800L, secondary, onSecondary, context, GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(4.dp))
                        PauseButton("1h", 3600L, secondary, onSecondary, context, GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(4.dp))
                        Spacer(GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(4.dp))
                        Spacer(GlanceModifier.defaultWeight())
                    }
                }

                Spacer(GlanceModifier.width(12.dp))
                Box(modifier = GlanceModifier.width(1.dp).fillMaxHeight().background(outlineVariant)) {}
                Spacer(GlanceModifier.width(12.dp))

                // Right: custom pause
                Column(
                    modifier = GlanceModifier.width(140.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom duration",
                        style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    Button(
                        text = "Set custom pause...",
                        onClick = openPauseAction,
                        modifier = GlanceModifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = secondary,
                            contentColor = onSecondary
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun QuickPauseRow(
        items: List<Pair<String, Long>>,
        secondary: ColorProvider,
        onSecondary: ColorProvider,
        context: Context
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            items.forEachIndexed { index, (label, seconds) ->
                if (index > 0) Spacer(GlanceModifier.width(4.dp))
                PauseButton(label, seconds, secondary, onSecondary, context, GlanceModifier.defaultWeight())
            }
        }
    }

    @Composable
    private fun PauseButton(
        label: String,
        seconds: Long,
        secondary: ColorProvider,
        onSecondary: ColorProvider,
        context: Context,
        modifier: GlanceModifier
    ) {
        Button(
            text = label,
            onClick = actionSendBroadcast(
                Intent(context, ScheduleReceiver::class.java).apply {
                    action = ScheduleReceiver.ACTION_APPLY_PAUSE
                    putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
                }
            ),
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = secondary,
                contentColor = onSecondary
            ),
            style = TextStyle(fontSize = 12.sp)
        )
    }

    @Composable
    private fun IconButton(
        action: androidx.glance.action.Action,
        iconRes: Int,
        contentDescription: String,
        tintColor: ColorProvider,
        bgColor: ColorProvider
    ) {
        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .background(bgColor)
                .cornerRadius(16.dp)
                .clickable(action),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(tintColor)
            )
        }
    }

    companion object {
        private fun resolveScheme(context: Context): androidx.compose.material3.ColorScheme {
            val settings = AppearancePreferences.load(context)
            val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val darkTheme = when (settings.themeMode) {
                AppearancePreferences.THEME_MODE_DARK -> true
                AppearancePreferences.THEME_MODE_LIGHT -> false
                else -> isNight
            }
            val defaultDark = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)
            val defaultLight = lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)
            val dynamicSupported = settings.useDynamicTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val base = when {
                dynamicSupported ->
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                settings.primaryColor != null || settings.accentColor != null || settings.backgroundColor != null ->
                    createCustomColorScheme(
                        darkTheme = darkTheme,
                        primary = settings.primaryColor ?: if (darkTheme) Purple80 else Purple40,
                        accent = settings.accentColor ?: if (darkTheme) PurpleGrey80 else PurpleGrey40,
                        background = settings.backgroundColor
                    )
                darkTheme -> defaultDark
                else -> defaultLight
            }
            return if (darkTheme && settings.oledMode) applyOledOverride(base) else base
        }
    }
}
