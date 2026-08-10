package io.github.cloudburst.grayscaler

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

object GrayscaleStateManager {

    enum class Decision { ENABLE, DISABLE, SKIP }

    private var appListStore: AppListStore? = null
    private var scheduleStore: ScheduleStore? = null
    private var perAppViewsStore: PerAppViewsStore? = null
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
        perAppViewsStore = null
        overlayIgnoreStore = null
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // If the outcome is unambiguous regardless of which app is foreground, apply directly.
        // This handles cases where there is no last meaningful foreground (e.g. lockscreen).
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE)
            return
        }
        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE)
            return
        }
        if (BedtimeStore(context).syncRuntimeState()) {
            // syncRuntimeState updates persisted runtime flags; continue with evaluation below.
        }
        if (BedtimeStore(context).bedtimeOverrideActive) {
            applyToSystem(context, Decision.ENABLE)
            return
        }

        val pkg = lastMeaningfulPkg ?: prefs.getString(PREF_LAST_MEANINGFUL_PKG, null)
        if (pkg == null) {
            // No foreground app is known yet (fresh process, lockscreen, toggled on from a tile/
            // widget before any window event). Pause is off, the master switch is on, and bedtime
            // isn't active, so the documented default — grayscale on — is the correct global
            // fallback. Returning without applying here is the "tile shows ON but display is in
            // color" desync.
            GrayscaleLog.log(context, "invalidate", "no known foreground -> default ENABLE")
            applyToSystem(context, Decision.ENABLE)
            return
        }
        val cls = lastMeaningfulClassName.ifEmpty { prefs.getString(PREF_LAST_MEANINGFUL_CLASS, "") ?: "" }
        // SKIP means the last foreground was a system/overlay app that shouldn't gate grayscale state.
        // At this point we know pause is off, grayscaler is enabled, and bedtime isn't active — default to ENABLE.
        val result = evaluate(pkg, cls, context)
        applyToSystem(context, if (result == Decision.SKIP) Decision.ENABLE else result)
    }

    fun evaluateCurrentApp(context: Context, className: String): Decision {
        return evaluate(context.packageName, className, context, allowSelfIgnoreBypass = true)
    }

    fun evaluate(pkg: String, className: String, context: Context): Decision {
        return evaluate(pkg, className, context, allowSelfIgnoreBypass = false)
    }

    private fun evaluate(
        pkg: String,
        className: String,
        context: Context,
        allowSelfIgnoreBypass: Boolean,
    ): Decision {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val activeSchedule = currentActiveSchedule(context)

        // Per-app views auto-disable — overrides all other rules
        val perAppViewMatched = if (activeSchedule?.perAppViewsProfileEnabled == true) {
            ScheduleProfiles.matchesPerAppView(activeSchedule, pkg, className)
        } else {
            val perAppViews = perAppViewsStore ?: PerAppViewsStore(context).also { perAppViewsStore = it }
            perAppViews.masterEnabled && perAppViews.matches(pkg, className)
        }
        if (perAppViewMatched) {
            rememberMeaningfulForeground(context, pkg, className)
            return Decision.DISABLE
        }

        // User-managed ignore list (includes system ignores + Gemini group + user additions)
        val effectiveIgnoreList = if (activeSchedule?.overlayProfileEnabled == true) {
            ScheduleProfiles.effectiveOverlayIgnoreList(activeSchedule)
        } else {
            val ignoreStore = overlayIgnoreStore ?: OverlayIgnoreStore(context).also { it.load(); overlayIgnoreStore = it }
            ignoreStore.effectiveIgnoreList()
        }
        val bypassIgnore = allowSelfIgnoreBypass && pkg == context.packageName
        if (!bypassIgnore && effectiveIgnoreList.contains(pkg)) return Decision.SKIP

        // Skip keyboard/IME packages
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return Decision.SKIP

        rememberMeaningfulForeground(context, pkg, className)

        // Session pause active — disable grayscale for the target app only
        if (prefs.getString("pause_type", "timed") == "session" &&
            pkg == prefs.getString("session_pause_pkg", "")) return Decision.DISABLE

        // Timed pause active — grayscale off
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) return Decision.DISABLE

        // Global switch off — grayscale off
        if (!prefs.getBoolean("grayscaler_enabled", true)) return Decision.DISABLE

        // Bedtime mode — force grayscale regardless of per-app rules
        val bedtimeStore = BedtimeStore(context)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) return Decision.ENABLE

        // Active schedule — apply the schedule's profile
        if (activeSchedule != null) {
            return when {
                !activeSchedule.appListProfileEnabled -> evaluateAppList(pkg, context)
                activeSchedule.effectiveAppListMode() == "whitelist" ->
                    if (pkg !in activeSchedule.profileWhitelist) Decision.ENABLE else Decision.DISABLE
                activeSchedule.effectiveAppListMode() == "blacklist" ->
                    if (pkg in activeSchedule.profileBlacklist) Decision.ENABLE else Decision.DISABLE
                else -> evaluateAppList(pkg, context)
            }
        }

        return evaluateAppList(pkg, context)
    }

    fun applyToSystem(context: Context, decision: Decision) {
        if (decision == Decision.SKIP) return
        val resolver = context.contentResolver
        try {
            if (decision == Decision.ENABLE) {
                // RC6 diagnostic: writing ENABLED=1 while it's already 1 is a no-op that won't
                // re-fire the system's color observer, so a dropped transform stays dropped. When
                // the dev toggle is on, force a 0 -> 1 transition first (may briefly flash to color).
                if (GrayscaleLog.forceRetoggle(context)) {
                    val curEnabled = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
                    val curMode = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, -1)
                    if (curEnabled == MainService.ON) {
                        Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
                        GrayscaleLog.log(
                            context,
                            "retoggle",
                            "forced OFF before re-ENABLE (was enabled=$curEnabled mode=$curMode) caller=${GrayscaleLog.caller()}"
                        )
                    }
                }
                Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
                Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.ON)
            } else {
                Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
            }
            // Record the belief only after the write actually succeeded. Setting it beforehand let
            // the app's notion of "on" diverge from the screen when the write threw — a root cause
            // of the stuck-in-color bug.
            lastDecision = decision
            logApplyOutcome(context, decision)
        } catch (t: Throwable) {
            // Most likely WRITE_SECURE_SETTINGS was revoked. Leave lastDecision untouched and
            // surface the failure instead of silently desyncing or crashing the caller (this runs
            // from accessibility-event and onResume paths that previously had no try/catch).
            GrayscaleLog.log(
                context,
                "apply",
                "WRITE FAILED decision=$decision ${t.javaClass.simpleName}: ${t.message} caller=${GrayscaleLog.caller()}"
            )
        }
    }

    /**
     * Reads the Secure settings straight back after a write and logs whether the screen actually
     * matches the requested decision. A `MISMATCH` line means the write was accepted but the value
     * didn't stick (or an external actor changed it) — the evidence we need for the stuck-in-color
     * reports. No-op unless diagnostic logging is enabled.
     */
    private fun logApplyOutcome(context: Context, decision: Decision) {
        if (!GrayscaleLog.isEnabled(context)) return
        try {
            val resolver = context.contentResolver
            val actualEnabled = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
            val actualMode = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, -1)
            val expectEnabled = if (decision == Decision.ENABLE) MainService.ON else MainService.OFF
            val consistent = actualEnabled == expectEnabled &&
                (decision != Decision.ENABLE || actualMode == MainService.MONOCHROME)
            GrayscaleLog.log(
                context,
                "apply",
                "decision=$decision readback[enabled=$actualEnabled mode=$actualMode] " +
                    (if (consistent) "OK" else "MISMATCH") + " caller=${GrayscaleLog.caller()}"
            )
        } catch (_: Throwable) {
        }
    }

    /** Last app GrayscaleStateManager considered meaningful (in-memory, else persisted). */
    fun lastMeaningfulForegroundPkg(context: Context): String? {
        return lastMeaningfulPkg ?: context
            .getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_MEANINGFUL_PKG, null)
    }

    fun applySystemEventMode(context: Context, mode: String): Boolean {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        if ((prefs.getString("pause_type", "timed") ?: "timed") == "session") {
            applyToSystem(context, Decision.DISABLE)
            return true
        }
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE)
            return true
        }

        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE)
            return true
        }

        val bedtimeStore = BedtimeStore(context)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) {
            applyToSystem(context, Decision.ENABLE)
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

    fun currentActiveSchedule(context: Context): Schedule? {
        val schedStore = scheduleStore ?: ScheduleStore(context).also { it.load(); scheduleStore = it }
        if (!schedStore.scheduleOverrideActive) return null
        return schedStore.schedules.find { it.id == schedStore.activeScheduleId && it.enabled }
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
