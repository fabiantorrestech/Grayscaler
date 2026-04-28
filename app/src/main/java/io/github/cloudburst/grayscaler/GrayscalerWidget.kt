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
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
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
import androidx.glance.text.FontFamily
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

internal enum class WidgetLayout {
    MICRO,
    PORTRAIT,
    LANDSCAPE,
}

internal abstract class BaseGrayscalerWidget(
    private val layout: WidgetLayout,
) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent(layout) }
    }
}

internal class GrayscalerMicroWidget : BaseGrayscalerWidget(WidgetLayout.MICRO)

internal class GrayscalerPortraitWidget : BaseGrayscalerWidget(WidgetLayout.PORTRAIT)

internal class GrayscalerLandscapeWidget : BaseGrayscalerWidget(WidgetLayout.LANDSCAPE)

@Composable
private fun WidgetContent(layout: WidgetLayout) {
    val context = LocalContext.current
    val prefs = GrayscalerToggleCoordinator.prefs(context)

    val grayscalerEnabled = prefs.getBoolean(GrayscalerToggleCoordinator.KEY_ENABLED, true)
    val pauseUntil = prefs.getLong("pause_until", 0L)
    val now = System.currentTimeMillis()
    val isPaused = pauseUntil > now
    val statusMonitoring = WidgetSettingsStore.isStatusMonitoring(context)
    val fontFamily: FontFamily? = if (WidgetSettingsStore.isWidgetCustomFont(context)) FontFamily.Monospace else null

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

    val statusText = when {
        !grayscalerEnabled -> "G+: Off"
        isPaused -> "G+: On - ${formatPauseRemaining(pauseUntil, now)}"
        else -> "G+: On"
    }
    val statusColor = when {
        !grayscalerEnabled -> error
        isPaused -> tertiary
        else -> primary
    }
    val microStatusText = if (grayscalerEnabled && !isPaused) "On" else "Off"

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
            action = ScheduleReceiver.ACTION_TOGGLE_ENABLED
        }
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            .cornerRadius(16.dp)
            .padding(12.dp)
    ) {
        when (layout) {
            WidgetLayout.MICRO -> MicroLayout(
                grayscalerEnabled = grayscalerEnabled,
                isPaused = isPaused,
                statusText = microStatusText,
                statusColor = statusColor,
                surfaceVariant = surfaceVariant,
                primaryContainer = primaryContainer,
                outline = outline,
                toggleAction = toggleAction,
                cancelPauseAction = cancelPauseAction,
                fontFamily = fontFamily,
            )
            WidgetLayout.PORTRAIT -> PortraitLayout(
                grayscalerEnabled = grayscalerEnabled,
                isPaused = isPaused,
                statusText = statusText,
                statusColor = statusColor,
                statusMonitoring = statusMonitoring,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
                secondary = secondary,
                onSecondary = onSecondary,
                outlineVariant = outlineVariant,
                surfaceVariant = surfaceVariant,
                primaryContainer = primaryContainer,
                outline = outline,
                toggleAction = toggleAction,
                cancelPauseAction = cancelPauseAction,
                refreshAction = refreshAction,
                openAppAction = openAppAction,
                openPauseAction = openPauseAction,
                context = context,
                fontFamily = fontFamily,
            )
            WidgetLayout.LANDSCAPE -> LandscapeLayout(
                grayscalerEnabled = grayscalerEnabled,
                isPaused = isPaused,
                statusText = statusText,
                statusColor = statusColor,
                statusMonitoring = statusMonitoring,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
                secondary = secondary,
                onSecondary = onSecondary,
                outlineVariant = outlineVariant,
                surfaceVariant = surfaceVariant,
                primaryContainer = primaryContainer,
                outline = outline,
                toggleAction = toggleAction,
                cancelPauseAction = cancelPauseAction,
                refreshAction = refreshAction,
                openAppAction = openAppAction,
                openPauseAction = openPauseAction,
                context = context,
                fontFamily = fontFamily,
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
    outlineVariant: ColorProvider,
    surfaceVariant: ColorProvider,
    primaryContainer: ColorProvider,
    outline: ColorProvider,
    toggleAction: Action,
    cancelPauseAction: Action,
    refreshAction: Action,
    openAppAction: Action,
    openPauseAction: Action,
    context: Context,
    fontFamily: FontFamily?,
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pause Grayscaler+",
                style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily),
                modifier = GlanceModifier.defaultWeight()
            )
            IconButton(refreshAction, R.drawable.ic_widget_refresh, "Refresh", onSurface, surfaceVariant)
            Spacer(GlanceModifier.width(4.dp))
            IconButton(openAppAction, R.drawable.ic_widget_open, "Open app", onSurface, surfaceVariant)
        }

        if (statusMonitoring || isPaused) {
            Spacer(GlanceModifier.height(3.dp))
            StatusRow(
                statusMonitoring = statusMonitoring,
                isPaused = isPaused,
                statusText = statusText,
                statusColor = statusColor,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                cancelPauseAction = cancelPauseAction,
                fontFamily = fontFamily,
            )
        }

        Spacer(GlanceModifier.height(3.dp))
        Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(outlineVariant)) {}
        Spacer(GlanceModifier.height(3.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Global Toggle",
                style = TextStyle(color = onSurfaceVariant, fontSize = 13.sp, fontFamily = fontFamily),
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

        Spacer(GlanceModifier.height(4.dp))
        QuickPauseButtonsSection(secondary, onSecondary, context, fontFamily)
        Spacer(GlanceModifier.height(6.dp))
        CustomDurationSection(secondary, onSecondary, openPauseAction, fontFamily)
    }
}

