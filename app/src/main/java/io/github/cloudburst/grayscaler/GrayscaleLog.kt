package io.github.cloudburst.grayscaler

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, opt-in diagnostic logger for grayscale state transitions.
 *
 * Purpose: the app tracks two independent notions of "grayscale is on" — its own belief
 * (`grayscaler_enabled` / [GrayscaleStateManager.lastDecision]) and the actual system Secure
 * settings that drive the screen. When those diverge the screen shows color while the UI still
 * reads "on". This logger records every apply (with the value read back from the system), which
 * caller triggered it, and on which thread, so a stuck-in-color state can be diagnosed from a file.
 *
 * Off by default (gated by [PREF_ENABLED]) so it costs nothing in normal use. Writes to an
 * app-private file (no permissions needed) and self-trims so it can never grow unbounded.
 */
object GrayscaleLog {
    const val PREF_ENABLED = "diagnostic_logging_enabled"
    // RC6 diagnostic: when on, an ENABLE that would otherwise re-write the daltonizer keys to values
    // they already hold instead forces a 0 -> 1 transition, guaranteeing the system color observer
    // re-fires. Separate from [PREF_ENABLED] on purpose — see DeveloperSettingsScreen note.
    const val PREF_FORCE_RETOGGLE = "force_daltonizer_retoggle"
    private const val PREFS_NAME = "grayscaler_prefs"
    private const val LOG_FILE = "grayscale_log.txt"
    private const val MAX_BYTES = 256 * 1024
    private const val TRIM_TO = 192 * 1024

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    fun forceRetoggle(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_FORCE_RETOGGLE, false)

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun log(context: Context, tag: String, message: String) {
        if (!isEnabled(context)) return
        val line = "${timestampFormat.format(Date())} | ${Thread.currentThread().name} | $tag | $message\n"
        synchronized(lock) {
            try {
                val file = logFile(context)
                file.appendText(line)
                if (file.length() > MAX_BYTES) trim(file)
            } catch (_: Throwable) {
                // Diagnostics must never take down a real code path.
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            logFile(context).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        try {
            logFile(context).delete()
        } catch (_: Throwable) {
        }
        Unit
    }

    /**
     * Best-effort name of the code that triggered the current log call, derived from the stack.
     * Skips frames inside this logger and [GrayscaleStateManager] so the reported caller is the
     * actual entry point (e.g. `MainService.handleForegroundApp`, `ScheduleReceiver.onPauseEnd`).
     */
    fun caller(): String {
        val stack = Throwable().stackTrace
        for (frame in stack) {
            val className = frame.className
            if (className.contains("GrayscaleLog") || className.contains("GrayscaleStateManager")) continue
            return "${className.substringAfterLast('.')}.${frame.methodName}"
        }
        return "?"
    }

    private fun trim(file: File) {
        try {
            val bytes = file.readBytes()
            if (bytes.size <= TRIM_TO) return
            val keep = bytes.copyOfRange(bytes.size - TRIM_TO, bytes.size)
            file.writeBytes("...[trimmed]...\n".toByteArray() + keep)
        } catch (_: Throwable) {
        }
    }
}
