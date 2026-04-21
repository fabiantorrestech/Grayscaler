package io.github.cloudburst.grayscaler

import android.content.Context

class OverlayIgnoreStore(private val context: Context) {

    private val path = context.filesDir.resolve("overlay_ignore.txt")

    var geminiIgnored: Boolean = true
    var userPackages: MutableSet<String> = mutableSetOf()

    fun load() {
        if (!path.exists()) {
            geminiIgnored = true
            userPackages = mutableSetOf()
            return
        }
        val lines = path.readLines().filter { it.isNotBlank() }
        geminiIgnored = lines.firstOrNull()?.removePrefix("gemini_ignored:") == "true"
        userPackages = lines.drop(1).toMutableSet()
    }

    fun save() {
        path.bufferedWriter().use { writer ->
            writer.write("gemini_ignored:$geminiIgnored\n")
            userPackages.forEach { writer.write("$it\n") }
        }
    }

    fun addPackage(pkg: String) {
        userPackages.add(pkg)
        save()
    }

    fun removePackage(pkg: String) {
        userPackages.remove(pkg)
        save()
    }

    fun updateGeminiIgnored(ignored: Boolean) {
        geminiIgnored = ignored
        save()
    }

    fun effectiveIgnoreList(): Set<String> {
        val result = mutableSetOf<String>()
        result.addAll(SYSTEM_IGNORES)
        if (geminiIgnored) result.addAll(GEMINI_PACKAGES)
        result.addAll(userPackages)
        return result
    }

    companion object {
        // Always ignored — not user-configurable
        val SYSTEM_IGNORES = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            "io.github.cloudburst.grayscaler"
        )

        // Grouped toggle: "Ignore Google Assistant / Gemini"
        val GEMINI_PACKAGES = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant"
        )

        // Known Shizuku distributions for auto-detection
        val KNOWN_SHIZUKU_PACKAGES = listOf(
            "moe.shizuku.privileged.api",
            "rikka.shizuku"
        )
    }
}