@Composable
private fun MicroLayout(
    grayscalerEnabled: Boolean,
    isPaused: Boolean,
    statusText: String,
    statusColor: ColorProvider,
    surfaceVariant: ColorProvider,
    primaryContainer: ColorProvider,
    outline: ColorProvider,
    toggleAction: Action,
    cancelPauseAction: Action,
    fontFamily: FontFamily?,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                style = TextStyle(color = statusColor, fontSize = 11.sp, fontFamily = fontFamily),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1
            )
            if (isPaused) {
                Spacer(GlanceModifier.width(4.dp))
                CancelPauseButton(
                    action = cancelPauseAction,
                    contentColor = statusColor,
                    backgroundColor = surfaceVariant,
                    fontFamily = fontFamily,
                )
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(GlanceModifier.defaultWeight())
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
            Spacer(GlanceModifier.defaultWeight())
        }
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
    outlineVariant: ColorProvider,
    surfaceVariant: ColorProvider,
    primaryContainer: ColorProvider,
    outline: ColorProvider,
    toggleAction: Action,
    cancelPauseAction: Action,
    refreshAction: Action,
    openAppAction: Action,
    openPauseAction: Action,
    context: Context,
    fontFamily: FontFamily?,
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pause Grayscaler+",
                style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily),
                modifier = GlanceModifier.defaultWeight()
            )
            if (statusMonitoring || isPaused) {
                Spacer(GlanceModifier.width(8.dp))
                StatusRow(
                    statusMonitoring = statusMonitoring,
                    isPaused = isPaused,
                    statusText = statusText,
                    statusColor = statusColor,
                    onSurface = onSurface,
                    surfaceVariant = surfaceVariant,
                    cancelPauseAction = cancelPauseAction,
                    fontFamily = fontFamily,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = "Toggle",
                style = TextStyle(color = onSurfaceVariant, fontSize = 11.sp, fontFamily = fontFamily)
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

        Row(modifier = GlanceModifier.fillMaxSize()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                QuickPauseButtonsSection(secondary, onSecondary, context, fontFamily)
            }

            Spacer(GlanceModifier.width(12.dp))
            Box(modifier = GlanceModifier.width(1.dp).fillMaxHeight().background(outlineVariant)) {}
            Spacer(GlanceModifier.width(12.dp))

            Column(
                modifier = GlanceModifier.width(140.dp).fillMaxHeight()
            ) {
                CustomDurationSection(secondary, onSecondary, openPauseAction, fontFamily)
            }
        }
    }
}

