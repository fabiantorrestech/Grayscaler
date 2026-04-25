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

    private var lastMeaningfulPkg: String? = null
    private var lastMeaningfulClassName: String = ""

    var lastDecision: Decision? = null
        private set

    private const val PREF_LAST_MEANINGFUL_PKG = "last_meaningful_foreground_pkg"
    private const val PREF_LAST_MEANINGFUL_CLASS = "last_meaningful_foreground_class"

    fun invalidate(context: Context) {
        appListStore = null
        scheduleStore = null
        photoViewerStore = null
        overlayIgnoreStore = null
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val pkg = lastMeaningfulPkg ?: prefs.getString(PREF_LAST_MEANINGFUL_PKG, null) ?: return
        val cls = lastMeaningfulClassName.ifEmpty { prefs.getString(PREF_LAST_MEANINGFUL_CLASS, "") ?: "" }
        applyToSystem(context, evaluate(pkg, cls, context))
    }

    fun evaluate(pkg: String, className: String, context: Context): Decision {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // Photo viewer auto-disable — overrides all other rules
        val photoStore = photoViewerStore ?: PhotoViewerStore(context).also { photoViewerStore = it }
        if (photoStore.masterEnabled && photoStore.matches(pkg, className)) {
            rememberMeaningfulForeground(context, pkg, className)
            return Decision.DISABLE
        }

        // User-managed ignore list (includes system ignores + Gemini group + user additions)
        val ignoreStore = overlayIgnoreStore ?: OverlayIgnoreStore(context).also { it.load(); overlayIgnoreStore = it }
        if (ignoreStore.effectiveIgnoreList().contains(pkg)) return Decision.SKIP

        // Skip keyboard/IME packages
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return Decision.SKIP

        rememberMeaningfulForeground(context, pkg, className)

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
        lastDecision = decision
        if (decision == Decision.ENABLE) {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.ON)
        } else {
            Settings.Secure.putInt(context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
        }
    }

    fun applySystemEventMode(context: Context, mode: String): Boolean {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE)
            return true
        }

        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE)
            return true
        }

        when (mode) {
            "enable" -> applyToSystem(context, Decision.ENABLE)
            "disable" -> applyToSystem(context, Decision.DISABLE)
            else -> return false
        }
        return true
    }

    private fun evaluateAppList(pkg: String, context: Context): Decision {
        val store = appListStore ?: AppListStore(context).also { it.load(); appListStore = it }
        return if (store.shouldGrayScale(pkg)) Decision.ENABLE else Decision.DISABLE
    }

    private fun rememberMeaningfulForeground(context: Context, pkg: String, className: String) {
        lastMeaningfulPkg = pkg
        lastMeaningfulClassName = className
        context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_MEANINGFUL_PKG, pkg)
            .putString(PREF_LAST_MEANINGFUL_CLASS, className)
            .apply()
    }
}
