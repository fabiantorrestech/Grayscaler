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
    private var webShortcutStore: WebShortcutStore? = null

    // Keyboard apps are skipped in evaluate(). Asking InputMethodManager is a binder call returning
    // large objects, so the answer is cached and refreshed only when the enabled-keyboards setting
    // changes (or, if that setting cannot be read, at most once a minute).
    private var imeKey: String? = null
    private var imeCheckedAt = 0L
    private var imePackages: Set<String> = emptySet()
    private const val IME_FALLBACK_TTL_MS = 60_000L

    private var lastMeaningfulPkg: String? = null
    private var lastMeaningfulClassName: String = ""

    var lastDecision: Decision? = null
        private set

    // Incremented by every apply. A delayed re-assert ON write only lands if no other apply
    // happened in between, so a DISABLE arriving inside the gap always wins.
    @Volatile
    private var generation = 0
    private var lastDevRetoggleAt = 0L

    // When this app last wrote the daltonizer keys. Lets the settings observer tell our own writes
    // (which also fire it) apart from another app's.
    @Volatile
    private var lastWriteAtMs = 0L

    /** When the system grayscale value last changed without Grayscaler+ having written it. 0 = never. */
    @Volatile
    var lastExternalChangeAtMs = 0L
        private set
    private const val OWN_WRITE_WINDOW_MS = 500L
    private val mainHandler = Handler(Looper.getMainLooper())

    // "No daltonizer". An earlier build used it as the colour state; it is cleaned up on the next
    // write so Android's own Colour correction tile never turns on a filter that does nothing.
    private const val MODE_NONE = -1

    private var lastSkipReason: String? = null

    // Set while a system surface (app switcher, notification shade, lock screen, power menu) has
    // forced grayscale on or off through its mode setting. Until that surface closes, re-evaluations
    // from elsewhere must not undo it. On Pixels every flip relaunches the visible activities; with
    // our own screen behind the app switcher, its pause and resume re-evaluated and flipped back,
    // which relaunched the app switcher, which flipped it again: a flicker loop that also emptied
    // the app switcher. Expires on its own in case a surface ends without us noticing.
    @Volatile
    private var surfaceOverrideSince = 0L
    private const val SURFACE_OVERRIDE_MAX_MS = 120_000L

    fun isSurfaceOverrideActive(): Boolean {
        val since = surfaceOverrideSince
        return since != 0L && System.currentTimeMillis() - since < SURFACE_OVERRIDE_MAX_MS
    }

    /** Called by the service when the system surface is gone and an app is in front again. */
    fun clearSurfaceOverride() {
        surfaceOverrideSince = 0L
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

    private fun masterEnabled(context: Context) = prefs(context).getBoolean("grayscaler_enabled", true)

    /** True when the system is actually showing grayscale: the filter is on and set to monochrome. */
    fun isSystemGrayscaleOn(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, 0) == MainService.ON &&
            Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, MODE_NONE) == MainService.MONOCHROME
    }

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
        clearSurfaceOverride()
        invalidateInternal(context, "reset:$reason", reassert = true, fromToggle = true)
    }

    private fun invalidateInternal(
        context: Context,
        reason: String,
        reassert: Boolean,
        fromToggle: Boolean = false,
    ) {
        clearCaches()
        if (!fromToggle && isSurfaceOverrideActive()) {
            // Caches are still dropped above, so edits made in the app apply once the surface closes.
            Log.d(LOG_TAG, "invalidate held: a system surface is in control reason=$reason")
            return
        }
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // If the outcome is unambiguous regardless of which app is foreground, apply directly.
        // This handles cases where there is no last meaningful foreground (e.g. lockscreen).
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            applyToSystem(context, Decision.DISABLE, "$reason:paused", fromToggle = fromToggle)
            return
        }
        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            applyToSystem(context, Decision.DISABLE, "$reason:disabled", fromToggle = fromToggle)
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
        webShortcutStore = null
    }

    /** The user's web shortcut rules, loaded once and dropped with the other cached stores. */
    fun webShortcutStore(context: Context): WebShortcutStore =
        webShortcutStore ?: WebShortcutStore(context).also { it.load(); webShortcutStore = it }

    private fun imePackages(context: Context): Set<String> {
        val key = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        } catch (_: Throwable) {
            null
        }
        val now = System.currentTimeMillis()
        val stale = if (key != null) key != imeKey else now - imeCheckedAt > IME_FALLBACK_TTL_MS
        if (stale || imeCheckedAt == 0L) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.enabledInputMethodList.mapTo(HashSet()) { it.packageName }
            imeKey = key
            imeCheckedAt = now
        }
        return imePackages
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
        if (pkg in imePackages(context)) return Decision.SKIP

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
     * @param reassert when true and grayscale is already showing, force a real change and back, so a
     *   colour transform the OS dropped is re-applied. Re-writing the same value is a no-op for the
     *   system's colour observer.
     * @param fromToggle true only for the global on/off switch. While Grayscaler+ is switched off
     *   every other caller is ignored: a disabled Grayscaler+ does not own the setting, so it must
     *   not undo grayscale that the user, Bedtime mode or another app turned on. The toggle itself
     *   still writes once, to clear our own filter when switching off.
     */
    fun applyToSystem(
        context: Context,
        decision: Decision,
        reason: String = "unspecified",
        reassert: Boolean = false,
        fromToggle: Boolean = false,
    ) {
        if (decision == Decision.SKIP) return
        val master = masterEnabled(context)
        if (!master && !fromToggle) {
            if (reason != lastSkipReason) {
                lastSkipReason = reason
                Log.d(LOG_TAG, "apply skipped (Grayscaler+ is off) decision=$decision reason=$reason")
            }
            return
        }
        lastSkipReason = null
        val prev = lastDecision
        val gen = ++generation
        val resolver = context.contentResolver
        try {
            lastWriteAtMs = System.currentTimeMillis()
            val curEnabled = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, -1)
            val curMode = Settings.Secure.getInt(resolver, MainService.DISPLAY_DALTONIZER, MODE_NONE)
            if (decision == Decision.ENABLE) {
                val alreadyGray = curEnabled == MainService.ON && curMode == MainService.MONOCHROME
                if (alreadyGray && shouldReassert(context, reassert)) {
                    // The two writes must be separated in time: the system observer reads the value
                    // when it dispatches, so OFF immediately followed by ON is seen as "still ON".
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
                    lastDecision = decision
                    Log.i(LOG_TAG, "apply decision=ENABLE reason=$reason prev=$prev reassert=pending")
                    GrayscaleLog.log(context, "apply", "reassert OFF->ON scheduled reason=$reason")
                    val appContext = context.applicationContext
                    mainHandler.postDelayed({
                        // Any apply in the gap (a DISABLE for a colour app, or a plain ENABLE that
                        // already wrote ON) supersedes this one.
                        if (generation != gen) return@postDelayed
                        try {
                            lastWriteAtMs = System.currentTimeMillis()
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
                // Only write what differs: a same-value write is still a binder call.
                if (curMode != MainService.MONOCHROME) {
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
                }
                if (curEnabled != MainService.ON) {
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.ON)
                }
            } else {
                if (curEnabled != MainService.OFF) {
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER_ENABLED, MainService.OFF)
                }
                if (curMode == MODE_NONE) {
                    Settings.Secure.putInt(resolver, MainService.DISPLAY_DALTONIZER, MainService.MONOCHROME)
                }
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
            val consistent = if (decision == Decision.ENABLE) {
                actualEnabled == MainService.ON && actualMode == MainService.MONOCHROME
            } else {
                actualEnabled == MainService.OFF
            }
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

    /**
     * Called by the service's settings observer whenever the system grayscale value changes. If the
     * new value disagrees with our last decision and we did not just write it, another app changed
     * it. This only records and logs the fact. It deliberately does not re-apply: while the other app
     * is still active that would just add more flips, and each flip is expensive on some devices.
     */
    fun onSystemValueChanged(context: Context) {
        val belief = lastDecision ?: return
        if (!masterEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - lastWriteAtMs < OWN_WRITE_WINDOW_MS) return
        val gray = try {
            isSystemGrayscaleOn(context)
        } catch (_: Throwable) {
            return
        }
        if (gray == (belief == Decision.ENABLE)) return
        lastExternalChangeAtMs = now
        val line = "external change grayscale=$gray belief=$belief (not written by Grayscaler+)"
        Log.w(LOG_TAG, line)
        GrayscaleLog.log(context, "external", line)
    }

    /** True when the system Secure value agrees with what this app last decided. */
    fun systemMatchesBelief(context: Context): Boolean {
        val belief = lastDecision ?: return true
        if (!masterEnabled(context)) return true
        return try {
            isSystemGrayscaleOn(context) == (belief == Decision.ENABLE)
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
            append(" display_color_mode=").append(DisplayColorMode.current(context))
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
        surfaceOverrideSince = System.currentTimeMillis()
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
        if (pkg == lastMeaningfulPkg && className == lastMeaningfulClassName) return
        lastMeaningfulPkg = pkg
        lastMeaningfulClassName = className
        context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_MEANINGFUL_PKG, pkg)
            .putString(PREF_LAST_MEANINGFUL_CLASS, className)
            .apply()
    }
}