@Composable
private fun QuickPauseButtonsSection(
    secondary: ColorProvider,
    onSecondary: ColorProvider,
    context: Context,
    fontFamily: FontFamily?,
) {
    Text(
        text = "Quick pause",
        style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = fontFamily)
    )
    Spacer(GlanceModifier.height(3.dp))
    QuickPauseRow(
        listOf("5s" to 5L, "15s" to 15L, "30s" to 30L, "1m" to 60L),
        secondary,
        onSecondary,
        context,
        fontFamily,
    )
    Spacer(GlanceModifier.height(3.dp))
    QuickPauseRow(
        listOf("3m" to 180L, "5m" to 300L, "10m" to 600L, "15m" to 900L),
        secondary,
        onSecondary,
        context,
        fontFamily,
    )
    Spacer(GlanceModifier.height(3.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        PauseButton("30m", 1800L, secondary, onSecondary, context, GlanceModifier.defaultWeight(), fontFamily)
        Spacer(GlanceModifier.width(4.dp))
        PauseButton("1h", 3600L, secondary, onSecondary, context, GlanceModifier.defaultWeight(), fontFamily)
        Spacer(GlanceModifier.width(4.dp))
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(4.dp))
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun CustomDurationSection(
    secondary: ColorProvider,
    onSecondary: ColorProvider,
    openPauseAction: Action,
    fontFamily: FontFamily?,
) {
    Text(
        text = "Custom duration",
        style = TextStyle(color = secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = fontFamily)
    )
    Spacer(GlanceModifier.height(6.dp))
    ActionButton(
        text = "Set custom pause...",
        onClick = openPauseAction,
        modifier = GlanceModifier.fillMaxWidth(),
        backgroundColor = secondary,
        contentColor = onSecondary,
        fontFamily = fontFamily,
    )
}

@Composable
private fun StatusRow(
    statusMonitoring: Boolean,
    isPaused: Boolean,
    statusText: String,
    statusColor: ColorProvider,
    onSurface: ColorProvider,
    surfaceVariant: ColorProvider,
    cancelPauseAction: Action,
    fontFamily: FontFamily?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (statusMonitoring) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(statusColor)
                    .cornerRadius(4.dp)
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = statusText,
                style = TextStyle(color = statusColor, fontSize = 11.sp, fontFamily = fontFamily),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1
            )
        }

        if (isPaused) {
            if (statusMonitoring) {
                Spacer(GlanceModifier.width(4.dp))
            }
            CancelPauseButton(
                action = cancelPauseAction,
                contentColor = onSurface,
                backgroundColor = surfaceVariant,
                fontFamily = fontFamily,
            )
        }
    }
}

@Composable
private fun QuickPauseRow(
    items: List<Pair<String, Long>>,
    secondary: ColorProvider,
    onSecondary: ColorProvider,
    context: Context,
    fontFamily: FontFamily?,
) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        items.forEachIndexed { index, (label, seconds) ->
            if (index > 0) Spacer(GlanceModifier.width(4.dp))
            PauseButton(label, seconds, secondary, onSecondary, context, GlanceModifier.defaultWeight(), fontFamily)
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
    modifier: GlanceModifier,
    fontFamily: FontFamily?,
) {
    ActionButton(
        text = label,
        onClick = actionSendBroadcast(
            Intent(context, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_APPLY_PAUSE
                putExtra(ScheduleReceiver.EXTRA_SECONDS, seconds)
            }
        ),
        modifier = modifier,
        backgroundColor = secondary,
        contentColor = onSecondary,
        fontFamily = fontFamily,
        minHeight = 28.dp,
        horizontalPadding = 6.dp,
    )
}

@Composable
private fun ActionButton(
    text: String,
    onClick: Action,
    modifier: GlanceModifier,
    backgroundColor: ColorProvider,
    contentColor: ColorProvider,
    fontFamily: FontFamily?,
    minHeight: androidx.compose.ui.unit.Dp = 32.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .height(minHeight)
            .background(backgroundColor)
            .cornerRadius(minHeight / 2)
            .clickable(onClick)
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun CancelPauseButton(
    action: Action,
    contentColor: ColorProvider,
    backgroundColor: ColorProvider,
    fontFamily: FontFamily?,
) {
    Box(
        modifier = GlanceModifier
            .size(20.dp)
            .background(backgroundColor)
            .cornerRadius(10.dp)
            .clickable(action),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "×",
            style = TextStyle(
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun IconButton(
    action: Action,
    iconRes: Int,
    contentDescription: String,
    tintColor: ColorProvider,
    bgColor: ColorProvider,
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

private fun formatPauseRemaining(pauseUntil: Long, now: Long): String {
    val remainingSeconds = ((pauseUntil - now + 999L) / 1000L).coerceAtLeast(1L)
    return when {
        remainingSeconds >= 86_400L -> "${remainingSeconds / 86_400L}d"
        remainingSeconds >= 3_600L -> "${remainingSeconds / 3_600L}h"
        remainingSeconds >= 60L -> "${remainingSeconds / 60L}m"
        else -> "${remainingSeconds}s"
    }
}

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
