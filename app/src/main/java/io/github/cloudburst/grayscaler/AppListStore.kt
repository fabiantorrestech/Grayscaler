package io.github.cloudburst.grayscaler

import android.content.Context

data class AppEntry(
    val packageName: String,
    val appName: String
)

class AppListStore(
    val context: Context
) {

    private val legacyPath = context.filesDir.resolve("apps.txt")
    private val whitelistPath = context.filesDir.resolve("apps_whitelist.txt")
    private val blacklistPath = context.filesDir.resolve("apps_blacklist.txt")

    var whitelist: Boolean = true
    var whitelistedApps: Set<String> = emptySet()
    var blacklistedApps: Set<String> = emptySet()

    var toggledApps: Set<String>
        get() = if (whitelist) whitelistedApps else blacklistedApps
        set(value) { if (whitelist) whitelistedApps = value else blacklistedApps = value }

    val apps: List<Pair<AppEntry, Boolean>>
        get() = snapshotApps()

    fun save() {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("app_list_whitelist_mode", whitelist).apply()
        writeApps(whitelistPath, whitelistedApps)
        writeApps(blacklistPath, blacklistedApps)
    }

    private fun writeApps(path: java.io.File, apps: Set<String>) {
        val writer = path.outputStream().writer()
        apps.forEach { writer.write("$it\n") }
        writer.close()
    }

    fun load() {
        if (legacyPath.exists() && !whitelistPath.exists() && !blacklistPath.exists()) {
            migrateLegacy()
            return
        }
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        whitelist = prefs.getBoolean("app_list_whitelist_mode", true)
        whitelistedApps = readApps(whitelistPath)
        blacklistedApps = readApps(blacklistPath)
    }

    private fun readApps(path: java.io.File): Set<String> {
        if (!path.exists()) return emptySet()
        return path.inputStream().bufferedReader().readLines().filter { it.isNotBlank() }.toSet()
    }

    private fun migrateLegacy() {
        val reader = legacyPath.inputStream().bufferedReader()
        whitelist = reader.readLine() != "b"
        val apps = reader.readLines().filter { it.isNotBlank() }.toSet()
        reader.close()
        if (whitelist) whitelistedApps = apps else blacklistedApps = apps
        legacyPath.delete()
    }

    fun shouldGrayScale(packageName: String): Boolean {
        return if (whitelist) !toggledApps.contains(packageName) else toggledApps.contains(packageName)
    }

    fun toggleApp(packageName: String) {
        if (toggledApps.contains(packageName)) {
            toggledApps -= packageName
        } else {
            toggledApps += packageName
        }
    }

    fun invalidate() {
        // No-op: app metadata is cached globally, while enabled state is derived per access.
    }

    fun hasPreloadedApps(): Boolean = AppCatalogRepository.hasCache()

    fun preloadApps() {
        AppCatalogRepository.preload(context)
    }

    fun snapshotApps(): List<Pair<AppEntry, Boolean>> {
        val toggled = toggledApps
        return AppCatalogRepository.snapshotApps(context, toggled).map { (app, enabled) ->
            AppEntry(
                packageName = app.packageName,
                appName = app.appName
            ) to enabled
        }
    }
}
