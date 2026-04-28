package io.github.cloudburst.grayscaler.ui.theme

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import io.github.cloudburst.grayscaler.AppearancePreferences
import io.github.cloudburst.grayscaler.rememberAppearanceSettings
import java.io.File

private val DefaultDarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val DefaultLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

fun loadFontFamily(path: String): FontFamily? {
    if (path.isBlank()) return null
    return try {
        val fontFile = File(path)
        if (!fontFile.exists()) return null
        FontFamily(
            Font(
                file = fontFile,
                weight = FontWeight.Normal,
                style = FontStyle.Normal
            )
        )
    } catch (e: Exception) {
        Log.e("GrayscalerTheme", "Error loading font '$path': ${e.message}")
        null
    }
}

@Composable
fun GrayscalerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = rememberAppearanceSettings(context)
    val darkTheme = when (settings.themeMode) {
        AppearancePreferences.THEME_MODE_LIGHT -> false
        AppearancePreferences.THEME_MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = remember(settings, darkTheme, context) {
        val dynamicSupported = settings.useDynamicTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val baseScheme = when {
            dynamicSupported -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            settings.primaryColor != null || settings.accentColor != null || settings.backgroundColor != null -> {
                createCustomColorScheme(
                    darkTheme = darkTheme,
                    primary = settings.primaryColor ?: if (darkTheme) Purple80 else Purple40,
                    accent = settings.accentColor ?: if (darkTheme) PurpleGrey80 else PurpleGrey40,
                    background = settings.backgroundColor
                )
            }
            darkTheme -> DefaultDarkColorScheme
            else -> DefaultLightColorScheme
        }
        if (darkTheme && settings.oledMode) applyOledOverride(baseScheme) else baseScheme
    }

    val mainFontFamily = remember(settings.useSystemFont, settings.mainFontPath) {
        if (settings.useSystemFont) null else loadFontFamily(settings.mainFontPath)
    }
    val headerFontFamily = remember(settings.useSystemFont, settings.headerFontPath, settings.mainFontPath) {
        if (settings.useSystemFont) null else loadFontFamily(settings.headerFontPath) ?: mainFontFamily
    }
    val subheaderFontFamily = remember(
        settings.useSystemFont,
        settings.subheaderFontPath,
        settings.headerFontPath,
        settings.mainFontPath
    ) {
        if (settings.useSystemFont) null
        else loadFontFamily(settings.subheaderFontPath) ?: headerFontFamily ?: mainFontFamily
    }
    val tertiaryFontFamily = remember(settings.useSystemFont, settings.tertiaryFontPath, settings.mainFontPath) {
        if (settings.useSystemFont) null else loadFontFamily(settings.tertiaryFontPath) ?: mainFontFamily
    }
    val typography = remember(
        headerFontFamily,
        subheaderFontFamily,
        mainFontFamily,
        tertiaryFontFamily,
        settings.displayScalePercent
    ) {
        val baseTypography = themedTypography(headerFontFamily, subheaderFontFamily, mainFontFamily, tertiaryFontFamily)
        scaledTypography(baseTypography, settings.displayScalePercent / 100f)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

internal fun createCustomColorScheme(
    darkTheme: Boolean,
    primary: Color,
    accent: Color,
    background: Color?,
): ColorScheme {
    val tertiary = blend(accent, primary, 0.35f)
    return if (darkTheme) {
        val darkBackground = background ?: Color(0xFF141218)
        val darkSurface = darkBackground
        val darkSurfaceVariant = blend(darkBackground, accent, 0.14f)
        darkColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            primaryContainer = primary.copy(alpha = 0.24f).compositeOver(darkBackground),
            onPrimaryContainer = Color(0xFFF6EEFF),
            secondary = accent,
            onSecondary = contentColorFor(accent),
            secondaryContainer = accent.copy(alpha = 0.22f).compositeOver(darkBackground),
            onSecondaryContainer = Color(0xFFF0F3FF),
            tertiary = tertiary,
            onTertiary = contentColorFor(tertiary),
            tertiaryContainer = tertiary.copy(alpha = 0.2f).compositeOver(darkBackground),
            onTertiaryContainer = Color(0xFFFFF1EC),
            background = darkBackground,
            onBackground = Color(0xFFE8E0E9),
            surface = darkSurface,
            onSurface = Color(0xFFE8E0E9),
            surfaceVariant = darkSurfaceVariant,
            onSurfaceVariant = Color(0xFFCCC2CE),
            outline = Color(0xFF958E99),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    } else {
        val lightBackground = background ?: Color(0xFFFFFBFF)
        val lightSurface = lightBackground
        val lightSurfaceVariant = blend(lightBackground, accent, 0.10f)
        lightColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            primaryContainer = primary.copy(alpha = 0.14f).compositeOver(lightBackground),
            onPrimaryContainer = Color(0xFF240F33),
            secondary = accent,
            onSecondary = contentColorFor(accent),
            secondaryContainer = accent.copy(alpha = 0.14f).compositeOver(lightBackground),
            onSecondaryContainer = Color(0xFF131C2B),
            tertiary = tertiary,
            onTertiary = contentColorFor(tertiary),
            tertiaryContainer = tertiary.copy(alpha = 0.14f).compositeOver(lightBackground),
            onTertiaryContainer = Color(0xFF2D160A),
            background = lightBackground,
            onBackground = Color(0xFF1D1B20),
            surface = lightSurface,
            onSurface = Color(0xFF1D1B20),
            surfaceVariant = lightSurfaceVariant,
            onSurfaceVariant = Color(0xFF49454E),
            outline = Color(0xFF7A757F),
            error = Color(0xFFBA1A1A),
            onError = Color.White
        )
    }
}

internal fun applyOledOverride(scheme: ColorScheme): ColorScheme =
    scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF121212),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color(0xFF080808),
        surfaceContainerHigh = Color(0xFF101010),
        surfaceContainerHighest = Color(0xFF181818),
        surfaceVariant = Color(0xFF121212),
        inverseSurface = Color(0xFFE8E0E9)
    )

private fun contentColorFor(color: Color): Color =
    if (color.luminance() > 0.45f) Color.Black else Color.White

private fun blend(first: Color, second: Color, secondWeight: Float): Color {
    val clampedWeight = secondWeight.coerceIn(0f, 1f)
    val firstWeight = 1f - clampedWeight
    return Color(
        red = first.red * firstWeight + second.red * clampedWeight,
        green = first.green * firstWeight + second.green * clampedWeight,
        blue = first.blue * firstWeight + second.blue * clampedWeight,
        alpha = first.alpha * firstWeight + second.alpha * clampedWeight
    )
}
