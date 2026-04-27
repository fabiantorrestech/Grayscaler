package io.github.cloudburst.grayscaler

import android.content.Context

object ScheduleProfiles {

    fun importAppListFromGlobal(context: Context, schedule: Schedule): Schedule {
        val store = AppListStore(context).also { it.load() }
        return schedule.copy(
            profileWhitelist = store.whitelistedApps,
            profileBlacklist = store.blacklistedApps
        )
    }

    fun importPerAppViewsFromGlobal(context: Context, schedule: Schedule): Schedule {
        val store = PerAppViewsStore(context)
        return schedule.copy(
            perAppViewsBuiltInEnabled = PerAppViewsStore.BUILT_IN_ENTRIES.associate { entry ->
                entry.id to store.isBuiltInEnabled(entry.id)
            },
            perAppViewsBuiltInPatterns = PerAppViewsStore.BUILT_IN_ENTRIES.associate { entry ->
                entry.id to store.getBuiltInPattern(entry)
            },
            perAppViewsCustomEntries = store.getCustomEntries().map {
                SchedulePerAppViewEntry(
                    packageName = it.packageName,
                    classPattern = it.classPattern,
                    enabled = it.enabled
                )
            }
        )
    }

    fun clearPerAppViews(schedule: Schedule): Schedule {
        return schedule.copy(
            perAppViewsBuiltInEnabled = emptyMap(),
            perAppViewsBuiltInPatterns = emptyMap(),
            perAppViewsCustomEntries = emptyList()
        )
    }

    fun importOverlayProfileFromGlobal(context: Context, schedule: Schedule): Schedule {
        val store = OverlayIgnoreStore(context).also { it.load() }
        return schedule.copy(
            overlayGeminiIgnored = store.geminiIgnored,
            overlayUserPackages = store.userPackages.toSet()
        )
    }

    fun clearOverlayProfile(schedule: Schedule): Schedule {
        return schedule.copy(
            overlayGeminiIgnored = true,
            overlayUserPackages = emptySet()
        )
    }

    fun importWebShortcutsFromGlobal(context: Context, schedule: Schedule): Schedule {
        val store = WebShortcutStore(context).also {
            it.load()
            it.refreshLauncherEntries()
        }
        return schedule.copy(
            webShortcutEntries = store.entries,
            webShortcutRules = store.entries.associate { it.id to store.ruleFor(it.id) }
        )
    }

    fun clearWebShortcuts(schedule: Schedule): Schedule {
        return schedule.copy(
            webShortcutEntries = emptyList(),
            webShortcutRules = emptyMap()
        )
    }

    fun matchesPerAppView(schedule: Schedule, pkg: String, className: String): Boolean {
        return PerAppViewsStore.matchesProfile(
            pkg = pkg,
            className = className,
            builtInEnabled = PerAppViewsStore.BUILT_IN_ENTRIES.associate { entry ->
                entry.id to (schedule.perAppViewsBuiltInEnabled[entry.id] ?: true)
            },
            builtInPatterns = PerAppViewsStore.BUILT_IN_ENTRIES.associate { entry ->
                entry.id to (schedule.perAppViewsBuiltInPatterns[entry.id] ?: entry.defaultPattern)
            },
            customEntries = schedule.perAppViewsCustomEntries.map {
                PerAppViewsStore.CustomEntry(it.packageName, it.classPattern, it.enabled)
            }
        )
    }

    fun effectiveOverlayIgnoreList(schedule: Schedule): Set<String> {
        val result = mutableSetOf<String>()
        result.addAll(OverlayIgnoreStore.SYSTEM_IGNORES)
        if (schedule.overlayGeminiIgnored) result.addAll(OverlayIgnoreStore.GEMINI_PACKAGES)
        result.addAll(schedule.overlayUserPackages)
        return result
    }

    fun shouldGrayScaleForScheduleWebShortcut(schedule: Schedule, url: String): Boolean? {
        val origin = WebShortcutStore.extractOrigin(url) ?: return null
        val match = schedule.webShortcutEntries.firstOrNull { entry ->
            WebShortcutStore.extractOrigin(entry.url) == origin
        } ?: return null
        return when (schedule.webShortcutRules[match.id] ?: "ignore") {
            "enable" -> true
            "disable" -> false
            else -> null
        }
    }
}
