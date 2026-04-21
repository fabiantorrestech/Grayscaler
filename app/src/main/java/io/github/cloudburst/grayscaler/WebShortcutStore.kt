package io.github.cloudburst.grayscaler

import android.content.Context
import android.net.Uri

class WebShortcutStore(val context: Context) {

    private val path = context.filesDir.resolve("web_shortcuts.txt")

    // All known entries — manual entries persisted, launcher entries merged at runtime
    var entries: List<WebShortcutEntry> = emptyList()

    // Per-entry rules: id -> "ignore" | "enable" | "disable"
    private val rules: MutableMap<String, String> = mutableMapOf()

    // Only manual entries are written to disk
    private val manualEntries: MutableList<WebShortcutEntry> = mutableListOf()

    fun load() {
        manualEntries.clear()
        rules.clear()
        if (!path.exists()) return
        path.bufferedReader().forEachLine { line ->
            val parts = line.split("\t")
            when (parts.getOrNull(0)) {
                "manual" -> if (parts.size >= 5) {
                    manualEntries.add(
                        WebShortcutEntry(
                            id = parts[1],
                            label = parts[2],
                            url = parts[3],
                            browserPackage = parts[4].ifEmpty { null },
                            isManual = true
                        )
                    )
                }
                "rule" -> if (parts.size >= 3) {
                    rules[parts[1]] = parts[2]
                }
            }
        }
        entries = manualEntries.toList()
    }

    fun save() {
        val writer = path.outputStream().writer()
        manualEntries.forEach { e ->
            writer.write("manual\t${e.id}\t${e.label}\t${e.url}\t${e.browserPackage ?: ""}\n")
        }
        rules.forEach { (id, mode) ->
            writer.write("rule\t$id\t$mode\n")
        }
        writer.close()
    }

    // Called after launcher shortcuts are fetched — merges by origin so duplicates don't appear
    fun mergeLauncherEntries(launcherEntries: List<WebShortcutEntry>) {
        val seenOrigins = mutableSetOf<String>()
        val merged = mutableListOf<WebShortcutEntry>()
        for (e in launcherEntries + manualEntries) {
            val origin = extractOrigin(e.url) ?: continue
            if (seenOrigins.add(origin)) merged.add(e)
        }
        entries = merged
    }

    fun setRule(id: String, mode: String) {
        rules[id] = mode
    }

    fun ruleFor(id: String): String = rules[id] ?: "ignore"

    fun addManualEntry(label: String, url: String): WebShortcutEntry {
        val entry = WebShortcutEntry(
            id = "manual_${System.currentTimeMillis()}",
            label = label,
            url = url,
            browserPackage = null,
            isManual = true
        )
        manualEntries.add(entry)
        entries = entries + entry
        return entry
    }

    fun removeManualEntry(id: String) {
        manualEntries.removeAll { it.id == id }
        rules.remove(id)
        entries = entries.filter { it.id != id }
    }

    // Returns null = no match or ignored, true = enable grayscale, false = disable
    fun shouldGrayScale(url: String): Boolean? {
        val origin = extractOrigin(url) ?: return null
        val match = entries.firstOrNull { extractOrigin(it.url) == origin } ?: return null
        return when (ruleFor(match.id)) {
            "enable" -> true
            "disable" -> false
            else -> null
        }
    }

    companion object {
        fun extractOrigin(url: String): String? {
            return try {
                val uri = Uri.parse(
                    if (url.startsWith("http://") || url.startsWith("https://")) url
                    else "https://$url"
                )
                val host = uri.host ?: return null
                "${uri.scheme ?: "https"}://$host"
            } catch (_: Exception) {
                null
            }
        }

        fun faviconUrl(url: String): String {
            val host = try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
            return "https://www.google.com/s2/favicons?domain=$host&sz=64"
        }
    }
}
