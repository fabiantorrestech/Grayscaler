# GrayScaler+

Fork of Grayscaler: an accessibility service that turns the system grayscale filter (Secure `accessibility_display_daltonizer*`, needs `WRITE_SECURE_SETTINGS`) on or off per foreground app, schedule, bedtime, pause and System UI surface. Extras: WhatsApp read-status labels drawn over the grayscale ticks, a pause pill overlay, Glance widgets, a QS tile, settings auto-backup.

## Stack
Kotlin, Jetpack Compose (UI), Glance (widgets), Shizuku (grant helper). minSdk 24, target 35. Single process.

## Hot paths (run constantly; measure before and after changing them)
- `MainService.onAccessibilityEvent`: every subscribed event. Content-change and scroll events are only requested while WhatsApp labels, a browser rule or shade detection need them (`updateEventSubscription`).
- `WhatsAppReadIndicatorScanner.scan`: tree walk + one screenshot + pixel classification, at most about once a second while WhatsApp is in front; `MainService` debounces, throttles and retries it, plus a 1 Hz heartbeat.
- `GrayscaleStateManager.evaluate` / `applyToSystem`: every app switch; only writes Secure values that differ.
- `PauseCountdownOverlayManager` ticker and the widget tick alarm during a pause.

## Measuring
`PerfStats` counts events, root lookups, scans, screenshots, copies, redraws, ticks and hot prefs writes, with timed sections. See Developer settings → Performance counters (Reset / Refresh / Copy). The same sections are Perfetto trace sections (`GS:*`); the app is `profileable`, so release builds can be traced.

## Storage
- Almost all settings and runtime flags are in one SharedPreferences file, `grayscaler_prefs`. Every `apply()` rewrites the whole file, so keep writes off hot paths.
- Schedules, app lists etc. live in `filesDir`.
- Backup/export skip `SettingsBackupManager.transientPrefKeys`; auto-backup also skips `AutoBackup.NON_CONFIG_KEYS`.

## Build and publish
- JDK 21 (Gradle 8.14 can't run on Android Studio's bundled JDK 25): `JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2 ./gradlew :app:assembleRelease`
- Release signing and the versionCode (commit count) come from `fabiantorrestech/android-apps` (see its project `CLAUDE.md` and ADRs). Build branch: `master`.
- Publish from the PC: `pwsh ../android-apps/scripts/publish-local.ps1 -App grayscalerplus` (`-NoUpload` to only build and check the key). CI publishes on every push to `master`.

## Conventions
- Explicit, readable code; comments explain why.
- No tests unless asked.
- Agent handoff in `handoffContext/` (gitignored).
