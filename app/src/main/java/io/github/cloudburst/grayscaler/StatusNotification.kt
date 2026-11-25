package io.github.cloudburst.grayscaler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one Grayscaler+ notification. It shows the master switch and pause state, opens the pause
 * popup on tap, and has three buttons: global on/off, a preset pause, and cancel pause.
 *
 * It has two modes:
 * - Persistent (default): always shown while [MainService] is connected, and used by the service
 *   as its foreground notification so aggressive OEM battery savers are less likely to kill it.
 * - Not persistent: shown only while a pause is active, like the old pause notifications.
 *
 * It only reacts to on/off and pause changes, never to per-app gray/colour decisions, so nothing
 * on the accessibility hot path ever touches it.
 */
object StatusNotification {
    const val CHANNEL_ID = "grayscaler_status"
    const val NOTIF_ID = 1003

    const val PREF_PERSISTENT_ENABLED = "persistent_notification_enabled"
    const val PREF_PAUSE_PRESET_SECONDS = "notif_pause_preset_seconds"
    const val PAUSE_PRESET_DEFAULT_SECONDS = 900L

    // Pause notifications from before this notification existed: their channels and IDs.
    private val RETIRED_CHANNEL_IDS = listOf("grayscaler_pause_static", "grayscaler_pause_countdown")
    private val RETIRED_NOTIF_IDS = listOf(1001, 1002)

    // A timed pause shows a live countdown only in its last 5 minutes (when that setting is on).
    // ScheduleReceiver arms an alarm at that point to call refresh().
    const val COUNTDOWN_WINDOW_MS = 5 * 60 * 1000L

    private const val CONTENT_REQUEST_CODE = 9990
    private const val TOGGLE_REQUEST_CODE = 9991
    private const val PRESET_REQUEST_CODE = 9992
    private const val CANCEL_REQUEST_CODE = 9993

    fun isPersistentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_PERSISTENT_ENABLED, true)

    fun pausePresetSeconds(context: Context): Long =
        prefs(context).getLong(PREF_PAUSE_PRESET_SECONDS, PAUSE_PRESET_DEFAULT_SECONDS)

    /** Creates the status channel and removes the retired pause channels. Safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Grayscaler+ Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Grayscaler+ state, pause timer and quick controls"
                setShowBadge(false)
            }
        )
        RETIRED_CHANNEL_IDS.forEach { nm.deleteNotificationChannel(it) }
    }

    /**
     * Posts, updates or removes the notification to match the current state. Call it after any
     * change to the master switch, the pause state or the notification settings.
     */
    fun refresh(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        RETIRED_NOTIF_IDS.forEach { nm.cancel(it) }

        if (!shouldShow(context)) {
            // Has no effect while it is the service's foreground notification; MainService
            // removes that one itself with stopForeground().
            nm.cancel(NOTIF_ID)
            return
        }
        if (!hasNotificationPermission(context)) return
        // Same ID as the foreground notification, so this updates it in place.
        nm.notify(NOTIF_ID, build(context))
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val enabled = GrayscalerToggleCoordinator.isEnabled(context)
        val pauseUntil = prefs.getLong("pause_until", 0L)
        val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openPopupIntent(context))

        when {
            !enabled -> builder
                .setContentTitle("Grayscaler+ Off")
                .setContentText("Tap for pause options")

            pauseType == "pending_session" -> builder
                .setContentTitle("Grayscaler+ · Waiting for next app…")

            pauseType == "session" -> {
                val startMs = prefs.getLong(ScheduleReceiver.PREF_SESSION_PAUSE_START_MS, now)
                val pkg = prefs.getString(ScheduleReceiver.PREF_SESSION_PAUSE_PKG, "") ?: ""
                // `when` is wall-clock time; the chronometer counts up from it. An elapsed-realtime
                // value here dates the notification to 1970 and the system drops the update.
                builder
                    .setContentTitle("Grayscaler+ Paused · ${appLabel(context, pkg)} session")
                    .setUsesChronometer(true)
                    .setChronometerCountDown(false)
                    .setWhen(startMs)
                    .setShowWhen(true)
            }

            pauseUntil > now -> {
                val remainingMs = pauseUntil - now
                val countdownEnabled = prefs.getBoolean("pause_countdown_notif_enabled", true)
                if (countdownEnabled && remainingMs <= COUNTDOWN_WINDOW_MS) {
                    builder
                        .setContentTitle("Grayscaler+ re-enabling soon")
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setWhen(pauseUntil)
                        .setShowWhen(true)
                } else {
                    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                    builder
                        .setContentTitle("Grayscaler+ Paused")
                        .setContentText("Re-enables at ${fmt.format(Date(pauseUntil))}")
                }
                // Backup for a late pause-end alarm. Not on the persistent notification: it is
                // the service's foreground notification and must stay up after the pause.
                if (!isPersistentEnabled(context)) builder.setTimeoutAfter(remainingMs)
            }

            else -> builder
                .setContentTitle("Grayscaler+ On")
                .setContentText("Tap for pause options")
        }

        // Fixed order, so the buttons never move under the user's thumb.
        builder.addAction(0, if (enabled) "Turn off" else "Turn on", toggleIntent(context))
        builder.addAction(0, "Pause ${formatDuration(pausePresetSeconds(context))}", presetPauseIntent(context))
        builder.addAction(0, "Cancel pause", cancelPauseIntent(context))
        return builder.build()
    }

    /** Short duration label, matching the popup's quick-pause buttons where possible. */
    fun formatDuration(seconds: Long): String {
        QuickPauseDurations.firstOrNull { it.second == seconds }?.let { return it.first }
        return when {
            seconds % 3600L == 0L -> "${seconds / 3600L}h"
            seconds % 60L == 0L -> "${seconds / 60L}m"
            else -> "${seconds}s"
        }
    }

    private fun shouldShow(context: Context): Boolean {
        // No "keeping the service alive" notification when the service is not running.
        val persistentShown = isPersistentEnabled(context) && MainService.instance != null
        return persistentShown || isPauseActive(prefs(context))
    }

    private fun isPauseActive(prefs: SharedPreferences): Boolean {
        val pauseType = prefs.getString(ScheduleReceiver.PREF_PAUSE_TYPE, "timed") ?: "timed"
        return prefs.getLong("pause_until", 0L) > System.currentTimeMillis() ||
            pauseType == "session" || pauseType == "pending_session"
    }

    private fun openPopupIntent(context: Context): PendingIntent {
        val intent = Intent(context, PauseOverlayShortcutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, CONTENT_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun toggleIntent(context: Context): PendingIntent =
        receiverIntent(context, TOGGLE_REQUEST_CODE) {
            action = ScheduleReceiver.ACTION_TOGGLE_ENABLED
            putExtra(ScheduleReceiver.EXTRA_REASON, "notification")
        }

    private fun presetPauseIntent(context: Context): PendingIntent =
        receiverIntent(context, PRESET_REQUEST_CODE) {
            action = ScheduleReceiver.ACTION_APPLY_PAUSE
            putExtra(ScheduleReceiver.EXTRA_SECONDS, pausePresetSeconds(context))
        }

    private fun cancelPauseIntent(context: Context): PendingIntent =
        receiverIntent(context, CANCEL_REQUEST_CODE) {
            action = ScheduleReceiver.ACTION_PAUSE_END
            // Pressed with no pause active, it must do nothing at all.
            putExtra(ScheduleReceiver.EXTRA_ONLY_IF_PAUSED, true)
        }

    private fun receiverIntent(context: Context, requestCode: Int, setup: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply(setup)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context): SharedPreferences =
        GrayscalerToggleCoordinator.prefs(context)
}
