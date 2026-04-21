package io.github.cloudburst.grayscaler

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Context.USAGE_STATS_SERVICE
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlin.collections.mapNotNull

data class AppEntry(
    val packageName: String,
    val appName: String,
    val icon: Drawable
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

    var apps: List<Pair<AppEntry, Boolean>> = emptyList()
        get() {
            if (field.isEmpty()) {
                field = (toggledApps + listPackages() + listWebApks()).mapNotNull { packageName ->
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                        val appName = context.packageManager.getApplicationLabel(appInfo).toString()
                        val icon = context.packageManager.getApplicationIcon(appInfo)
                        AppEntry(packageName, appName, icon) to toggledApps.contains(packageName)
                    } catch (e: Exception) {
                        null
                    }
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.appName })
            }
            return field
        }
        private set

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
        apps = emptyList()
    }

    private fun listWebApks(): Set<String> {
        return try {
            context.packageManager
                .getInstalledPackages(PackageManager.GET_META_DATA)
                .filter { pkg ->
                    pkg.applicationInfo?.metaData
                        ?.containsKey("org.chromium.webapk.shell_apk.runtimeHost") == true
                }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun listPackages() : Set<String> {
        val usageStatsManager = context.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val result = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            0,
            System.currentTimeMillis()
        )
            .filter { it.totalTime > 0 }
            .sortedBy { -it.lastTimeUsed }
            .map { it.packageName }
            .toSet()

        return result
    }
}

private val UsageStats.totalTime get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) totalTimeVisible else totalTimeInForeground