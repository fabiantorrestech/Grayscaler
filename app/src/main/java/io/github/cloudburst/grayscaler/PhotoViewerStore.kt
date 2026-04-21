package io.github.cloudburst.grayscaler

import android.content.Context

class PhotoViewerStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

    val masterEnabled: Boolean get() = prefs.getBoolean("photo_viewer_enabled", false)
    val diagnosticEnabled: Boolean get() = prefs.getBoolean("photo_viewer_diagnostic", false)

    fun setMasterEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("photo_viewer_enabled", enabled).apply()

    fun setDiagnosticEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("photo_viewer_diagnostic", enabled).apply()

    fun isBuiltInEnabled(id: String): Boolean =
        prefs.getBoolean("pv_builtin_${id}_on", true)

    fun setBuiltInEnabled(id: String, enabled: Boolean) =
        prefs.edit().putBoolean("pv_builtin_${id}_on", enabled).apply()

    fun getBuiltInPattern(entry: BuiltInEntry): String =
        prefs.getString("pv_builtin_${entry.id}_pat", entry.defaultPattern) ?: entry.defaultPattern

    fun setBuiltInPattern(id: String, pattern: String) =
        prefs.edit().putString("pv_builtin_${id}_pat", pattern).apply()

    fun getCustomEntries(): List<CustomEntry> {
        val raw = prefs.getString("pv_custom", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { item ->
            val parts = item.split("::")
            if (parts.size == 3) CustomEntry(parts[0], parts[1], parts[2] == "true") else null
        }
    }

    fun saveCustomEntries(entries: List<CustomEntry>) {
        val encoded = entries.joinToString("|") { "${it.packageName}::${it.classPattern}::${it.enabled}" }
        prefs.edit().putString("pv_custom", encoded).apply()
    }

    fun matches(pkg: String, className: String): Boolean {
        for (entry in BUILT_IN_ENTRIES) {
            if (!isBuiltInEnabled(entry.id)) continue
            val pattern = getBuiltInPattern(entry)
            if (pattern.isBlank()) continue
            if (pkg == entry.packageName && className.contains(pattern, ignoreCase = true)) return true
        }
        for (entry in getCustomEntries()) {
            if (!entry.enabled || entry.classPattern.isBlank()) continue
            if (pkg == entry.packageName && className.contains(entry.classPattern, ignoreCase = true)) return true
        }
        return false
    }

    data class BuiltInEntry(
        val id: String,
        val displayName: String,
        val packageName: String,
        val defaultPattern: String,
        val patternNote: String = ""
    )

    data class CustomEntry(
        val packageName: String,
        val classPattern: String,
        val enabled: Boolean
    )

    companion object {
        val BUILT_IN_ENTRIES = listOf(
            BuiltInEntry("whatsapp", "WhatsApp", "com.whatsapp", "MediaView"),
            BuiltInEntry("googlemessages", "Google Messages", "com.google.android.apps.messaging", "mpv"),
            BuiltInEntry("signal", "Signal", "org.thoughtcrime.securesms", "MediaPreview"),
            BuiltInEntry(
                "messenger", "Facebook Messenger", "com.facebook.orca", "",
                patternNote = "Messenger uses obfuscated class names — reliable detection is not possible"
            ),
            BuiltInEntry(
                "telegram", "Telegram", "org.telegram.messenger", "",
                patternNote = "Telegram is single-activity — the class name may not change when opening a photo"
            ),
            BuiltInEntry("instagram", "Instagram", "com.instagram.android", ""),
        )
    }
}
