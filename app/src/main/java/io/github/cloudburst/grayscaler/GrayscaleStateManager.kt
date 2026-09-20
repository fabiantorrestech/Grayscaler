package io.github.cloudburst.grayscaler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

object GrayscaleStateManager {

    enum class Decision { ENABLE, DISABLE, SKIP }

    /** Logcat tag for every state transition. Read with `adb logcat -s Grayscaler`. */
    const val LOG_TAG = "Grayscaler"

    private var appListStore: AppListStore? = null
    private var scheduleStore: ScheduleStore? = null
    private var perAppViewsStore: PerAppViewsStore? = null
    private var overlayIgnoreStore: OverlayIgnoreStore? = null

    private var lastMeaningfulPkg: String? = null
    private var lastMeaningfulClassName: String = ""

    var lastDecision: Decision? = null
        private set

    // Incremented by every apply. A delayed re-assert ON write only lands if no other apply
    // happened in between, so a DISABLE arriving inside the gap always wins.
    @Volatile
    private var generation = 0
    private var lastDevRetoggleAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val PREF_LAST_MEANINGFUL_PKG = "last_meaningful_foreground_pkg"
    private const val PREF_LAST_MEANINGFUL_CLASS = "last_meaningful_foreground_class"
    private const val REASSERT_GAP_MS = 150L
    private const val DEV_RETOGGLE_MIN_INTERVAL_MS = 5_000L

    fun invalidate(context: Context, reason: String = "invalidate") {
        invalidateInternal(context, reason, reassert = false)
    }

    /**
     * Full re-derivation used by the global on/off switch. The caller has already cancelled every
     * pause and re-synced schedule/bedtime runtime flags; this drops the cached stores, recomputes
     * the decision from scratch and re-asserts it on the system, forcing a real OFF -> ON
     * transition when the Secure value already reads "on" (so a colour transform the OS dropped
     * comes back even though the setting never changed).
     */
    fun reset(context: Context, reason: String) {
        invalidateInternal(context, "reset:$reason", reassert = true)
    }

