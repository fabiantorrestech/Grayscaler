package io.github.cloudburst.grayscaler

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import java.io.File

data class AppearanceSettings(
    val themeMode: String = AppearancePreferences.THEME_MODE_SYSTEM,
    val useDynamicTheme: Boolean = true,
    val oledMode: Boolean = false,
    val displayScalePercent: Int = 100,
    val useSystemFont: Boolean = true,
    val primaryColor: Color? = null,
    val accentColor: Color? = null,
    val backgroundColor: Color? = null,
    val mainFontPath: String = "",
    val mainFontName: String = "",
    val headerFontPath: String = "",
    val headerFontName: String = "",
    val subheaderFontPath: String = "",
    val subheaderFontName: String = "",
    val tertiaryFontPath: String = "",
    val tertiaryFontName: String = "",
)

object AppearancePreferences {
    const val PREFS_NAME = "grayscaler_prefs"

    const val KEY_THEME_MODE = "appearance_theme_mode"
    const val KEY_USE_DYNAMIC_THEME = "appearance_use_dynamic_theme"
    const val KEY_OLED_MODE = "appearance_oled_mode"
    const val KEY_DISPLAY_SCALE_PERCENT = "appearance_display_scale_percent"
    const val KEY_USE_SYSTEM_FONT = "appearance_use_system_font"
    const val KEY_PRIMARY_COLOR = "appearance_primary_color"
    const val KEY_ACCENT_COLOR = "appearance_accent_color"
    const val KEY_BACKGROUND_COLOR = "appearance_background_color"
    const val KEY_MAIN_FONT_PATH = "appearance_main_font_path"
    const val KEY_MAIN_FONT_NAME = "appearance_main_font_name"
    const val KEY_HEADER_FONT_PATH = "appearance_header_font_path"
    const val KEY_HEADER_FONT_NAME = "appearance_header_font_name"
    const val KEY_SUBHEADER_FONT_PATH = "appearance_subheader_font_path"
    const val KEY_SUBHEADER_FONT_NAME = "appearance_subheader_font_name"
    const val KEY_TERTIARY_FONT_PATH = "appearance_tertiary_font_path"
    const val KEY_TERTIARY_FONT_NAME = "appearance_tertiary_font_name"

    const val THEME_MODE_SYSTEM = "system"
    const val THEME_MODE_LIGHT = "light"
    const val THEME_MODE_DARK = "dark"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AppearanceSettings {
        val prefs = prefs(context)
        return AppearanceSettings(
            themeMode = prefs.getString(KEY_THEME_MODE, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM,
            useDynamicTheme = prefs.getBoolean(KEY_USE_DYNAMIC_THEME, true),
            oledMode = prefs.getBoolean(KEY_OLED_MODE, false),
            displayScalePercent = prefs.getInt(KEY_DISPLAY_SCALE_PERCENT, 100).coerceIn(25, 200),
            useSystemFont = prefs.getBoolean(KEY_USE_SYSTEM_FONT, true),
            primaryColor = prefs.takeIf { it.contains(KEY_PRIMARY_COLOR) }?.getInt(KEY_PRIMARY_COLOR, 0)?.let(::Color),
            accentColor = prefs.takeIf { it.contains(KEY_ACCENT_COLOR) }?.getInt(KEY_ACCENT_COLOR, 0)?.let(::Color),
            backgroundColor = prefs.takeIf { it.contains(KEY_BACKGROUND_COLOR) }?.getInt(KEY_BACKGROUND_COLOR, 0)?.let(::Color),
            mainFontPath = prefs.getString(KEY_MAIN_FONT_PATH, "") ?: "",
            mainFontName = prefs.getString(KEY_MAIN_FONT_NAME, "") ?: "",
            headerFontPath = prefs.getString(KEY_HEADER_FONT_PATH, "") ?: "",
            headerFontName = prefs.getString(KEY_HEADER_FONT_NAME, "") ?: "",
            subheaderFontPath = prefs.getString(KEY_SUBHEADER_FONT_PATH, "") ?: "",
            subheaderFontName = prefs.getString(KEY_SUBHEADER_FONT_NAME, "") ?: "",
            tertiaryFontPath = prefs.getString(KEY_TERTIARY_FONT_PATH, "") ?: "",
            tertiaryFontName = prefs.getString(KEY_TERTIARY_FONT_NAME, "") ?: "",
        )
    }

    fun importFontFile(context: Context, uri: Uri, prefix: String): Pair<String, String> {
        val resolver = context.contentResolver
        val extension = resolver.getType(uri)
            ?.substringAfterLast('/', "")
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?: "ttf"
        val displayName = queryDisplayName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "${prefix}.$extension"
        val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
        val target = File(fontsDir, "${prefix}_${System.currentTimeMillis()}.$extension")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected font")
        return target.absolutePath to displayName
    }

    fun clearFont(context: Context, pathKey: String, nameKey: String) {
        val prefs = prefs(context)
        val oldPath = prefs.getString(pathKey, null)
        if (!oldPath.isNullOrBlank()) {
            runCatching { File(oldPath).delete() }
        }
        prefs.edit().remove(pathKey).remove(nameKey).apply()
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { cursor: Cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }
}

@Composable
fun rememberAppearanceSettings(context: Context): AppearanceSettings {
    val prefs = remember(context) { AppearancePreferences.prefs(context) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("appearance_")) version++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(version) { AppearancePreferences.load(context) }
}

fun formatColorForInput(color: Color?): String {
    if (color == null) return ""
    val argb = color.toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}

fun parseHexColor(input: String): Color? {
    val normalized = input.trim().removePrefix("#")
    if (normalized.length != 6 && normalized.length != 8) return null
    val value = normalized.toLongOrNull(16) ?: return null
    return if (normalized.length == 6) {
        Color((0xFF000000 or value).toInt())
    } else {
        Color(value.toInt())
    }
}

private fun Color.toArgb(): Int {
    val alpha = (alpha * 255f).toInt().coerceIn(0, 255)
    val red = (red * 255f).toInt().coerceIn(0, 255)
    val green = (green * 255f).toInt().coerceIn(0, 255)
    val blue = (blue * 255f).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

fun fontDisplayName(path: String, savedName: String): String =
    when {
        savedName.isNotBlank() -> savedName
        path.isBlank() -> "Default"
        else -> File(path).name
    }

fun String.asUriOrNull(): Uri? = runCatching { toUri() }.getOrNull()
