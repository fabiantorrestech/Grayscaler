package io.github.cloudburst.grayscaler

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.inputmethod.InputMethodManager

class MainService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 1. User-managed ignore list (includes system ignores + Gemini group + user additions)
        val ignoreStore = OverlayIgnoreStore(this)
        ignoreStore.load()
        if (ignoreStore.effectiveIgnoreList().contains(pkg)) return

        // 2. Skip keyboard/IME packages
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.enabledInputMethodList.any { it.packageName == pkg }) return

        val prefs = getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        // 3. Pause active — grayscale off
        val pauseUntil = prefs.getLong("pause_until", 0L)
        if (pauseUntil > System.currentTimeMillis()) {
            disableMonochrome()
            return
        }

        // 4. Global switch off — grayscale off
        val globalEnabled = prefs.getBoolean("grayscaler_enabled", true)
        if (!globalEnabled) {
            disableMonochrome()
            return
        }

        // 5. Active schedule — force grayscale on (whitelist still applies per-app below)
        val scheduleStore = ScheduleStore(this)
        scheduleStore.load()
        if (scheduleStore.scheduleOverrideActive) {
            val appStore = AppListStore(this)
            appStore.load()
            if (appStore.shouldGrayScale(pkg)) enableMonochrome() else disableMonochrome()
            return
        }

        // 6. Default whitelist/blacklist behavior
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
    }
}