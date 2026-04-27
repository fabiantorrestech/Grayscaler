<div align="center">

![Grayscaler+](./app/src/main/play_store_512.png)

# Grayscaler+

A heavily modified fork of [Grayscaler](https://github.com/Cloudburst-Apps/grayscaler) that turns your Android phone mostly monochrome while giving you even more granular control over whitelisting certain apps, views, overlays, and Android OS Events.

</div>

---

## Why does this exist?

There is [sufficient research](https://pmc.ncbi.nlm.nih.gov/articles/PMC10159421/) showing that using your phone in GrayScale mode makes it significantly less enticing to use.
I and many others who have tried this out can vouch for this claim anecdotally. For people who are trying to be more intentional about their tech usage,
this could be a great tool in their arsenal.

However, the issue is that remembering to enable/disable it (even if you configure a shortcut to do it) is tedious. And lets be honest - there are some genuine views/overlays that you want granular control over
when you want to view it in color (e.g. certain images/videos where you need color).

And while the first Grayscaler app laid the groundwork for this amazing idea on Android, the truth is that just enabling/disabling certain apps is too "black and white".
You need some granular control over things like app-overlays, keyboards, notifications, and OS events. (e.g. lockscreen, typing using an app keyboard (gboard), viewing photos for certain apps, etc..).


The fork takes the original concept and adds a full scheduling system, automation support, per-activity class matching, URL-based rules for browsers, a pause overlay, appearance customization, backup/restore, and more.

---

## New in Grayscaler+

The following are all additions on top of the original Grayscaler:

### Pause System
- Pause grayscale temporarily via 3 methods: floating overlay, shortcut, or in-app-UI menu
- Preset durations: 5s, 15s, 30s, 1m, 3m, 5m, 10m, 15m, 30m, 1hr.
- Custom duration input with minutes or seconds
- Ongoing notification while paused, with a "Cancel Pause" action
- Two notification styles: live countdown timer (last 5 minutes only) or static "resumes at X:XX" text.
- Grayscale resumes automatically when the pause expires

### Schedules
- Create time-based schedules that enable grayscale on specific days and time windows
- Each schedule picks its own app profile: use the global app list, or override it with a schedule-specific whitelist or blacklist
- Multiple schedules can coexist; the most recently activated one wins
- Schedules survive reboots via alarm registration on boot

### Web Shortcuts
- Define URL or domain-based rules per browser
- Enable or disable grayscale on specific websites regardless of the global app list
- Browser favicon previews in the rule list

### Per-App Views (Activity-level control)
- Match specific Activity classes or view names within an app, not just the package
- Built-in presets for common apps (photo viewers, etc.)
- Diagnostic mode: logs the current foreground class name in real time so you can find the right class to target
- Custom entries: add any package + class combination manually

### System UI Behavior
Configure grayscale behavior independently for each system context:
- Lockscreen
- App switcher / recents
- Power menu
- Inline reply (notification text fields)
- ~~Notification Shade (experimental)~~

### Overlay Ignore List
- Mark apps as ignored so they never trigger a grayscale change (useful for floating system overlays)
- Gemini/Assistant group toggle
- Built-in system ignores
- Add custom packages manually

### Quick Settings Tile
- Add a Grayscaler+ tile to your notification shade for an instant global on/off toggle

### Automation Integration (Tasker / Key Mapper / MacroDroid / Etc...)
Invoke the pause-shortcut menu via intent/shortcut from your favorite:

| Action | Broadcast |
|---|---|
| Open pause menu | `io.github.cloudburst.grayscaler.ACTION_PAUSE_GRAYSCALER` |
| Apply pause (minutes) | `io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE` + extra `minutes` (int) |
| Apply pause (seconds) | `io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE` + extra `seconds` (long) |
| Cancel pause | `io.github.cloudburst.grayscaler.ACTION_PAUSE_END` |
| Set enabled | `io.github.cloudburst.grayscaler.ACTION_SET_ENABLED` + extra `enabled` (boolean) |

All broadcasts must target `io.github.cloudburst.grayscaler` as the package.

#### Key Mapper Example
Set a shortcut to automatically pause for 30 minutes.

1. Create a new mapping, assign your trigger (button, gesture, etc.)
2. Add action: Send Broadcast
3. Set:                                                                                            
   - Action: io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE                                     
   - Package: io.github.cloudburst.grayscaler                                                       
   - Extra: 
     - key: minutes
     - type: Int
     - value: 30 (for 30 minutes)

#### Tasker Example
Set a shortcut to automatically pause for 30 minutes or 90 seconds.

1. Create a Task, add action: Send Intent
2. Set:                                                                                            
   - Action: io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE
   - Package: io.github.cloudburst.grayscaler                                                       
   - Target: Broadcast Receiver                            
   - Extra: minutes:30 (for 30 min) or seconds:90 (for 90 seconds)

▎ In Tasker's extra field the format is key:value. For hours, just multiply: 2 hours = minutes:120.

#### MacroDroid Example
Set a shortcut to pause for 30 minutes.

1. Add action: Send Intent
2. Set:
   - Action: io.github.cloudburst.grayscaler.ACTION_APPLY_PAUSE
   - Package: io.github.cloudburst.grayscaler                                                       
   - Target: Broadcast
   - Add extra:
     - key: minutes 
     - value: 30

### Persistent Overlay Mode
By default, the pause overlay is spawned on demand from a foreground service, which Android restricts inside system apps like Settings. Persistent overlay mode pre-loads the overlay inside the Accessibility Service using a higher-privilege window type so shortcuts work everywhere, including inside the Settings app. Toggle it from the Pause screen. When off, there is no additional resource cost.

### Appearance Customization
- Material You (dynamic color) toggle
- Custom primary, accent, and background colors via hex input
- Custom fonts for four text roles: body, header, subheader, tertiary
- Font files are loaded from device storage

### Backup and Restore
- Export all settings to a JSON file
- Import settings from a previously exported file
- Covers app lists, schedules, web rules, per-app views, appearance, and behavior prefs

---

## Full Feature Summary

| Feature | Details                                                              |
|---|----------------------------------------------------------------------|
| Whitelist mode | Listed apps stay in color; everything else is grayscale              |
| Blacklist mode | Listed apps are grayscale; everything else stays in color            |
| Schedules | Day + time window triggers with per-schedule app profiles            |
| Pause | Timed pause with presets, custom input, notifications                |
| Web shortcuts | Per-URL grayscale rules inside browsers                              |
| Per-app views | Activity class-level matching within apps                            |
| System UI modes | Per-context behavior for lockscreen, shade, recents, etc.            |
| Ignore list | Exclude overlays and floaters from triggering changes                |
| Quick Settings tile | One-tap toggle from the notification shade                           |
| Automation | Broadcast intents for Tasker, Key Mapper, MacroDroid                 |
| Persistent overlay | Pause menu available inside Settings and other system apps           |
| Appearance | Material You, custom colors, custom fonts                            |
| Backup/restore | Full settings export and import as JSON                              |
| Shizuku support | GUI permission granting via Shizuku (can use your own Shizuku forks) |
| ADB fallback | Manual permission grant for users without Shizuku                    |

---

## Requirements

- Android 7.0 (API 24) or higher
- Accessibility Service enabled for Grayscaler+
- `WRITE_SECURE_SETTINGS` permission (granted via Shizuku or ADB)

---

## Installation

1. Download and install the APK.
2. Open Grayscaler+ and follow the permissions screen.
3. Grant `WRITE_SECURE_SETTINGS` using Shizuku or ADB (see below).
4. Enable the Accessibility Service when prompted.
5. Choose whitelist or blacklist mode and configure your app list.

---

## Granting Permissions

### With Shizuku
Install and start [Shizuku](https://github.com/RikkaApps/Shizuku), then grant permissions from inside the app.

### With ADB (no Shizuku required)
Connect your phone to a computer with ADB and run:

```shell
adb shell pm grant io.github.cloudburst.grayscaler android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant io.github.cloudburst.grayscaler android.permission.PACKAGE_USAGE_STATS
adb shell pm grant io.github.cloudburst.grayscaler android.permission.QUERY_ALL_PACKAGES
```

Only needs to be done once. The permission survives app updates.

---

## License

GPL-3.0. See [LICENSE](./LICENSE).

Based on [Grayscaler](https://github.com/Cloudburst-Apps/grayscaler) by Cloudburst Apps.
