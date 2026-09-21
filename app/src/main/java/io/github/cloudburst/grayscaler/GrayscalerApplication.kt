package io.github.cloudburst.grayscaler

import android.app.Application
import android.content.SharedPreferences

class GrayscalerApplication : Application() {

    // Held in a field: SharedPreferences only keeps weak references to its listeners.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (AutoBackup.isConfigKey(key)) AutoBackup.requestBackup(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Settings kept in files (schedules, app lists, overlay ignores, web shortcuts) are not
        // visible here; their stores request a backup from save() instead.
        getSharedPreferences("grayscaler_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(settingsListener)
    }
}
