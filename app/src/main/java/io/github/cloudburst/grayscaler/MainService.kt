package io.github.cloudburst.grayscaler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.cloudburst.grayscaler.ui.theme.GrayscalerTheme

class MainService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var countdownOverlay: PauseCountdownOverlayManager? = null
    private var overlayPresentationKey by mutableIntStateOf(0)
    // True only while the persistent pause popup is on screen; its once-a-second clock stops otherwise.
    private var overlayShown by mutableStateOf(false)

    // Event types currently requested from the system (see updateEventSubscription).
    private var subscribedEventTypes = -1

    private val graceHandler = Handler(Looper.getMainLooper())
    private val graceRunnable = Runnable {
        sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_END).apply { setPackage(packageName) })
    }

    private val launcherPackages: Set<String> by lazy {
        val set = mutableSetOf(packageName)
        set.addAll(KNOWN_LAUNCHER_PACKAGES)
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(homeIntent, 0)
                .forEach { set.add(it.activityInfo.packageName) }
        } catch (_: Exception) {}
        set
    }

    private inner class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun start() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    // Set when a SystemUI surface (recents / notification shade / lockscreen / power menu) drove a
    // grayscale change and we must re-derive state once that surface is dismissed. Many devices
    // return to the same app without emitting a fresh TYPE_WINDOW_STATE_CHANGED, which otherwise
    // strands the screen in color while the master switch still reads "on".
    private var suppressedBySystemUi = false

    // One pending re-check for a window event the stale-window guard dropped. If the dropped
    // package really is in front a moment later, it is evaluated then; otherwise nothing happens.
    private var staleRecheckRunnable: Runnable? = null

    // Package name of the browser currently in foreground, null if not a browser
    private var currentBrowserPkg: String? = null
    private var lastUrlCheckTime = 0L
    private var experimentalNotificationShadeActive = false
    private var experimentalNotificationShadeLastConfirmAt = 0L
    private var lastWhatsAppProbeMs = 0L

    private lateinit var whatsAppScanner: WhatsAppReadIndicatorScanner
    private lateinit var whatsAppOverlay: WhatsAppReadIndicatorOverlayManager
    private val whatsAppScanHandler = Handler(Looper.getMainLooper())
    private val whatsAppScanRunnable = Runnable { performWhatsAppScan() }
    private val whatsAppRetryRunnable = Runnable { performWhatsAppScan() }
    private var isWhatsAppForegrounded = false
    private var isWhatsAppObscuredBySystemUi = false
    // Tracks how many empty-scan retries have fired in the current settling window. Reset on
    // non-empty result, enter/exit WhatsApp, soft-hide, or — critically — a fresh content-change
    // *only* when no chain is currently running (count == 0) or the chain has exhausted. A fresh
    // content-change mid-chain does NOT reset, so animation-induced re-events can't nuke the
    // retry budget before the tree settles. See [scheduleWhatsAppScan], [performWhatsAppScan].
    private var whatsAppEmptyRetryCount = 0
    // True iff [whatsAppOverlay] currently has labels painted. Used by the TEXT-only content-change
    // filter (skip rescans triggered by the user's own typing while labels are already up) and by
    // the heartbeat (kick a rescan if we somehow have no labels while in a healthy WhatsApp state).
    private var whatsAppOverlayHasLabels = false

    // Every label from the last scan. What is drawn is this minus the ones another window covers
    // (a floating overlay, a bubble, the keyboard, our own pause popup), recomputed whenever the
    // set of windows on screen changes, without rescanning.
    private var whatsAppAllLabels: List<WhatsAppReadIndicatorScanner.Label> = emptyList()
    private var whatsAppCoverSummary = ""
    private val whatsAppCoverRunnable = Runnable { applyWindowCovers() }
    private var whatsAppHeartbeatTick = 0

    // Set when System UI (a heads-up banner, the shade, the power menu) took the labels down while
    // WhatsApp was in front. WhatsApp never loses focus in that case, so no WhatsApp window event
    // arrives afterwards; the heartbeat keeps ticking for a while and puts the labels back as soon
    // as WhatsApp is the active window again.
    private var whatsAppAwaitingReturn = false
    private var whatsAppAwaitTicksLeft = 0

    // Ends a timed pause on time. The AlarmManager alarm that also ends it is inexact and can fire
    // late; this service is alive whenever the screen is on, so it does the punctual part and the
    // alarm stays as the backup for when the service is not running.
    private val pauseEndRunnable = Runnable {
        val until = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE).getLong("pause_until", 0L)
        if (until != 0L && until <= System.currentTimeMillis() + PAUSE_END_SLACK_MS) {
            sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_END).apply { setPackage(packageName) })
        }
    }
    private var lastWhatsAppHealMs = 0L
    private val whatsAppHeartbeatRunnable = Runnable { runWhatsAppHeartbeat() }

    private val browserPackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    clearExperimentalNotificationShade("screen_off")
                    // Tear the WhatsApp read-indicator overlay down synchronously on screen-off
                    // rather than waiting for the next TYPE_WINDOW_STATE_CHANGED (which can lag by
                    // a couple of seconds after the user presses power).
                    exitWhatsAppActiveState()
                    GrayscaleStateManager.applySystemEventMode(
                        context,
                        prefs.getString("lockscreen_mode", "ignore") ?: "ignore",
                        "screen_off"
                    )
                }
                Intent.ACTION_USER_PRESENT -> {
                    clearExperimentalNotificationShade("user_present")
                    GrayscaleStateManager.invalidate(context, "user_present")
                }
            }
        }
    }

    // Watches the system grayscale value so writes made by another app (e.g. a second app with its
    // own grayscale feature) show up in the log instead of looking like random flicker.
    private val daltonizerObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            GrayscaleStateManager.onSystemValueChanged(this@MainService)
        }
    }

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SHOW_PAUSE_OVERLAY) showPersistentOverlay()
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            "persistent_overlay_mode" -> {
                if (prefs.getBoolean(key, false)) registerPersistentOverlay() else unregisterPersistentOverlay()
            }
            "pause_until" -> {
                val pauseUntil = prefs.getLong("pause_until", 0L)
                armPauseEndTimer(pauseUntil)
                if (pauseUntil > System.currentTimeMillis()) {
                    countdownOverlay?.show(pauseUntil)
                } else if (prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") != "session") {
                    countdownOverlay?.hide()
                }
                reevaluateWhatsAppActiveState()
            }
            ScheduleReceiver.PREF_PAUSE_TYPE -> {
                val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"
                when (pauseType) {
                    "session" -> {
                        val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, 0L)
                        countdownOverlay?.showSession(startMs)
                    }
                    // Leaving "session" by any route (pause end, a new timed pause, the global
                    // toggle) must drop the grace timer, or it later ends an unrelated pause.
                    "timed" -> {
                        graceHandler.removeCallbacks(graceRunnable)
                        countdownOverlay?.hide()
                    }
                    "pending_session" -> {
                        graceHandler.removeCallbacks(graceRunnable)
                        countdownOverlay?.hide()
                    }
                }
                reevaluateWhatsAppActiveState()
            }
            "grayscaler_enabled",
            "session_pause_pkg",
            PREF_WHATSAPP_READ_INDICATOR_ENABLED -> reevaluateWhatsAppActiveState()
            PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED -> updateEventSubscription()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        countdownOverlay = PauseCountdownOverlayManager(this, windowManager)
        whatsAppScanner = WhatsAppReadIndicatorScanner(this)
        whatsAppOverlay = WhatsAppReadIndicatorOverlayManager(this, windowManager)

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayReceiver, IntentFilter(ACTION_SHOW_PAUSE_OVERLAY), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(overlayReceiver, IntentFilter(ACTION_SHOW_PAUSE_OVERLAY))
        }

        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        // Both keys: with seamless switching the enabled flag stays on and the mode key carries the
        // gray/colour state, so another app's write could land on either one.
        for (key in listOf(DISPLAY_DALTONIZER_ENABLED, DISPLAY_DALTONIZER)) {
            contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor(key),
                false,
                daltonizerObserver
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("persistent_overlay_mode", false)) registerPersistentOverlay()
        val pauseUntil = prefs.getLong("pause_until", 0L)
        val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"
        armPauseEndTimer(pauseUntil)
        when {
            pauseUntil > System.currentTimeMillis() -> countdownOverlay?.show(pauseUntil)
            pauseType == "session" -> {
                val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, 0L)
                countdownOverlay?.showSession(startMs)
            }
        }
        // Re-assert grayscale on (re)connect. After a reboot, the service being toggled in
        // Accessibility settings, or an OS-initiated restart, the system Secure value may no longer
        // match the master switch and nothing else re-applies it until the next window event.
        instance = this
        updateEventSubscription()
        GrayscaleLog.log(this, "serviceConnected", "reconciling grayscale state")
        GrayscaleStateManager.invalidate(this, "service_connected")
    }

    /**
     * Drops every piece of in-memory tracking state so the next event starts clean. Called by
     * [GrayscalerToggleCoordinator] as part of the global on/off reset. Pref listeners cannot do
     * this job: they only fire when a value actually changes.
     */
    fun resetTransientState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            graceHandler.post { resetTransientState() }
            return
        }
        graceHandler.removeCallbacks(graceRunnable)
        graceHandler.removeCallbacks(pauseEndRunnable)
        cancelStaleRecheck()
        suppressedBySystemUi = false
        experimentalNotificationShadeActive = false
        experimentalNotificationShadeLastConfirmAt = 0L
        currentBrowserPkg = null
        lastUrlCheckTime = 0L
        whatsAppEmptyRetryCount = 0
        lastWhatsAppHealMs = 0L
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val paused = prefs.getLong("pause_until", 0L) > System.currentTimeMillis() ||
            prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") == "session"
        if (!paused) countdownOverlay?.hide()
        reevaluateWhatsAppActiveState()
        updateEventSubscription()
        GrayscaleLog.log(this, "reset", "transient service state cleared")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        graceHandler.removeCallbacks(graceRunnable)
        graceHandler.removeCallbacks(pauseEndRunnable)
        cancelStaleRecheck()
        whatsAppScanHandler.removeCallbacks(whatsAppScanRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppHeartbeatRunnable)
        contentResolver.unregisterContentObserver(daltonizerObserver)
        unregisterReceiver(screenReceiver)
        unregisterReceiver(overlayReceiver)
        getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        unregisterPersistentOverlay()
        countdownOverlay?.hide()
        countdownOverlay = null
        if (::whatsAppOverlay.isInitialized) whatsAppOverlay.hide()
        isWhatsAppForegrounded = false
        super.onDestroy()
    }

    private fun registerPersistentOverlay() {
        if (overlayView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // The popup pads itself for the keyboard; resize (not pan) avoids shifting it twice.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        overlayParams = params
        val lifecycleOwner = OverlayLifecycleOwner().also {
            it.start()
            overlayLifecycleOwner = it
        }
        val view = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                GrayscalerTheme {
                    PauseOverlayContent(
                        presentationKey = overlayPresentationKey,
                        active = overlayShown,
                        onDismiss = { hidePersistentOverlay() },
                        onOpenApp = {
                            startActivity(
                                Intent(this@MainService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                            )
                            hidePersistentOverlay()
                        }
                    )
                }
            }
        }
        overlayView = view
        windowManager.addView(view, params)
    }

    private fun unregisterPersistentOverlay() {
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        overlayParams = null
    }

    private fun showPersistentOverlay() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val wasVisible = view.visibility == View.VISIBLE
        params.flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        windowManager.updateViewLayout(view, params)
        view.visibility = View.VISIBLE
        overlayShown = true
        if (!wasVisible) {
            overlayPresentationKey += 1
        }
        view.post { view.requestFocus() }
    }

    private fun hidePersistentOverlay() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        view.visibility = View.GONE
        overlayShown = false
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(view, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Suppress the WhatsApp read-indicator overlay while SystemUI (notification shade, quick
        // settings, lockscreen) is on top of WhatsApp — and bring it back the moment the
        // obstruction clears. Runs on every event so we catch TYPE_WINDOWS_CHANGED that fires
        // when the shade is dragged down (which would otherwise fall through).
        // Both per-event checks below need the active window's package. Resolve it at most once per
        // event, and only if one of them will actually use it.
        val activeRootPkg: String? =
            if (isWhatsAppForegrounded || suppressedBySystemUi) activeRootPackage() else null
        checkWhatsAppObscuredTransition(activeRootPkg)

        // Another window appeared, moved or went away while labels are up: re-check which labels it
        // covers. Coalesced, because opening a window produces a short burst of these events.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            isWhatsAppForegrounded && whatsAppAllLabels.isNotEmpty()
        ) {
            whatsAppScanHandler.removeCallbacks(whatsAppCoverRunnable)
            whatsAppScanHandler.postDelayed(whatsAppCoverRunnable, WHATSAPP_COVER_COALESCE_MS)
        }

        // Restore grayscale once a SystemUI surface that disabled it is dismissed. Runs on every
        // event (including TYPE_WINDOWS_CHANGED, which fires on shade/power-menu dismiss when no
        // fresh TYPE_WINDOW_STATE_CHANGED does). Cheap: bails immediately unless we're suppressed.
        maybeRecoverFromSystemUiSuppression(activeRootPkg)

        // Inline reply typing detection — fires when an EditText inside a notification gets focus
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != "com.android.systemui") return
            val className = event.className?.toString() ?: ""
            if (!INLINE_REPLY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            GrayscaleStateManager.applySystemEventMode(
                this,
                prefs.getString("inline_reply_mode", "ignore") ?: "ignore",
                "inline_reply"
            )
            return
        }

        // WhatsApp scroll signal — repositions chips as message rows move past the viewport. The
        // content-change branch below would eventually catch this, but scroll events fire ahead of
        // the full subtree mutation so reacting here keeps chip tracking tight on long lists.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val eventPkg = event.packageName?.toString()
            if (eventPkg == WHATSAPP_PKG && isWhatsAppForegrounded
                && !isWhatsAppObscuredBySystemUi
            ) {
                val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean(PREF_WHATSAPP_READ_INDICATOR_ENABLED, false)) {
                    scheduleWhatsAppScan()
                }
            }
            return
        }

        // URL-based web shortcut matching — debounced, only when a browser is in foreground
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            if (handleExperimentalNotificationShadeEvent(event, prefs)) return
            val eventPkg = event.packageName?.toString()
            if (eventPkg == WHATSAPP_PKG
                && prefs.getBoolean(PREF_WHATSAPP_READ_INDICATOR_ENABLED, false)
            ) {
                // Unit 2B — content-change self-heal: if we missed WhatsApp's WINDOW_STATE_CHANGED
                // on re-entry (root-window guard discarded it as "stale"), the foregrounded flag is
                // stuck false and no scan will ever fire. Use this content-change as proof of life
                // and re-evaluate against the current root package.
                if (!isWhatsAppForegrounded) reevaluateWhatsAppActiveState()
                if (isWhatsAppForegrounded && !isWhatsAppObscuredBySystemUi) {
                    // Unit 3 — skip rescans triggered purely by the user's typing. A keystroke into
                    // the message input fires CONTENT_CHANGE_TYPE_TEXT alone; any structural change
                    // (new message, status flip, panel open) includes other flags (e.g. SUBTREE)
                    // and falls through to the normal rescan path.
                    val types = event.contentChangeTypes
                    val onlyTextChange = types != 0 &&
                        (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0 &&
                        (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT.inv()) == 0
                    if (onlyTextChange && whatsAppOverlayHasLabels) return
                    // Content moved — wipe stale labels and re-scan once events settle.
                    clearWhatsAppLabels()
                    scheduleWhatsAppScan()
                    return
                }
            }
            val browserPkg = currentBrowserPkg ?: return
            if (eventPkg != browserPkg) return
            val now = System.currentTimeMillis()
            if (now - lastUrlCheckTime < URL_CHECK_DEBOUNCE_MS) return
            lastUrlCheckTime = now
            applyWebShortcutRule(browserPkg)
            return
        }

        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (handleExperimentalNotificationShadeEvent(event, prefs)) return

        if (event?.eventType != TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. Diagnostic capture — runs independently of master switch
        if (prefs.getBoolean("per_app_views_diagnostic", false)) {
            val isNoise = pkg in OverlayIgnoreStore.SYSTEM_IGNORES
                || RECENTS_KEYWORDS.any { className.contains(it, ignoreCase = true) }
                || className.contains("Launcher", ignoreCase = true)
            if (!isNoise) {
                Log.d(TAG, "window: pkg=$pkg cls=$className")
                prefs.edit().putString("last_window_class", "$pkg\n$className").apply()
            }
        }

        // 2. System UI behavior — recents check is package-agnostic to handle OEM launchers
        //    (e.g. com.android.quickstep on Nothing OS, not just com.android.systemui)
        val isRecents = RECENTS_KEYWORDS.any { className.contains(it, ignoreCase = true) }
        val isNotifShade = pkg == "com.android.systemui" &&
            NOTIF_KEYWORDS.any { className.contains(it, ignoreCase = true) }
        val isLockscreen = pkg == "com.android.systemui" &&
            LOCKSCREEN_KEYWORDS.any { className.contains(it, ignoreCase = true) }
        val isPowerMenu = pkg == "com.android.systemui" &&
            POWER_MENU_KEYWORDS.any { className.contains(it, ignoreCase = true) }
        when {
            isRecents -> {
                enterSystemUiSurface()
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("app_switcher_mode", "ignore") ?: "ignore",
                    "recents"
                )
                markSuppressedBySystemUi("recents", className)
                return
            }
            isNotifShade -> {
                enterSystemUiSurface()
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("notification_center_mode", "ignore") ?: "ignore",
                    "shade"
                )
                markSuppressedBySystemUi("shade", className)
                return
            }
            isLockscreen -> {
                enterSystemUiSurface()
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("lockscreen_mode", "ignore") ?: "ignore",
                    "lockscreen"
                )
                markSuppressedBySystemUi("lockscreen", className)
                return
            }
            isPowerMenu -> {
                enterSystemUiSurface()
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("power_menu_mode", "disable") ?: "disable",
                    "power_menu"
                )
                markSuppressedBySystemUi("power_menu", className)
                return
            }
            pkg == "com.android.systemui" -> {
                enterSystemUiSurface()
                return
            }
        }

        // 3. Discard stale events from windows being detached — only trust this event if
        //    rootInActiveWindow confirms the same package is actually in the foreground.
        //    Null rootInActiveWindow means we can't confirm either way, so we let it through.
        //    A dropped event is not forgotten: on re-entry from the app switcher the root briefly
        //    still points at recents, and for that package this is the only event we will get. So
        //    look again shortly and evaluate it then if it really is in front. This replaces the
        //    old "always trust WhatsApp" bypass, which evaluated (and remembered) WhatsApp even
        //    while another app was on screen, and it heals dropped events for every other app too.
        val rootPkg = activeRootPkg ?: activeRootPackage()
        if (rootPkg != null && rootPkg != pkg) {
            scheduleStaleRecheck(pkg, className)
            return
        }

        // 4. Apply per-app logic. GrayscaleStateManager is responsible for deciding whether this
        //    foreground is meaningful enough to become the restore/invalidation context.
        cancelStaleRecheck()
        handleForegroundApp(pkg, className)
    }

    private fun enterSystemUiSurface() {
        cancelStaleRecheck()
        exitWhatsAppActiveState(keepWatching = true)
    }

    private fun armPauseEndTimer(pauseUntil: Long) {
        graceHandler.removeCallbacks(pauseEndRunnable)
        val delay = pauseUntil - System.currentTimeMillis()
        if (delay > 0) graceHandler.postDelayed(pauseEndRunnable, delay)
    }

    private fun scheduleStaleRecheck(pkg: String, className: String) {
        cancelStaleRecheck()
        val runnable = Runnable {
            staleRecheckRunnable = null
            if (activeRootPackage() == pkg) {
                GrayscaleLog.log(this, "stale_recheck", "accepted late pkg=$pkg")
                handleForegroundApp(pkg, className)
            }
        }
        staleRecheckRunnable = runnable
        graceHandler.postDelayed(runnable, STALE_RECHECK_DELAY_MS)
    }

    private fun cancelStaleRecheck() {
        staleRecheckRunnable?.let { graceHandler.removeCallbacks(it) }
        staleRecheckRunnable = null
    }

    private fun checkSessionPauseState(pkg: String) {
        // Our own windows (the pause popup, the app itself) are not "leaving the session app".
        // Without this, opening the popup inside the session app armed the grace timer.
        if (pkg == packageName) return
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        when (prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed") {
            "pending_session" -> {
                if (pkg !in launcherPackages) {
                    sendBroadcast(Intent(ScheduleReceiver.ACTION_APPLY_SESSION_PAUSE).apply {
                        setPackage(packageName)
                        putExtra(ScheduleReceiver.EXTRA_SESSION_PKG, pkg)
                    })
                }
            }
            "session" -> {
                val sessionPkg = prefs.getString(ScheduleReceiver.PREF_SESSION_PAUSE_PKG, "") ?: ""
                if (pkg == sessionPkg) {
                    graceHandler.removeCallbacks(graceRunnable)
                    val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, 0L)
                    countdownOverlay?.showSession(startMs)
                } else {
                    val gracePeriodMs = prefs.getLong(
                        ScheduleReceiver.PREF_SESSION_GRACE_PERIOD_MS,
                        ScheduleReceiver.SESSION_GRACE_PERIOD_DEFAULT_MS
                    )
                    graceHandler.removeCallbacks(graceRunnable)
                    graceHandler.postDelayed(graceRunnable, gracePeriodMs)
                    countdownOverlay?.hide()
                }
            }
        }
    }

    private fun handleForegroundApp(pkg: String, className: String) {
        checkSessionPauseState(pkg)
        val decision = GrayscaleStateManager.evaluate(pkg, className, this)
        GrayscaleStateManager.applyToSystem(this, decision, "foreground:$pkg")
        if (decision == GrayscaleStateManager.Decision.SKIP) return
        // A real foreground app just re-applied grayscale state, so any pending SystemUI-dismissal
        // recovery is satisfied — drop the flag so it can never linger across unrelated events.
        suppressedBySystemUi = false

        // Track browser state; immediately probe URL if this is a browser coming to foreground
        if (pkg in browserPackages) {
            currentBrowserPkg = pkg
            lastUrlCheckTime = System.currentTimeMillis()
            applyWebShortcutRule(pkg)
        } else {
            currentBrowserPkg = null
        }

        maybeRunWhatsAppColorProbe(pkg)
        updateWhatsAppActiveState(pkg)
        updateEventSubscription()
    }

    /**
     * Content-change and scroll events arrive from every app, dozens per second while anything
     * scrolls, and each one wakes this process. Only three things use them: the WhatsApp labels,
     * the browser URL rules and the experimental shade detection. So request them only while one
     * of those needs them. The window-state event that makes one of them start needing them always
     * comes first, and that event type is always subscribed, so nothing is missed.
     */
    private fun updateEventSubscription() {
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val whatsApp = prefs.getBoolean(PREF_WHATSAPP_READ_INDICATOR_ENABLED, false) &&
            (isWhatsAppForegrounded || whatsAppAwaitingReturn)
        val shade = prefs.getBoolean(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED, false)
        var mask = BASE_EVENT_TYPES
        if (whatsApp || shade || currentBrowserPkg != null) {
            mask = mask or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        if (whatsApp) mask = mask or AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (mask == subscribedEventTypes) return
        try {
            val info = serviceInfo ?: return
            info.eventTypes = mask
            serviceInfo = info
            subscribedEventTypes = mask
            Log.d(TAG, "event subscription: content=${mask and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0} " +
                "scroll=${mask and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0}")
        } catch (_: Throwable) {
            // Not connected (yet, or any more); the next call retries.
        }
    }

    private fun updateWhatsAppActiveState(pkg: String) {
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val featureEnabled = prefs.getBoolean(PREF_WHATSAPP_READ_INDICATOR_ENABLED, false)
        val shouldBeActive = featureEnabled &&
            pkg == WHATSAPP_PKG &&
            isGrayscalerActiveForWhatsApp(prefs)
        if (shouldBeActive) {
            val wasActive = isWhatsAppForegrounded
            isWhatsAppForegrounded = true
            scheduleWhatsAppScan()
            if (!wasActive) {
                // Kick the heartbeat backstop. Repost-self stops itself once WhatsApp is gone.
                whatsAppScanHandler.removeCallbacks(whatsAppHeartbeatRunnable)
                whatsAppScanHandler.postDelayed(whatsAppHeartbeatRunnable, WHATSAPP_HEARTBEAT_INTERVAL_MS)
            }
            updateEventSubscription()
        } else {
            exitWhatsAppActiveState()
        }
    }

    /**
     * Returns true if the Grayscaler is currently in a state where the WhatsApp read-indicator
     * feature should run. Mirrors the conditions used by [GrayscaleStateManager.evaluate]:
     *  - Master "grayscaler_enabled" switch is on
     *  - No timed pause is in effect
     *  - No session pause is in effect for WhatsApp specifically
     * Rationale: if the user has explicitly turned grayscale off, there's no color confusion to
     * resolve and the feature would just be drawing useless chips on top of a full-color screen.
     */
    private fun isGrayscalerActiveForWhatsApp(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean("grayscaler_enabled", true)) return false
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) return false
        if (prefs.getString("pause_type", "timed") == "session" &&
            prefs.getString("session_pause_pkg", "") == WHATSAPP_PKG
        ) return false
        return true
    }

    /**
     * Re-evaluates the WhatsApp feature gate using the current root-window package. Called when
     * grayscale-affecting prefs change so the user gets instant feedback (overlay vanishes when
     * they hit pause; reappears when they resume) without waiting for the next window event.
     */
    private fun reevaluateWhatsAppActiveState() {
        val rootPkg = try {
            rootInActiveWindow?.let {
                val pkg = it.packageName?.toString()
                it.recycle()
                pkg
            }
        } catch (_: Throwable) {
            null
        } ?: return
        updateWhatsAppActiveState(rootPkg)
    }

    /**
     * @param keepWatching true when System UI, not another app, is the reason for leaving. The
     *   labels still come down, but the heartbeat keeps running so they return by themselves.
     */
    private fun exitWhatsAppActiveState(keepWatching: Boolean = false) {
        if (!isWhatsAppForegrounded && !::whatsAppOverlay.isInitialized) return
        val wasWatching = isWhatsAppForegrounded || whatsAppAwaitingReturn
        isWhatsAppForegrounded = false
        isWhatsAppObscuredBySystemUi = false
        whatsAppEmptyRetryCount = 0
        whatsAppOverlayHasLabels = false
        whatsAppAllLabels = emptyList()
        whatsAppScanHandler.removeCallbacks(whatsAppScanRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppHeartbeatRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppCoverRunnable)
        if (keepWatching && wasWatching) {
            whatsAppAwaitingReturn = true
            whatsAppAwaitTicksLeft = WHATSAPP_AWAIT_RETURN_TICKS
            whatsAppScanHandler.postDelayed(whatsAppHeartbeatRunnable, WHATSAPP_HEARTBEAT_INTERVAL_MS)
        } else {
            whatsAppAwaitingReturn = false
            if (::whatsAppScanner.isInitialized) whatsAppScanner.forgetLastScan()
        }
        if (::whatsAppOverlay.isInitialized) whatsAppOverlay.hide()
        updateEventSubscription()
    }

    /**
     * Soft-hide: pull the labels but keep [isWhatsAppForegrounded] set. Used when the user pulls
     * down the notification shade or similar System UI overlays appear on top of WhatsApp — we
     * want labels to disappear instantly and come back the moment the obstruction clears, without
     * waiting for a fresh WhatsApp TYPE_WINDOW_STATE_CHANGED.
     */
    private fun softHideWhatsAppOverlay() {
        whatsAppScanHandler.removeCallbacks(whatsAppScanRunnable)
        whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
        whatsAppEmptyRetryCount = 0
        clearWhatsAppLabels()
    }

    private fun clearWhatsAppLabels() {
        whatsAppAllLabels = emptyList()
        whatsAppOverlayHasLabels = false
        whatsAppScanHandler.removeCallbacks(whatsAppCoverRunnable)
        if (::whatsAppOverlay.isInitialized) whatsAppOverlay.clear()
    }

    /**
     * Draws the labels from the last scan except those that another window covers. Android reports
     * every touchable window with its z-order (layer), so any window above WhatsApp's is on top of
     * it: an app overlay, a chat bubble, a heads-up banner, the keyboard, our own pause popup. A
     * label whose centre lies inside such a window is hidden, the rest stay. Our own label window is
     * never in the list because it does not take touches.
     */
    private fun applyWindowCovers() {
        if (!isWhatsAppForegrounded || isWhatsAppObscuredBySystemUi) return
        val all = whatsAppAllLabels
        if (all.isEmpty()) return
        val covers = windowsAboveScannedWindow()
        val visible = when {
            covers == null -> emptyList() // WhatsApp's window is not reported: fully covered
            covers.isEmpty() -> all
            else -> all.filter { label ->
                val x = label.bounds.centerX()
                val y = label.bounds.centerY()
                covers.none { it.contains(x, y) }
            }
        }
        val summary = "covers=${covers?.size ?: "all"} hidden=${all.size - visible.size}/${all.size}"
        if (summary != whatsAppCoverSummary) {
            whatsAppCoverSummary = summary
            Log.d(TAG, "wa labels: $summary")
        }
        if (visible.isEmpty()) whatsAppOverlay.clear() else whatsAppOverlay.setLabels(visible)
    }

    /**
     * Screen bounds of every window drawn above the window the last scan read. Empty when nothing is
     * above it or the window list is unavailable (then nothing is hidden, as before); null when that
     * window is no longer reported, which Android does when something covers it completely.
     */
    private fun windowsAboveScannedWindow(): List<Rect>? {
        val baseId = whatsAppScanner.lastWindowId
        if (baseId < 0) return emptyList()
        val list = try {
            windows
        } catch (_: Throwable) {
            return emptyList()
        }
        if (list.isEmpty()) return emptyList()
        val base = list.firstOrNull { it.id == baseId } ?: return null
        return list
            .filter { it.id != baseId && it.layer > base.layer }
            .map { window -> Rect().also { window.getBoundsInScreen(it) } }
            .filter { !it.isEmpty }
    }

    /**
     * 1 Hz backstop for the event-driven scan pipeline. Catches the two failure modes the event
     * stream can hide:
     *  1. WhatsApp came back to foreground without emitting a usable WINDOW_STATE_CHANGED *and*
     *     without emitting a useful content-change (so [Unit 2] couldn't self-heal either).
     *  2. The server flipped a status (sent → read) with no accompanying accessibility event, so
     *     no rescan fired and the overlay is stuck on the previous status.
     * Reposts itself only while WhatsApp is on screen; idles to zero otherwise.
     */
    private fun runWhatsAppHeartbeat() {
        val rootPkg = activeRootPackage()
        if (rootPkg == WHATSAPP_PKG && !isWhatsAppForegrounded) {
            // Missed transition recovery — updateWhatsAppActiveState reschedules the heartbeat
            // itself on the inactive→active edge, so return to avoid double-posting.
            whatsAppAwaitingReturn = false
            updateWhatsAppActiveState(WHATSAPP_PKG)
            return
        }
        // Still waiting for System UI to get out of the way: keep ticking only while the thing in
        // front is System UI (or our own popup, or nothing identifiable yet), and only for so long.
        val stillWaiting = whatsAppAwaitingReturn &&
            --whatsAppAwaitTicksLeft > 0 &&
            (rootPkg == null || rootPkg == "com.android.systemui" || rootPkg == packageName)
        if (!stillWaiting && whatsAppAwaitingReturn) {
            whatsAppAwaitingReturn = false
            updateEventSubscription()
        }
        whatsAppHeartbeatTick++
        // Every scan costs a screenshot. Content changes and scrolls already schedule their own
        // scans, so an idle screen with nothing to label (e.g. a chat list with no outgoing
        // messages) is only re-probed every few ticks instead of once a second, forever.
        if (isWhatsAppForegrounded && !isWhatsAppObscuredBySystemUi && !whatsAppOverlayHasLabels &&
            whatsAppHeartbeatTick % WHATSAPP_HEARTBEAT_IDLE_SCAN_EVERY == 0
        ) {
            scheduleWhatsAppScan()
        }
        healGrayscaleIfDrifted()
        if (isWhatsAppForegrounded || rootPkg == WHATSAPP_PKG || stillWaiting) {
            whatsAppScanHandler.postDelayed(whatsAppHeartbeatRunnable, WHATSAPP_HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * Logic-level self heal while WhatsApp is on screen: if the system Secure value no longer
     * matches what we last decided, re-derive and re-apply. This cannot see the case where the
     * value still reads "on" but the panel shows colour (screenshots are taken before the colour
     * transform) — the global toggle's forced OFF -> ON covers that one.
     */
    private fun healGrayscaleIfDrifted() {
        if (!isWhatsAppForegrounded || isWhatsAppObscuredBySystemUi || suppressedBySystemUi) return
        if (GrayscaleStateManager.systemMatchesBelief(this)) return
        val now = System.currentTimeMillis()
        if (now - lastWhatsAppHealMs < WHATSAPP_HEAL_MIN_INTERVAL_MS) return
        lastWhatsAppHealMs = now
        GrayscaleLog.log(this, "wa_heal", "system value drifted from belief -> invalidate")
        GrayscaleStateManager.invalidate(this, "wa_heal")
    }

    /**
     * Returns the package name of the window currently holding input focus, or null if it can't
     * be determined right now. This is the title-independent signal we rely on to detect when
     * SystemUI (shade, quick settings, lockscreen) or any other surface has been placed on top
     * of WhatsApp — works on every OEM because [AccessibilityWindowInfo.getTitle] is unreliable
     * on stock Android and frequently null on Nothing OS / OneUI / MIUI for the shade window.
     */
    private fun activeRootPackage(): String? {
        return try {
            rootInActiveWindow?.let {
                val pkg = it.packageName?.toString()
                it.recycle()
                pkg
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun markSuppressedBySystemUi(surface: String, className: String) {
        suppressedBySystemUi = true
        GrayscaleLog.log(this, "systemui", "$surface engaged cls=$className")
    }

    /**
     * Restores the correct grayscale state after a SystemUI surface that disabled it is dismissed.
     * Gated on the active root window having returned to the last meaningful app, so we never
     * re-enable while the switcher / power menu / shade is still on top (which would defeat the
     * user's app_switcher_mode / power_menu_mode = "disable" choice).
     */
    private fun maybeRecoverFromSystemUiSuppression(activeRootPkg: String?) {
        if (!suppressedBySystemUi) return
        val active = activeRootPkg ?: return
        if (active == "com.android.systemui" || active == packageName) return
        val target = GrayscaleStateManager.lastMeaningfulForegroundPkg(this) ?: return
        if (active != target) return
        suppressedBySystemUi = false
        GrayscaleLog.log(this, "recover", "systemui dismissed active=$active -> invalidate")
        GrayscaleStateManager.invalidate(this, "systemui_recover")
    }

    private fun checkWhatsAppObscuredTransition(activeRootPkg: String?) {
        if (!isWhatsAppForegrounded) return
        val activePkg = activeRootPkg ?: return  // null = transition in progress, ignore
        // Treat anything not WhatsApp (and not our own overlay surface) as obscuring.
        val obscured = activePkg != WHATSAPP_PKG && activePkg != packageName
        if (obscured == isWhatsAppObscuredBySystemUi) return
        isWhatsAppObscuredBySystemUi = obscured
        writeShadeDebug("obscured=$obscured activePkg=$activePkg")
        if (obscured) {
            softHideWhatsAppOverlay()
        } else {
            scheduleWhatsAppScan()
        }
    }

    private fun writeShadeDebug(reason: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_WHATSAPP_SHADE_DEBUG, "$ts $reason")
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun scheduleWhatsAppScan() {
        if (!isWhatsAppForegrounded || isWhatsAppObscuredBySystemUi) return
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val debounce = prefs.getInt(PREF_WHATSAPP_SCAN_DEBOUNCE_MS, WHATSAPP_SCAN_DEBOUNCE_DEFAULT_MS)
            .coerceAtLeast(WHATSAPP_SCAN_DEBOUNCE_MIN_MS)
        val retryCap = prefs.getInt(PREF_WHATSAPP_RESCAN_RETRY_COUNT, WHATSAPP_RESCAN_RETRY_COUNT_DEFAULT)
            .coerceAtLeast(WHATSAPP_RESCAN_RETRY_COUNT_MIN)
        // Only refresh the retry budget when no chain is running (count == 0) or the prior chain
        // has exhausted (count >= cap). Mid-chain content-changes are the WhatsApp tree still
        // settling — killing the queued retry would defeat multi-shot recovery for the chat →
        // all-chats case, which is exactly when the tree emits a flurry of late content-changes.
        if (whatsAppEmptyRetryCount == 0 || whatsAppEmptyRetryCount >= retryCap) {
            whatsAppEmptyRetryCount = 0
            whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
        }
        // Trailing throttle: never start a scan (= a screenshot) sooner than
        // WHATSAPP_MIN_CAPTURE_INTERVAL_MS after the previous capture. Repeated captures are the
        // chip feature's only system-level side effect, so they are kept to what is needed.
        val sinceCapture = System.currentTimeMillis() - whatsAppScanner.lastCaptureAtMs
        val throttle = (WHATSAPP_MIN_CAPTURE_INTERVAL_MS - sinceCapture).coerceAtLeast(0L)
        whatsAppScanHandler.removeCallbacks(whatsAppScanRunnable)
        whatsAppScanHandler.postDelayed(whatsAppScanRunnable, maxOf(debounce.toLong(), throttle))
    }

    private fun performWhatsAppScan() {
        if (!isWhatsAppForegrounded || isWhatsAppObscuredBySystemUi) return
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val debugDump = prefs.getBoolean(PREF_WHATSAPP_COLOR_PROBE_ENABLED, false)
        whatsAppScanner.scan(debugDump) { labels ->
            if (!isWhatsAppForegrounded) return@scan
            if (labels.isEmpty()) {
                clearWhatsAppLabels()
                // Empty result is often a race against WhatsApp's tree settling — particularly
                // chat → all-chats, where content-changed events stop before the bottom-nav tabs
                // appear in the tree. Allow up to [retryCap] retries spaced by [retry] ms.
                val retryCap = prefs.getInt(PREF_WHATSAPP_RESCAN_RETRY_COUNT, WHATSAPP_RESCAN_RETRY_COUNT_DEFAULT)
                    .coerceAtLeast(WHATSAPP_RESCAN_RETRY_COUNT_MIN)
                if (whatsAppEmptyRetryCount < retryCap) {
                    whatsAppEmptyRetryCount++
                    val retry = prefs.getInt(PREF_WHATSAPP_RESCAN_RETRY_MS, WHATSAPP_RESCAN_RETRY_DEFAULT_MS)
                        .coerceAtLeast(WHATSAPP_RESCAN_RETRY_MIN_MS)
                    whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
                    whatsAppScanHandler.postDelayed(whatsAppRetryRunnable, retry.toLong())
                }
            } else {
                whatsAppEmptyRetryCount = 0
                whatsAppScanHandler.removeCallbacks(whatsAppRetryRunnable)
                whatsAppAllLabels = labels
                whatsAppOverlayHasLabels = true
                applyWindowCovers()
            }
        }
    }

    private fun maybeRunWhatsAppColorProbe(pkg: String) {
        if (pkg != WHATSAPP_PKG) return
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_WHATSAPP_COLOR_PROBE_ENABLED, false)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            storeAndToastProbeResult("API < 30: takeScreenshot unavailable")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWhatsAppProbeMs < WHATSAPP_PROBE_INTERVAL_MS) return
        lastWhatsAppProbeMs = now
        runWhatsAppColorProbe()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun runWhatsAppColorProbe() {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hb = screenshot.hardwareBuffer
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            if (hwBitmap == null) {
                                storeAndToastProbeResult("wrapHardwareBuffer returned null")
                                return
                            }
                            // Hardware bitmaps don't allow getPixel; copy to a software bitmap.
                            val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            if (swBitmap == null) {
                                storeAndToastProbeResult("bitmap copy failed")
                                return
                            }
                            val cx = swBitmap.width / 2
                            val cy = swBitmap.height / 2
                            val color = swBitmap.getPixel(cx, cy)
                            swBitmap.recycle()
                            val r = Color.red(color)
                            val g = Color.green(color)
                            val b = Color.blue(color)
                            val isGray = abs(r - g) <= 4 && abs(g - b) <= 4 && abs(r - b) <= 4
                            val verdict = if (isGray) "GRAY (color path likely dead)" else "COLOR ALIVE"
                            storeAndToastProbeResult("center RGB($r,$g,$b) — $verdict")
                        } finally {
                            hb.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        storeAndToastProbeResult("takeScreenshot failed: code=$errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            storeAndToastProbeResult("probe exception: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun storeAndToastProbeResult(result: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$stamp] $result"
        getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_WHATSAPP_COLOR_PROBE_RESULT, line)
            .apply()
        Toast.makeText(this, line, Toast.LENGTH_LONG).show()
        Log.d(TAG, "WhatsApp color probe: $line")
    }

    private fun applyWebShortcutRule(browserPkg: String) {
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE, "web_shortcut:paused")
            return
        }
        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE, "web_shortcut:disabled")
            return
        }
        val bedtimeStore = BedtimeStore(this)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.ENABLE, "web_shortcut:bedtime")
            return
        }

        val root = rootInActiveWindow ?: return
        val url = findUrlInNodeTree(root) ?: run { root.recycle(); return }
        root.recycle()
        val activeSchedule = GrayscaleStateManager.currentActiveSchedule(this)
        val decision = if (activeSchedule?.webShortcutProfileEnabled == true) {
            ScheduleProfiles.shouldGrayScaleForScheduleWebShortcut(activeSchedule, url)
        } else {
            GrayscaleStateManager.webShortcutStore(this).shouldGrayScale(url)
        }
        when (decision) {
            true -> GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.ENABLE, "web_shortcut:rule_enable")
            false -> GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE, "web_shortcut:rule_disable")
            null -> { /* no matching rule — leave current state */ }
        }
    }

    /**
     * Depth-first search for the browser's URL text. Bounded by depth and by a total node budget:
     * with flagIncludeNotImportantViews the tree is deeper (the old cap of 8 could stop short of
     * the URL bar), and the budget keeps a huge page from turning this into a long walk.
     */
    private fun findUrlInNodeTree(root: AccessibilityNodeInfo): String? =
        findUrlInNodeTree(root, 0, intArrayOf(URL_WALK_NODE_BUDGET))

    private fun findUrlInNodeTree(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): String? {
        if (depth > URL_WALK_MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--
        val text = node.text?.toString()?.trim() ?: ""
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            // Domain-only display (e.g. "twitter.com") — common in Chrome's omnibox
            text.isNotEmpty() && !text.contains(" ") &&
                DOMAIN_REGEX.matches(text) -> "https://$text"
            else -> null
        }
        if (url != null) return url
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = try {
                findUrlInNodeTree(child, depth + 1, budget)
            } finally {
                child.recycle()
            }
            if (result != null) return result
        }
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        countdownOverlay?.onConfigurationChanged()
    }

    override fun onInterrupt() {
        clearExperimentalNotificationShade("interrupt")
        // Previously forced grayscale OFF here with no path to re-enable, which stranded the screen
        // in color while the master switch still read "on". Re-derive the correct state instead.
        GrayscaleLog.log(this, "onInterrupt", "reconciling grayscale state")
        GrayscaleStateManager.invalidate(this, "interrupt")
    }

    private fun handleExperimentalNotificationShadeEvent(
        event: AccessibilityEvent?,
        prefs: android.content.SharedPreferences
    ): Boolean {
        if (event == null) return false
        if (!prefs.getBoolean(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED, false)) {
            clearExperimentalNotificationShade("disabled")
            return false
        }

        val eventType = event.eventType
        if (eventType != TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return false
        }

        val pkg = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""
        val signal = classifyExperimentalNotificationShadeSignal(pkg, className)
        val hasShadeWindow = hasLikelyNotificationShadeWindow()
        val now = System.currentTimeMillis()

        when (signal) {
            ShadeSignal.EXCLUDED -> {
                if (experimentalNotificationShadeActive) {
                    clearExperimentalNotificationShade("excluded:$className")
                } else {
                    storeExperimentalNotificationShadeDebug("excluded:$className")
                }
                return false
            }
            ShadeSignal.SHADE -> {
                if (pkg == "com.android.systemui" && hasShadeWindow) {
                    experimentalNotificationShadeActive = true
                    experimentalNotificationShadeLastConfirmAt = now
                    storeExperimentalNotificationShadeDebug("engaged:$className")
                }
            }
            ShadeSignal.UNKNOWN -> {
                if (experimentalNotificationShadeActive) {
                    if (pkg.isNotEmpty() && pkg != "com.android.systemui") {
                        clearExperimentalNotificationShade("focus_return:$pkg")
                    } else if (hasShadeWindow) {
                        experimentalNotificationShadeLastConfirmAt = now
                        storeExperimentalNotificationShadeDebug("confirmed:$className")
                    } else if (now - experimentalNotificationShadeLastConfirmAt > EXPERIMENTAL_NOTIFICATION_SHADE_TIMEOUT_MS) {
                        clearExperimentalNotificationShade("timeout")
                    }
                } else if (pkg == "com.android.systemui" && hasShadeWindow && looksLikeShadeClass(className)) {
                    experimentalNotificationShadeActive = true
                    experimentalNotificationShadeLastConfirmAt = now
                    storeExperimentalNotificationShadeDebug("engaged_weak:$className")
                }
            }
        }

        if (!experimentalNotificationShadeActive) return false

        GrayscaleStateManager.applySystemEventMode(
            this,
            prefs.getString(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE, "ignore") ?: "ignore",
            "experimental_shade"
        )
        return true
    }

    private fun hasLikelyNotificationShadeWindow(): Boolean {
        return windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            if (!window.isActive && !window.isFocused) return@any false
            val title = window.title?.toString().orEmpty()
            looksLikeShadeClass(title) && !looksLikeExcludedSystemUi(title)
        }
    }

    private fun classifyExperimentalNotificationShadeSignal(pkg: String, className: String): ShadeSignal {
        if (pkg != "com.android.systemui") return ShadeSignal.UNKNOWN
        if (looksLikeExcludedSystemUi(className)) return ShadeSignal.EXCLUDED
        if (looksLikeShadeClass(className)) return ShadeSignal.SHADE
        return ShadeSignal.UNKNOWN
    }

    private fun looksLikeShadeClass(value: String): Boolean {
        return SHADE_KEYWORDS.any { value.contains(it, ignoreCase = true) }
    }

    private fun looksLikeExcludedSystemUi(value: String): Boolean {
        return EXCLUDED_SYSTEMUI_KEYWORDS.any { value.contains(it, ignoreCase = true) }
    }

    private fun clearExperimentalNotificationShade(reason: String) {
        if (!experimentalNotificationShadeActive &&
            getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
                .getString(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG, null) == reason
        ) {
            return
        }
        experimentalNotificationShadeActive = false
        experimentalNotificationShadeLastConfirmAt = 0L
        storeExperimentalNotificationShadeDebug(reason)
    }

    private fun storeExperimentalNotificationShadeDebug(reason: String) {
        getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG, reason)
            .apply()
    }

    private enum class ShadeSignal { SHADE, EXCLUDED, UNKNOWN }

    companion object {
        /** The connected service, or null. Lets the global toggle clear in-memory tracking state. */
        @Volatile
        var instance: MainService? = null
            private set

        const val ACTION_SHOW_PAUSE_OVERLAY = "io.github.cloudburst.grayscaler.ACTION_SHOW_PAUSE_OVERLAY"

        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED = "experimental_notification_shade_enabled"
        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE = "experimental_notification_shade_mode"
        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG = "experimental_notification_shade_debug"

        // WhatsApp read-indicator color-survival probe (temporary, gated by dev settings toggle).
        // Verifies whether AccessibilityService.takeScreenshot returns pre- or post-daltonizer pixels.
        const val PREF_WHATSAPP_COLOR_PROBE_ENABLED = "whatsapp_color_probe_enabled"
        const val PREF_WHATSAPP_COLOR_PROBE_RESULT = "whatsapp_color_probe_result"

        // WhatsApp Read/Delivered/Sent inline indicator feature.
        const val PREF_WHATSAPP_READ_INDICATOR_ENABLED = "whatsapp_read_indicator_enabled"
        const val PREF_WHATSAPP_SHADE_DEBUG = "whatsapp_shade_debug"
        const val PREF_WHATSAPP_SCAN_DEBOUNCE_MS = "whatsapp_scan_debounce_ms"
        const val WHATSAPP_SCAN_DEBOUNCE_DEFAULT_MS = 300
        // Hard floor — even if a user injects a smaller value the system screenshot rate limit
        // (~1/sec) means anything tighter just burns battery without producing fresher labels.
        const val WHATSAPP_SCAN_DEBOUNCE_MIN_MS = 100
        // One-shot empty-scan retry — catches WhatsApp's accessibility tree settling races,
        // especially chat → all-chats transitions where the bottom-nav tabs needed by the
        // chat-list path don't appear in the tree until after the first scan window.
        const val PREF_WHATSAPP_RESCAN_RETRY_MS = "whatsapp_rescan_retry_ms"
        const val WHATSAPP_RESCAN_RETRY_DEFAULT_MS = 700
        const val WHATSAPP_RESCAN_RETRY_MIN_MS = 200
        // Multi-shot retry cap. Total recovery wallclock ≈ retry_ms * retry_count. A fresh
        // content-change mid-chain does not reset this count (see [scheduleWhatsAppScan]).
        const val PREF_WHATSAPP_RESCAN_RETRY_COUNT = "whatsapp_rescan_retry_count"
        const val WHATSAPP_RESCAN_RETRY_COUNT_DEFAULT = 3
        const val WHATSAPP_RESCAN_RETRY_COUNT_MIN = 1
        // Heartbeat tick — recovers state for events the accessibility stream silently drops.
        // See [runWhatsAppHeartbeat] for the two failure modes this catches.
        private const val WHATSAPP_HEARTBEAT_INTERVAL_MS = 1000L
        private const val WHATSAPP_HEARTBEAT_IDLE_SCAN_EVERY = 5
        private const val WHATSAPP_AWAIT_RETURN_TICKS = 60
        private const val WHATSAPP_COVER_COALESCE_MS = 32L
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        private const val PAUSE_END_SLACK_MS = 250L
        private const val WHATSAPP_MIN_CAPTURE_INTERVAL_MS = 1000L
        private const val WHATSAPP_HEAL_MIN_INTERVAL_MS = 3000L
        private const val STALE_RECHECK_DELAY_MS = 300L
        private const val URL_WALK_MAX_DEPTH = 20
        private const val URL_WALK_NODE_BUDGET = 500
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_PROBE_INTERVAL_MS = 2000L

        const val DISPLAY_DALTONIZER = "accessibility_display_daltonizer"
        const val DISPLAY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val MONOCHROME = 0
        const val OFF = 0
        const val ON = 1

        private const val TAG = GrayscaleStateManager.LOG_TAG
        private const val URL_CHECK_DEBOUNCE_MS = 500L
        private const val EXPERIMENTAL_NOTIFICATION_SHADE_TIMEOUT_MS = 1200L

        private val DOMAIN_REGEX = Regex("^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.[a-zA-Z]{2,}(/\\S*)?$")

        private val KNOWN_LAUNCHER_PACKAGES = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.samsung.android.app.launcher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.android.launcher3",
            "com.oneplus.launcher",
            "com.nothing.launcher",
        )

        private val RECENTS_KEYWORDS = setOf("Recents", "RecentTask", "TaskView", "RecentsActivity")

        private val NOTIF_KEYWORDS = setOf(
            "NotificationShade", "NotificationPanel", "NotificationBar",
            "StatusBar", "QuickSettings", "QuickSetting"
        )

        private val SHADE_KEYWORDS = setOf(
            "NotificationShade", "NotificationPanel", "StatusBar",
            "QuickSettings", "QuickSetting", "QSPanel", "Shade"
        )

        private val EXCLUDED_SYSTEMUI_KEYWORDS = setOf(
            "Volume", "VolumeDialog", "VolumePanel", "SafetyWarning",
            "GlobalActions", "PowerMenu", "Keyguard", "HeadsUp", "Headsup"
        )

        private val LOCKSCREEN_KEYWORDS = setOf(
            "Keyguard", "KeyguardView", "KeyguardHost", "KeyguardBouncer", "StatusBarKeyguard"
        )

        private val POWER_MENU_KEYWORDS = setOf(
            "GlobalActions", "GlobalActionsDialog", "PowerMenu"
        )

        // RemoteEditText is the inner EditText class inside RemoteInputView on stock Android
        private val INLINE_REPLY_KEYWORDS = setOf(
            "RemoteInput", "RemoteInputView", "RemoteEditText"
        )
    }
}
