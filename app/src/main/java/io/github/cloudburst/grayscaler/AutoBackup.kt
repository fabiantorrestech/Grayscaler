package io.github.cloudburst.grayscaler

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Keeps a copy of the settings in a folder the user picked, rewritten a few seconds after any setting
 * changes: `<folder>/grayscaler_autobackup/grayscaler_autobackup.json`. The file has the same format
 * as a manual export, so Import Settings restores it.
 *
 * Its own settings live in a separate prefs file, so a restore never overwrites them and changing
 * them never triggers a backup.
 */
object AutoBackup {

    enum class Result { WRITTEN, UNCHANGED, NO_FOLDER, FAILED }

    const val SUBFOLDER = "grayscaler_autobackup"
    const val FILE_NAME = "grayscaler_autobackup.json"

    private const val PREFS_NAME = "grayscaler_backup_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DIRECTORY_URI = "directory_uri"
    private const val KEY_LAST_HASH = "last_hash"
    private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
    private const val KEY_LAST_ERROR = "last_error"

    private const val DEBOUNCE_MS = 3_000L
    private const val JSON_MIME = "application/json"
    private const val ACCESS_LOST = "Folder access was lost. Choose the folder again."
    private const val TREE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // Written in the main prefs file but not configuration: they change constantly or describe the
    // device's current moment, so they must not cause a backup. (Most are also excluded from export.)
    private val NON_CONFIG_KEYS = setOf(
        "per_app_views_diagnostic",
        "last_window_class",
        "last_meaningful_foreground_pkg",
        "last_meaningful_foreground_class",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Any()
    private val writeLock = Any()
    private var pending: Job? = null

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun directoryUri(context: Context): Uri? =
        prefs(context).getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)

    fun lastSuccessMs(context: Context): Long = prefs(context).getLong(KEY_LAST_SUCCESS_MS, 0L)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    /** Whether a change to [key] in the main prefs file is a settings change worth backing up. */
    fun isConfigKey(key: String?): Boolean {
        if (key == null) return false // the clear() at the start of an import; per-key events follow
        if (key in SettingsBackupManager.transientPrefKeys) return false
        if (key in NON_CONFIG_KEYS) return false
        if (key.startsWith("countdown_pill_")) return false // pill drag position
        return true
    }

    /** Schedules a backup a few seconds from now; a burst of changes produces one write. */
    fun requestBackup(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || directoryUri(app) == null) return
        synchronized(jobLock) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MS)
                backupNow(app)
            }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) synchronized(jobLock) { pending?.cancel() }
    }

    /**
     * Keeps access to [treeUri] across reboots and gives up access to the previous folder.
     * Throws if the system refuses a persistable grant.
     */
    fun setFolder(context: Context, treeUri: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(treeUri, TREE_FLAGS)
        val previous = directoryUri(context)
        if (previous != null && previous != treeUri) {
            runCatching { resolver.releasePersistableUriPermission(previous, TREE_FLAGS) }
        }
        prefs(context).edit()
            .putString(KEY_DIRECTORY_URI, treeUri.toString())
            .remove(KEY_LAST_HASH)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun hasFolderAccess(context: Context): Boolean {
        val tree = directoryUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    /** Name of the chosen folder as the file picker shows it, or null if none is chosen. */
    fun folderDisplayName(context: Context): String? {
        val tree = directoryUri(context) ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val fromProvider = runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            context.contentResolver.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        return treeDocId.substringAfter(':').ifBlank { treeDocId }
    }

    /**
     * Writes the backup now, on the calling thread (call it off the main thread). Skips the write
     * when the settings are the same as the last backup, unless [force] is set.
     */
    fun backupNow(context: Context, force: Boolean = false): Result = synchronized(writeLock) {
        val app = context.applicationContext
        val tree = directoryUri(app) ?: return Result.NO_FOLDER
        val prefs = prefs(app)
        try {
            val backup = SettingsBackupManager.buildBackupJson(app)
            val hash = sha256(JSONObject(backup.toString()).apply { remove("exportedAt") }.toString())
            if (!force && hash == prefs.getString(KEY_LAST_HASH, null)) return Result.UNCHANGED
            writeToTree(app.contentResolver, tree, backup.toString(2))
            prefs.edit()
                .putString(KEY_LAST_HASH, hash)
                .putLong(KEY_LAST_SUCCESS_MS, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
            Result.WRITTEN
        } catch (e: SecurityException) {
            fail(prefs, ACCESS_LOST, e)
        } catch (t: Throwable) {
            fail(prefs, "Backup failed: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun fail(prefs: SharedPreferences, message: String, t: Throwable): Result {
        Log.w(GrayscaleStateManager.LOG_TAG, "auto-backup: $message", t)
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
        return Result.FAILED
    }

    private fun writeToTree(resolver: ContentResolver, tree: Uri, text: String) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val dirUri = findChild(resolver, tree, rootId, SUBFOLDER)
            ?: DocumentsContract.createDocument(resolver, rootUri, Document.MIME_TYPE_DIR, SUBFOLDER)
            ?: error("could not create the $SUBFOLDER folder")
        val dirId = DocumentsContract.getDocumentId(dirUri)
        val fileUri = findChild(resolver, tree, dirId, FILE_NAME)
            ?: DocumentsContract.createDocument(resolver, dirUri, JSON_MIME, FILE_NAME)
            ?: error("could not create $FILE_NAME")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val truncated = try {
            resolver.openOutputStream(fileUri, "wt")?.use { it.write(bytes) } != null
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!truncated) {
            // Some providers do not support "wt". Plain "w" may leave old bytes after a shorter
            // file, so start from a fresh file instead.
            DocumentsContract.deleteDocument(resolver, fileUri)
            val fresh = DocumentsContract.createDocument(resolver, dirUri, JSON_MIME, FILE_NAME)
                ?: error("could not recreate $FILE_NAME")
            resolver.openOutputStream(fresh, "w")?.use { it.write(bytes) }
                ?: error("could not open $FILE_NAME")
        }
    }

    private fun findChild(resolver: ContentResolver, tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
