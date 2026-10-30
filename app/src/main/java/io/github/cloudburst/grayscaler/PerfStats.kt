package io.github.cloudburst.grayscaler

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLongArray

/**
 * In-memory performance counters for the hot paths (accessibility events, WhatsApp scans, the
 * pause pill and widget ticks). Always on: an increment is one atomic add, a timed section two
 * clock reads. Shown, with per-minute rates since the last reset, in Developer settings, so a
 * change can be measured on the phone before and after. Timed sections also appear as trace
 * sections in Perfetto (the manifest marks the app profileable).
 */
object PerfStats {

    enum class Counter(val label: String) {
        EVENT_WINDOW_STATE("events: window state"),
        EVENT_WINDOWS_CHANGED("events: windows changed"),
        EVENT_VIEW_FOCUSED("events: view focused"),
        EVENT_CONTENT_WHATSAPP("events: content, WhatsApp"),
        EVENT_CONTENT_OTHER("events: content, other apps"),
        EVENT_SCROLL_WHATSAPP("events: scroll, WhatsApp"),
        EVENT_SCROLL_OTHER("events: scroll, other apps"),
        ROOT_LOOKUP("rootInActiveWindow calls"),
        FOREGROUND_EVAL("foreground evaluations"),
        SCAN_RUN("WhatsApp scans run"),
        SCAN_REUSED("scans reusing the last labels"),
        SCAN_EMPTY("scans with no labels"),
        TREE_NODES("tree nodes walked"),
        SCREENSHOT("screenshots taken"),
        SCREENSHOT_FAILED("screenshots failed"),
        COPY_BYTES_KB("bitmap KB copied"),
        LABEL_REDRAW("label overlay redraws"),
        HEARTBEAT_TICK("WhatsApp heartbeat ticks"),
        PILL_TICK("pause pill ticks"),
        PILL_REDRAW("pause pill redraws"),
        WIDGET_TICK("widget tick alarms"),
        PREFS_WRITE_FOREGROUND("prefs writes: last foreground"),
        PREFS_WRITE_DEBUG("prefs writes: debug keys"),
    }

    enum class Section(val label: String, val traceName: String) {
        EVENT("onAccessibilityEvent", "GS:event"),
        ROOT_LOOKUP("rootInActiveWindow", "GS:rootLookup"),
        FOREGROUND_EVAL("foreground evaluation", "GS:foreground"),
        TREE_WALK("WhatsApp tree walk", "GS:treeWalk"),
        SCREENSHOT("screenshot latency", "GS:screenshot"),
        BITMAP_COPY("bitmap copy", "GS:bitmapCopy"),
        CLASSIFY("pixel classify", "GS:classify"),
    }

    private val counts = AtomicLongArray(Counter.entries.size)
    private val sectionRuns = AtomicLongArray(Section.entries.size)
    private val sectionTotalNs = AtomicLongArray(Section.entries.size)
    private val sectionMaxNs = AtomicLongArray(Section.entries.size)

    @Volatile
    private var sinceElapsedMs = SystemClock.elapsedRealtime()

    fun inc(counter: Counter, by: Long = 1) {
        counts.addAndGet(counter.ordinal, by)
    }

    /** Runs [block] as a timed section. */
    inline fun <T> time(section: Section, block: () -> T): T {
        Trace.beginSection(section.traceName)
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            record(section, SystemClock.elapsedRealtimeNanos() - start)
            Trace.endSection()
        }
    }

    /** Records a section measured elsewhere (e.g. the screenshot callback's latency). */
    fun record(section: Section, durationNs: Long) {
        val i = section.ordinal
        sectionRuns.incrementAndGet(i)
        sectionTotalNs.addAndGet(i, durationNs)
        while (true) {
            val max = sectionMaxNs.get(i)
            if (durationNs <= max || sectionMaxNs.compareAndSet(i, max, durationNs)) break
        }
    }

    fun reset() {
        for (i in 0 until counts.length()) counts.set(i, 0)
        for (i in 0 until sectionRuns.length()) {
            sectionRuns.set(i, 0)
            sectionTotalNs.set(i, 0)
            sectionMaxNs.set(i, 0)
        }
        sinceElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Plain-text report: totals and per-minute rates since the last reset, then timed sections. */
    fun report(context: Context): String {
        val elapsedMs = (SystemClock.elapsedRealtime() - sinceElapsedMs).coerceAtLeast(1L)
        val minutes = elapsedMs / 60_000.0
        return buildString {
            append(String.format(Locale.US, "since reset: %.1f min\n", minutes))
            append("prefs file: ").append(prefsFileSizeBytes(context)).append(" bytes\n")
            append("\ncounters (total · per min)\n")
            for (counter in Counter.entries) {
                val total = counts.get(counter.ordinal)
                if (total == 0L) continue
                append(String.format(Locale.US, "%s: %d · %.1f\n", counter.label, total, total / minutes))
            }
            append("\nsections (runs · avg ms · max ms · total ms)\n")
            for (section in Section.entries) {
                val runs = sectionRuns.get(section.ordinal)
                if (runs == 0L) continue
                val totalMs = sectionTotalNs.get(section.ordinal) / 1_000_000.0
                val maxMs = sectionMaxNs.get(section.ordinal) / 1_000_000.0
                append(
                    String.format(
                        Locale.US, "%s: %d · %.2f · %.1f · %.0f\n",
                        section.label, runs, totalMs / runs, maxMs, totalMs
                    )
                )
            }
        }
    }

    private fun prefsFileSizeBytes(context: Context): Long =
        File(context.applicationInfo.dataDir, "shared_prefs/grayscaler_prefs.xml").length()
}
