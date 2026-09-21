package io.github.cloudburst.grayscaler

import android.content.Context
import android.provider.Settings

/**
 * Reads the phone's display colour setting (Settings > Display > Colors) for diagnostics.
 *
 * Observed on a Pixel 7 / Android 16: while an accessibility colour filter is enabled the panel is
 * put in its native colour mode (hwc `mode(0), intent(0)`), which neither user option uses (Adaptive
 * is `mode(7), intent(1)`, Natural is `mode(7), intent(0)`). So every flip of the daltonizer
 * *enabled* flag switches the colour mode whatever the user picked. That is a global
 * CONFIG_COLOR_MODE change, and Android relaunches every visible activity that does not handle it.
 * Changing this setting does not avoid that; it is logged only so reports carry the full picture.
 */
object DisplayColorMode {
    private const val KEY = "display_color_mode"

    /** The raw system value, or -1 when the device does not expose it. */
    fun current(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, KEY, -1)
    } catch (_: Throwable) {
        -1
    }
}
