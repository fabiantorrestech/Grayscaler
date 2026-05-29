package io.github.cloudburst.grayscaler

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    // Package name of the browser currently in foreground, null if not a browser
    private var currentBrowserPkg: String? = null
    private var lastUrlCheckTime = 0L
    private var experimentalNotificationShadeActive = false
    private var experimentalNotificationShadeLastConfirmAt = 0L

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
                    GrayscaleStateManager.applySystemEventMode(
                        context,
                        prefs.getString("lockscreen_mode", "ignore") ?: "ignore"
                    )
                }
                Intent.ACTION_USER_PRESENT -> {
                    clearExperimentalNotificationShade("user_present")
                    GrayscaleStateManager.invalidate(context)
                }
            }
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
                if (pauseUntil > System.currentTimeMillis()) {
                    countdownOverlay?.show(pauseUntil)
                } else if (prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") != "session") {
                    countdownOverlay?.hide()
                }
            }
            ScheduleReceiver.PREF_PAUSE_TYPE -> {
                val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"
                when (pauseType) {
                    "session" -> {
                        val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, 0L)
                        countdownOverlay?.showSession(startMs)
                    }
                    "timed" -> countdownOverlay?.hide()
                    "pending_session" -> countdownOverlay?.hide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        countdownOverlay = PauseCountdownOverlayManager(this, windowManager)

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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("persistent_overlay_mode", false)) registerPersistentOverlay()
        val pauseUntil = prefs.getLong("pause_until", 0L)
        val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"
        when {
            pauseUntil > System.currentTimeMillis() -> countdownOverlay?.show(pauseUntil)
            pauseType == "session" -> {
                val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, 0L)
                countdownOverlay?.showSession(startMs)
            }
        }
    }

    override fun onDestroy() {
        graceHandler.removeCallbacks(graceRunnable)
        unregisterReceiver(screenReceiver)
        unregisterReceiver(overlayReceiver)
        getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        unregisterPersistentOverlay()
        countdownOverlay?.hide()
        countdownOverlay = null
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
        )
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
        if (!wasVisible) {
            overlayPresentationKey += 1
        }
        view.post { view.requestFocus() }
    }

    private fun hidePersistentOverlay() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        view.visibility = View.GONE
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(view, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Inline reply typing detection — fires when an EditText inside a notification gets focus
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != "com.android.systemui") return
            val className = event.className?.toString() ?: ""
            if (!INLINE_REPLY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            GrayscaleStateManager.applySystemEventMode(
                this,
                prefs.getString("inline_reply_mode", "ignore") ?: "ignore"
            )
            return
        }

        // URL-based web shortcut matching — debounced, only when a browser is in foreground
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            if (handleExperimentalNotificationShadeEvent(event, prefs)) return
            val browserPkg = currentBrowserPkg ?: return
            if (event.packageName?.toString() != browserPkg) return
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
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("app_switcher_mode", "ignore") ?: "ignore"
                )
                return
            }
            isNotifShade -> {
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("notification_center_mode", "ignore") ?: "ignore"
                )
                return
            }
            isLockscreen -> {
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("lockscreen_mode", "ignore") ?: "ignore"
                )
                return
            }
            isPowerMenu -> {
                GrayscaleStateManager.applySystemEventMode(
                    this,
                    prefs.getString("power_menu_mode", "disable") ?: "disable"
                )
                return
            }
            pkg == "com.android.systemui" -> return
        }

        // 3. Discard stale events from windows being detached — only trust this event if
        //    rootInActiveWindow confirms the same package is actually in the foreground.
        //    Null rootInActiveWindow means we can't confirm either way, so we let it through.
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()
        root?.recycle()
        if (rootPkg != null && rootPkg != pkg) return

        // 4. Apply per-app logic. GrayscaleStateManager is responsible for deciding whether this
        //    foreground is meaningful enough to become the restore/invalidation context.
        handleForegroundApp(pkg, className)
    }

    private fun checkSessionPauseState(pkg: String) {
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
        GrayscaleStateManager.applyToSystem(this, decision)
        if (decision == GrayscaleStateManager.Decision.SKIP) return

        // Track browser state; immediately probe URL if this is a browser coming to foreground
        if (pkg in browserPackages) {
            currentBrowserPkg = pkg
            lastUrlCheckTime = System.currentTimeMillis()
            applyWebShortcutRule(pkg)
        } else {
            currentBrowserPkg = null
        }
    }

    private fun applyWebShortcutRule(browserPkg: String) {
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        if (prefs.getLong("pause_until", 0L) > System.currentTimeMillis()) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE)
            return
        }
        if (!prefs.getBoolean("grayscaler_enabled", true)) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE)
            return
        }
        val bedtimeStore = BedtimeStore(this)
        bedtimeStore.syncRuntimeState()
        if (bedtimeStore.bedtimeOverrideActive) {
            GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.ENABLE)
            return
        }

        val root = rootInActiveWindow ?: return
        val url = findUrlInNodeTree(root) ?: run { root.recycle(); return }
        root.recycle()
        val activeSchedule = GrayscaleStateManager.currentActiveSchedule(this)
        val decision = if (activeSchedule?.webShortcutProfileEnabled == true) {
            ScheduleProfiles.shouldGrayScaleForScheduleWebShortcut(activeSchedule, url)
        } else {
            val webStore = WebShortcutStore(this)
            webStore.load()
            webStore.shouldGrayScale(url)
        }
        when (decision) {
            true -> GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.ENABLE)
            false -> GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE)
            null -> { /* no matching rule — leave current state */ }
        }
    }

    private fun findUrlInNodeTree(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 8) return null
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
            val result = findUrlInNodeTree(child, depth + 1)
            child.recycle()
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
        GrayscaleStateManager.applyToSystem(this, GrayscaleStateManager.Decision.DISABLE)
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
            prefs.getString(PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE, "ignore") ?: "ignore"
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
        const val ACTION_SHOW_PAUSE_OVERLAY = "io.github.cloudburst.grayscaler.ACTION_SHOW_PAUSE_OVERLAY"

        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_ENABLED = "experimental_notification_shade_enabled"
        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_MODE = "experimental_notification_shade_mode"
        const val PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG = "experimental_notification_shade_debug"

        const val DISPLAY_DALTONIZER = "accessibility_display_daltonizer"
        const val DISPLAY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val MONOCHROME = 0
        const val OFF = 0
        const val ON = 1

        private const val TAG = "Grayscaler"
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
