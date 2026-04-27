package io.github.cloudburst.grayscaler

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SettingsBackupManager {

    private const val PREFS_NAME = "grayscaler_prefs"
    private const val BACKUP_VERSION = 1
    private const val PAUSE_END_REQUEST_CODE = 9999
    private const val PAUSE_NOTIFY_TRANSITION_REQUEST_CODE = 9998

    private val transientPrefKeys = setOf(
        "active_schedule_id",
        "last_window_class",
        "last_meaningful_foreground_class",
        "last_meaningful_foreground_pkg",
        BedtimeStore.KEY_BEDTIME_CHARGE_LATCH_ACTIVE,
        BedtimeStore.KEY_BEDTIME_OVERRIDE_ACTIVE,
        "notif_permission_prompted",
        "pause_until",
        "schedule_override_active",
        MainService.PREF_EXPERIMENTAL_NOTIFICATION_SHADE_DEBUG
    )

    private val fontSlots = listOf(
        FontSlot(
            id = "main",
            pathKey = AppearancePreferences.KEY_MAIN_FONT_PATH,
            nameKey = AppearancePreferences.KEY_MAIN_FONT_NAME
        ),
        FontSlot(
            id = "header",
            pathKey = AppearancePreferences.KEY_HEADER_FONT_PATH,
            nameKey = AppearancePreferences.KEY_HEADER_FONT_NAME
        ),
        FontSlot(
            id = "subheader",
            pathKey = AppearancePreferences.KEY_SUBHEADER_FONT_PATH,
            nameKey = AppearancePreferences.KEY_SUBHEADER_FONT_NAME
        ),
        FontSlot(
            id = "tertiary",
            pathKey = AppearancePreferences.KEY_TERTIARY_FONT_PATH,
            nameKey = AppearancePreferences.KEY_TERTIARY_FONT_NAME
        )
    )

    private val managedFiles = listOf(
        "apps_whitelist.txt",
        "apps_blacklist.txt",
        "overlay_ignore.txt",
        "schedules.txt",
        "web_shortcuts.txt"
    )

    fun exportToUri(context: Context, uri: Uri) {
        val backup = buildBackupJson(context)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(backup.toString(2))
        } ?: error("Unable to open export destination")
    }

    fun importFromUri(context: Context, uri: Uri) {
        val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to open backup file")
        val backup = JSONObject(jsonText)
        val version = backup.optInt("version", -1)
        require(version == BACKUP_VERSION) { "Unsupported backup version: $version" }

        val oldSchedules = ScheduleStore(context).apply { load() }.schedules.toList()
        ScheduleManager(context).cancelAll(oldSchedules)
        BedtimeManager(context).cancelAll()
        clearRuntimePause(context)

        restorePrefs(context, backup.getJSONObject("prefs"))
        restoreFiles(context, backup.optJSONObject("files"))
        restoreFonts(context, backup.optJSONArray("fonts"))

        val scheduleStore = ScheduleStore(context).apply { load() }
        scheduleStore.syncRuntimeStateNow()
        ScheduleManager(context).registerAll(scheduleStore.schedules)
        BedtimeManager(context).apply {
            registerAll()
            resync()
        }
        GrayscaleStateManager.invalidate(context)
    }

    private fun buildBackupJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("prefs", exportPrefs(prefs))
            put("files", exportFiles(context))
            put("fonts", exportFonts(prefs))
        }
    }

    private fun exportPrefs(prefs: android.content.SharedPreferences): JSONObject {
        val json = JSONObject()
        prefs.all
            .filterKeys { key -> key !in transientPrefKeys && !isFontPrefKey(key) }
            .toSortedMap()
            .forEach { (key, value) ->
                json.put(key, encodePrefValue(value))
            }
        return json
    }

    private fun exportFiles(context: Context): JSONObject {
        val json = JSONObject()
        managedFiles.forEach { name ->
            val file = context.filesDir.resolve(name)
            json.put(name, if (file.exists()) file.readText() else JSONObject.NULL)
        }
        return json
    }

    private fun exportFonts(prefs: android.content.SharedPreferences): JSONArray {
        val array = JSONArray()
        fontSlots.forEach { slot ->
            val path = prefs.getString(slot.pathKey, null).orEmpty()
            if (path.isBlank()) return@forEach
            val file = File(path)
            if (!file.exists()) return@forEach
            array.put(
                JSONObject().apply {
                    put("slot", slot.id)
                    put("name", prefs.getString(slot.nameKey, "") ?: "")
                    put("extension", file.extension)
                    put("contentBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                }
            )
        }
        return array
    }

    private fun restorePrefs(context: Context, prefsJson: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        val editor = prefs.edit()
        val keys = prefsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = prefsJson.getJSONObject(key)
            when (value.getString("type")) {
                "boolean" -> editor.putBoolean(key, value.getBoolean("value"))
                "float" -> editor.putFloat(key, value.getDouble("value").toFloat())
                "int" -> editor.putInt(key, value.getInt("value"))
                "long" -> editor.putLong(key, value.getLong("value"))
                "string" -> editor.putString(key, value.getString("value"))
                else -> error("Unsupported pref type for $key")
            }
        }
        editor.apply()
    }

    private fun restoreFiles(context: Context, filesJson: JSONObject?) {
        managedFiles.forEach { name ->
            val file = context.filesDir.resolve(name)
            if (filesJson == null || filesJson.isNull(name)) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(filesJson.getString(name))
            }
        }
    }

    private fun restoreFonts(context: Context, fontsJson: JSONArray?) {
        val prefs = AppearancePreferences.prefs(context)
        val fontsDir = File(context.filesDir, "fonts")

        fontSlots.forEach { slot ->
            AppearancePreferences.clearFont(context, slot.pathKey, slot.nameKey)
        }
        if (fontsDir.exists()) {
            fontsDir.listFiles()?.forEach { it.delete() }
        } else {
            fontsDir.mkdirs()
        }

        val editor = prefs.edit()
        if (fontsJson != null) {
            for (index in 0 until fontsJson.length()) {
                val font = fontsJson.getJSONObject(index)
                val slot = fontSlots.firstOrNull { it.id == font.getString("slot") } ?: continue
                val extension = font.optString("extension").ifBlank { "ttf" }
                val target = File(fontsDir, "${slot.id}_${System.currentTimeMillis()}_$index.$extension")
                val bytes = Base64.decode(font.getString("contentBase64"), Base64.DEFAULT)
                target.writeBytes(bytes)
                editor.putString(slot.pathKey, target.absolutePath)
                editor.putString(slot.nameKey, font.optString("name"))
            }
        }
        editor.apply()
    }

    private fun clearRuntimePause(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("pause_until")
            .apply()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        cancelPauseAlarm(context, alarmManager, ScheduleReceiver.ACTION_PAUSE_END, PAUSE_END_REQUEST_CODE)
        cancelPauseAlarm(
            context,
            alarmManager,
            ScheduleReceiver.ACTION_PAUSE_NOTIFY_COUNTDOWN,
            PAUSE_NOTIFY_TRANSITION_REQUEST_CODE
        )
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ScheduleReceiver.NOTIF_ID_STATIC)
        notificationManager.cancel(ScheduleReceiver.NOTIF_ID_COUNTDOWN)
    }

    private fun cancelPauseAlarm(
        context: Context,
        alarmManager: android.app.AlarmManager,
        action: String,
        requestCode: Int
    ) {
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            requestCode,
            android.content.Intent(context, ScheduleReceiver::class.java).apply { this.action = action },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun encodePrefValue(value: Any?): JSONObject {
        return when (value) {
            is Boolean -> JSONObject().put("type", "boolean").put("value", value)
            is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
            is Int -> JSONObject().put("type", "int").put("value", value)
            is Long -> JSONObject().put("type", "long").put("value", value)
            is String -> JSONObject().put("type", "string").put("value", value)
            else -> error("Unsupported pref value type: ${value?.javaClass?.name ?: "null"}")
        }
    }

    private fun isFontPrefKey(key: String): Boolean {
        return fontSlots.any { key == it.pathKey || key == it.nameKey }
    }

    private data class FontSlot(
        val id: String,
        val pathKey: String,
        val nameKey: String
    )
}
