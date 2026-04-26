package io.github.cloudburst.grayscaler

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Context.USAGE_STATS_SERVICE
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.atomic.AtomicReference

data class CatalogApp(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap
)

object AppCatalogRepository {
    private val cachedApps = AtomicReference<List<CatalogApp>?>(null)
    private val cacheLock = Any()

    fun hasCache(): Boolean = cachedApps.get() != null

    fun preload(context: Context) {
        apps(context)
    }

    fun apps(context: Context): List<CatalogApp> {
        cachedApps.get()?.let { return it }
        synchronized(cacheLock) {
            cachedApps.get()?.let { return it }
            val built = buildCatalog(context.applicationContext)
            cachedApps.set(built)
            return built
        }
    }

    fun snapshotApps(context: Context, toggledPackages: Set<String>): List<Pair<CatalogApp, Boolean>> {
        val baseApps = apps(context)
        val knownPackages = baseApps.asSequence().map { it.packageName }.toHashSet()
        val extraApps = toggledPackages
            .filterNot(knownPackages::contains)
            .mapNotNull { resolveApp(context.applicationContext, it) }
        return (baseApps + extraApps)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
            .map { it to toggledPackages.contains(it.packageName) }
    }

    fun appForPackage(context: Context, packageName: String): CatalogApp? {
        cachedApps.get()?.firstOrNull { it.packageName == packageName }?.let { return it }
        return resolveApp(context.applicationContext, packageName)
    }

    fun overlayApps(context: Context): List<CatalogApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter { info ->
                try {
                    val perms = pm.getPackageInfo(info.packageName, PackageManager.GET_PERMISSIONS)
                    perms.requestedPermissions?.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == true &&
                        info.packageName != context.packageName
                } catch (_: Exception) {
                    false
                }
            }
            .mapNotNull { resolveApp(context.applicationContext, it.packageName) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    private fun buildCatalog(context: Context): List<CatalogApp> {
        return (recentPackages(context) + webApks(context))
            .mapNotNull { resolveApp(context, it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    private fun webApks(context: Context): Set<String> {
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

    private fun recentPackages(context: Context): Set<String> {
        val usageStatsManager = context.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        return usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            0,
            System.currentTimeMillis()
        )
            .filter { it.totalTime > 0 }
            .sortedBy { -it.lastTimeUsed }
            .map { it.packageName }
            .toSet()
    }

    private fun resolveApp(context: Context, packageName: String): CatalogApp? {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val appName = context.packageManager.getApplicationLabel(appInfo).toString()
            val icon = context.packageManager.getApplicationIcon(appInfo).toBitmap().asImageBitmap()
            CatalogApp(packageName, appName, icon)
        } catch (_: Exception) {
            null
        }
    }
}

private val UsageStats.totalTime: Long
    get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        totalTimeVisible
    } else {
        totalTimeInForeground
    }
