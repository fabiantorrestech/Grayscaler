package io.github.cloudburst.grayscaler

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

object GrayscaleStateManager {

    enum class Decision { ENABLE, DISABLE, SKIP }

    private var appListStore: AppListStore? = null
    private var scheduleStore: ScheduleStore? = null
    private var photoViewerStore: PhotoViewerStore? = null
    private var overlayIgnoreStore: OverlayIgnoreStore? = null

    private var lastPkg: String? = null
    private var lastClassName: String = ""

    fun invalidate(context: Context) {
        appListStore = null
        scheduleStore = null
        photoViewerStore = null
        overlayIgnoreStore = null
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pkg = lastPkg ?: prefs.getString("last_foreground_pkg", null) ?: return
        val cls = lastClassName.ifEmpty { prefs.getString("last_foreground_class", "") ?: "" }
        applyToSystem(context, evaluate(pkg, cls, context))
    }

    fun evaluate(pkg: String, className: String, context: Context): Decision {
        lastPkg = pkg
        lastClassName = className

        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // Photo viewer auto-disable — overrides all other rules
        val photoStore = photoViewerStore ?: PhotoViewerStore(context).also { photoViewerStore = it }
        if (photoStore.masterEnabled && photoStore.matches(pkg, className)) return Decision.DISABLE

        // User-managed ignore list (includes system ignores + Gemini group + user additions)
        val ignoreStore = overlayIgnoreStore ?: OverlayIgnoreStore(context).also { it.load(); overlayIgnoreStore = it }
        if (ignoreStore.effectiveIgnoreList().contains(pkg)) return Decision.SKIP

        // Skip keyboard/IME packages
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return Decision.SKIP

        // Pause active — grayscale off
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) return Decision.DISABLE

        // Global switch off — grayscale off
        if (!prefs.getBoolean("grayscaler_enabled", true)) return Decision.DISABLE

        // Active schedule — apply the schedule's profile
        val schedStore = scheduleStore ?: ScheduleStore(context).also { it.load(); scheduleStore = it }
        if (schedStore.scheduleOverrideActive) {
            val active = schedStore.schedules.find { it.id == schedStore.activeScheduleId }
            return when {
                active == null || active.profileMode == "global" -> evaluateAppList(pkg, context)
                active.profileMode == "whitelist" ->
                    if (pkg !in active.profileWhitelist) Decision.ENABLE else Decision.DISABLE
                active.profileMode == "blacklist" ->
                    if (pkg in active.profileBlacklist) Decision.ENABLE else Decision.DISABLE
                else -> evaluateAppList(pkg, context)
            }
        }

        return evaluateAppList(pkg, context)
    }

    fun applyToSystem(context: Context, decision: Decision) {
        if (decision == Decision.SKIP) return
        if (decision == Decision.ENABLE) {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.ON)
        } else {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
        }
    }

    private fun evaluateAppList(pkg: String, context: Context): Decision {
        val store = appListStore ?: AppListStore(context).also { it.load(); appListStore = it }
        return if (store.shouldGrayScale(pkg)) Decision.ENABLE else Decision.DISABLE
    }
}
