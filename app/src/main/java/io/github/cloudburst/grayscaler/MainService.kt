package io.github.cloudburst.grayscaler

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.inputmethod.InputMethodManager

class MainService : AccessibilityService() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    when (prefs.getString("lockscreen_mode", "ignore")) {
                        "enable" -> enableMonochrome()
                        "disable" -> disableMonochrome()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    val pkg = prefs.getString("last_foreground_pkg", null) ?: return
                    val cls = prefs.getString("last_foreground_class", "") ?: ""
                    handleForegroundApp(pkg, cls, prefs)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Inline reply typing detection — fires when an EditText inside a notification gets focus
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != "com.android.systemui") return
            val className = event.className?.toString() ?: ""
            if (!INLINE_REPLY_KEYWORDS.any { className.contains(it, ignoreCase = true) }) return
            val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
            when (prefs.getString("inline_reply_mode", "ignore")) {
                "enable" -> enableMonochrome()
                "disable" -> disableMonochrome()
            }
            return
        }

        if (event?.eventType != TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // 1. Diagnostic capture — runs independently of master switch
        if (prefs.getBoolean("photo_viewer_diagnostic", false)) {
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
                when (prefs.getString("app_switcher_mode", "ignore")) {
                    "enable" -> enableMonochrome()
                    "disable" -> disableMonochrome()
                }
                return
            }
            isNotifShade -> {
                when (prefs.getString("notification_center_mode", "ignore")) {
                    "enable" -> enableMonochrome()
                    "disable" -> disableMonochrome()
                }
                return
            }
            isLockscreen -> {
                when (prefs.getString("lockscreen_mode", "ignore")) {
                    "enable" -> enableMonochrome()
                    "disable" -> disableMonochrome()
                }
                return
            }
            isPowerMenu -> {
                when (prefs.getString("power_menu_mode", "disable")) {
                    "enable" -> enableMonochrome()
                    "disable" -> disableMonochrome()
                }
                return
            }
            pkg == "com.android.systemui" -> return
        }

        // 3. Save foreground context for unlock restore, then apply per-app logic
        prefs.edit()
            .putString("last_foreground_pkg", pkg)
            .putString("last_foreground_class", className)
            .apply()
        handleForegroundApp(pkg, className, prefs)
    }

    private fun handleForegroundApp(pkg: String, className: String, prefs: SharedPreferences) {
        // Photo viewer auto-disable — overrides all other rules
        val photoStore = PhotoViewerStore(this)
        if (photoStore.masterEnabled && photoStore.matches(pkg, className)) {
            disableMonochrome()
            return
        }

        // User-managed ignore list (includes system ignores + Gemini group + user additions)
        val ignoreStore = OverlayIgnoreStore(this)
        ignoreStore.load()
        if (ignoreStore.effectiveIgnoreList().contains(pkg)) return

        // Skip keyboard/IME packages
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return

        // Pause active — grayscale off
        val pauseUntil = prefs.getLong("pause_until", 0L)
        if (pauseUntil > System.currentTimeMillis()) {
            disableMonochrome()
            return
        }

        // Global switch off — grayscale off
        val globalEnabled = prefs.getBoolean("grayscaler_enabled", true)
        if (!globalEnabled) {
            disableMonochrome()
            return
        }

        // Active schedule — force grayscale on (whitelist still applies per-app below)
        val scheduleStore = ScheduleStore(this)
        scheduleStore.load()
        if (scheduleStore.scheduleOverrideActive) {
            val appStore = AppListStore(this)
            appStore.load()
            if (appStore.shouldGrayScale(pkg)) enableMonochrome() else disableMonochrome()
            return
        }

        // Default whitelist/blacklist behavior
        val appStore = AppListStore(this)
        appStore.load()
        if (appStore.shouldGrayScale(pkg)) enableMonochrome() else disableMonochrome()
    }

    override fun onInterrupt() {
        disableMonochrome()
    }

    private fun enableMonochrome() {
        Settings.Secure.putInt(contentResolver, DISPLAY_DALTONIZER, MONOCHROME)
        Settings.Secure.putInt(contentResolver, DISPLAY_DALTONIZER_ENABLED, ON)
    }

    private fun disableMonochrome() {
        Settings.Secure.putInt(contentResolver, DISPLAY_DALTONIZER_ENABLED, OFF)
    }

    companion object {
        const val DISPLAY_DALTONIZER = "accessibility_display_daltonizer"
        const val DISPLAY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val MONOCHROME = 0
        const val OFF = 0
        const val ON = 1

        private const val TAG = "Grayscaler"

        private val RECENTS_KEYWORDS = setOf("Recents", "RecentTask", "TaskView", "RecentsActivity")

        private val NOTIF_KEYWORDS = setOf(
            "NotificationShade", "NotificationPanel", "NotificationBar",
            "StatusBar", "QuickSettings", "QuickSetting"
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