    private fun invalidateInternal(context: Context, reason: String, reassert: Boolean) {
        clearCaches()
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // If the outcome is unambiguous regardless of which app is foreground, apply directly.
        // This handles cases where there is no last meaningful foreground (e.g. lockscreen).
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE, "$reason:paused")
            return
        }
        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE, "$reason:disabled")
            return
        }
        val bedtimeStore = BedtimeStore(context)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) {
            applyToSystem(context, Decision.ENABLE, "$reason:bedtime", reassert)
            return
        }

        val pkg = lastMeaningfulPkg ?: prefs.getString(PREF_LAST_MEANINGFUL_PKG, null)
        if (pkg == null) {
            // No foreground app is known yet (fresh process, lockscreen, toggled on from a tile/
            // widget before any window event). Pause is off, the master switch is on, and bedtime
            // isn't active, so the documented default — grayscale on — is the correct global
            // fallback. Returning without applying here is the "tile shows ON but display is in
            // color" desync.
            applyToSystem(context, Decision.ENABLE, "$reason:no_foreground", reassert)
            return
        }
        val cls = lastMeaningfulClassName.ifEmpty { prefs.getString(PREF_LAST_MEANINGFUL_CLASS, "") ?: "" }
        // Our own package sits in the overlay ignore list. Evaluate it the way MainActivity.onResume
        // does, so invalidating from inside the app gives the same result as opening the app.
        val result = if (pkg == context.packageName) {
            evaluateCurrentApp(context, cls)
        } else {
            evaluate(pkg, cls, context)
        }
        // SKIP means the last foreground was a system/overlay app that shouldn't gate grayscale state.
        // At this point we know pause is off, grayscaler is enabled, and bedtime isn't active — default to ENABLE.
        applyToSystem(
            context,
            if (result == Decision.SKIP) Decision.ENABLE else result,
            "$reason:$pkg",
            reassert
        )
    }

    private fun clearCaches() {
        appListStore = null
        scheduleStore = null
        perAppViewsStore = null
        overlayIgnoreStore = null
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

    /**
     * The single writer of the system daltonizer keys.
     *
     * @param reason short tag naming what triggered this write; shows up in logcat and the log file.
     * @param reassert when true and the decision is ENABLE while the Secure value already reads
     *   "on", force a real OFF -> ON transition. Re-writing the same value is a no-op for the
     *   system's colour observer, so without this a dropped transform stays dropped.
     */
    fun applyToSystem(
        context: Context,
        decision: Decision,
        reason: String = "unspecified",
        reassert: Boolean = false,
    ) {
        if (decision == Decision.SKIP) return
        val prev = lastDecision
        val gen = ++generation
        val resolver = context.contentResolver
        try {
            if (decision == Decision.ENABLE) {
                val curEnabled = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
                if (curEnabled == MainService.ON && shouldReassert(context, reassert)) {
                    // The two writes must be separated in time: the system observer reads the value
                    // when it dispatches, so OFF immediately followed by ON is seen as "still ON".
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
                    lastDecision = decision
                    Log.i(LOG_TAG, "apply decision=ENABLE reason=$reason prev=$prev reassert=pending")
                    GrayscaleLog.log(context, "apply", "reassert OFF->ON scheduled reason=$reason")
                    val appContext = context.applicationContext
                    mainHandler.postDelayed({
                        // Any apply in the gap (a DISABLE for a colour app, or a plain ENABLE that
                        // already wrote ON) supersedes this one.
                        if (generation != gen) return@postDelayed
                        try {
                            Settings.Secure.putInt(
                                appContext.contentResolver,
                                MainService.DISPLAY_DALTONIZER_ENABLED,
                                MainService.ON
                            )
                            logApplyOutcome(appContext, Decision.ENABLE, "$reason:reassert_on", prev)
                        } catch (t: Throwable) {
                            logApplyFailure(appContext, Decision.ENABLE, "$reason:reassert_on", prev, t)
                        }
                    }, REASSERT_GAP_MS)
                    return
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
            logApplyOutcome(context, decision, reason, prev)
        } catch (t: Throwable) {
            // Most likely WRITE_SECURE_SETTINGS was revoked. Leave lastDecision untouched and
            // surface the failure instead of silently desyncing or crashing the caller (this runs
            // from accessibility-event and onResume paths that previously had no try/catch).
            logApplyFailure(context, decision, reason, prev, t)
        }
    }

    private fun shouldReassert(context: Context, requested: Boolean): Boolean {
        if (requested) return true
        // RC6 dev experiment: re-assert on ordinary ENABLE applies too, rate limited so a burst of
        // window events can't turn into a strobe.
        if (!GrayscaleLog.forceRetoggle(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastDevRetoggleAt < DEV_RETOGGLE_MIN_INTERVAL_MS) return false
        lastDevRetoggleAt = now
        return true
    }

    /**
     * Reads the Secure settings straight back after a write and logs whether the system actually
     * holds the requested decision. A `MISMATCH` line means the write was accepted but the value
     * didn't stick (or an external actor changed it).
     */
    private fun logApplyOutcome(context: Context, decision: Decision, reason: String, prev: Decision?) {
        try {
            val resolver = context.contentResolver
            val actualEnabled = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
            val actualMode = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, -1)
            val expectEnabled = if (decision == Decision.ENABLE) MainService.ON else MainService.OFF
            val consistent = actualEnabled == expectEnabled &&
                (decision != Decision.ENABLE || actualMode == MainService.MONOCHROME)
            val verdict = if (consistent) "OK" else "MISMATCH"
            val line = "decision=$decision reason=$reason prev=$prev " +
                "readback[enabled=$actualEnabled mode=$actualMode] $verdict"
            Log.i(LOG_TAG, "apply $line")
            // caller() captures a stack trace, so only pay for it when file logging is on.
            if (GrayscaleLog.isEnabled(context)) {
                GrayscaleLog.log(context, "apply", "$line caller=${GrayscaleLog.caller()}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun logApplyFailure(
        context: Context,
        decision: Decision,
        reason: String,
        prev: Decision?,
        t: Throwable,
    ) {
        val line = "WRITE FAILED decision=$decision reason=$reason prev=$prev " +
            "${t.javaClass.simpleName}: ${t.message}"
        Log.e(LOG_TAG, "apply $line")
        if (GrayscaleLog.isEnabled(context)) {
            GrayscaleLog.log(context, "apply", "$line caller=${GrayscaleLog.caller()}")
        }
    }

    /** True when the system Secure value agrees with what this app last decided. */
    fun systemMatchesBelief(context: Context): Boolean {
        val belief = lastDecision ?: return true
        return try {
            val actual = Settings.Secure.getInt(
                context.contentResolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1
            )
            actual == if (belief == Decision.ENABLE) MainService.ON else MainService.OFF
        } catch (_: Throwable) {
            true
        }
    }

    /** One-line picture of everything that feeds a grayscale decision, plus what the OS holds. */
    fun stateSnapshot(context: Context): String {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val pauseUntil = prefs.getLong("pause_until", 0L)
        val resolver = context.contentResolver
        val osEnabled = try {
            Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
        } catch (_: Throwable) { -2 }
        val osMode = try {
            Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, -1)
        } catch (_: Throwable) { -2 }
        return buildString {
            append("enabled=").append(prefs.getBoolean("grayscaler_enabled", true))
            append(" pause_until=").append(pauseUntil)
            append(" pause_remaining_ms=").append((pauseUntil - now).coerceAtLeast(0L))
            append(" pause_type=").append(prefs.getString("pause_type", "timed"))
            append(" session_pkg=").append(prefs.getString("session_pause_pkg", ""))
            append(" session_start_ms=").append(prefs.getLong("session_pause_start_ms", 0L))
            append(" schedule_override=").append(prefs.getBoolean("schedule_override_active", false))
            append(" active_schedule=").append(prefs.getString("active_schedule_id", null))
            append(" bedtime_override=").append(prefs.getBoolean("bedtime_override_active", false))
            append(" bedtime_latch=").append(prefs.getBoolean("bedtime_charge_latch_active", false))
            append(" mem_last_pkg=").append(lastMeaningfulPkg)
            append(" mem_last_cls=").append(lastMeaningfulClassName)
            append(" pref_last_pkg=").append(prefs.getString(PREF_LAST_MEANINGFUL_PKG, null))
            append(" pref_last_cls=").append(prefs.getString(PREF_LAST_MEANINGFUL_CLASS, null))
            append(" last_decision=").append(lastDecision)
            append(" os_daltonizer_enabled=").append(osEnabled)
            append(" os_daltonizer=").append(osMode)
        }
    }

    fun dumpState(context: Context, label: String) {
        val line = try {
            "state[$label] ${stateSnapshot(context)}"
        } catch (t: Throwable) {
            "state[$label] snapshot failed: ${t.javaClass.simpleName}: ${t.message}"
        }
        Log.i(LOG_TAG, line)
        GrayscaleLog.log(context, "state", line)
    }

    /** Last app GrayscaleStateManager considered meaningful (in-memory, else persisted). */
    fun lastMeaningfulForegroundPkg(context: Context): String? {
        return lastMeaningfulPkg ?: context
            .getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_MEANINGFUL_PKG, null)
    }

    fun applySystemEventMode(context: Context, mode: String, reason: String = "system_event"): Boolean {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val tag = "system_event:$reason:$mode"

        if ((prefs.getString("pause_type", "timed") ?: "timed") == "session") {
            applyToSystem(context, Decision.DISABLE, "$tag:session")
            return true
        }
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE, "$tag:paused")
            return true
        }

        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE, "$tag:disabled")
            return true
        }

        val bedtimeStore = BedtimeStore(context)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) {
            applyToSystem(context, Decision.ENABLE, "$tag:bedtime")
            return true
        }

        when (mode) {
            "enable" -> applyToSystem(context, Decision.ENABLE, tag)
            "disable" -> applyToSystem(context, Decision.DISABLE, tag)
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
