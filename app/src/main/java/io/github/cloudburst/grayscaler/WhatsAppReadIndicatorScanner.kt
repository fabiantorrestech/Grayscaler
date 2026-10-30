package io.github.cloudburst.grayscaler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Walks WhatsApp's accessibility tree for message-status icons, captures one screenshot, and
 * classifies each candidate as Read / Delivered / Sent via average pixel color inside the node's
 * screen bounds. Falls back to the contentDescription text when the screenshot is unavailable.
 */
class WhatsAppReadIndicatorScanner(
    private val service: AccessibilityService,
) {
    data class Label(val bounds: Rect, val text: String, val isColored: Boolean, val timestamp: String? = null)

    /** A node whose text/contentDescription is a whole-string clock time (e.g. "10:30 PM"). */
    private data class TimestampNode(val rect: Rect, val time: String)

    /**
     * A wide node (a whole media bubble or row) whose description ends in a status word. Photo and
     * voice-note rows tend to report status this way instead of on a small tick node. Resolved to
     * real tick bounds after the walk, once every timestamp node is known.
     */
    private data class StatusContainer(val rect: Rect, val kind: Kind, val covers: List<Cover>)

    /**
     * Something drawn on top of part of the tree being walked: a sibling of the node (or of one of
     * its ancestors) that is drawn later. WhatsApp's gallery picker is one: it is a sheet inside the
     * conversation window, so the message rows stay in the accessibility tree underneath it.
     */
    private data class Cover(val rect: Rect, val name: String)

    // Result of the last scan that used a screenshot, and what the tree looked like then. A scan
    // that finds the same tree within LAST_SCAN_MAX_AGE_MS reuses the labels instead of copying a
    // full-screen bitmap again. A status change also changes the tick's description, so it shows
    // up in the signature; the age limit covers pixel-only changes (chat list, id-only ticks).
    private var lastSignature: String? = null
    private var lastLabels: List<Label>? = null
    private var lastScanAtMs = 0L

    fun forgetLastScan() {
        lastSignature = null
        lastLabels = null
    }

    /** Accessibility window id of the tree the last scan read (WhatsApp's window), or -1. */
    @Volatile
    var lastWindowId = -1
        private set

    /** When the last screenshot was requested. MainService spaces captures out using this. */
    @Volatile
    var lastCaptureAtMs = 0L
        private set

    fun interface ResultCallback {
        fun onResult(labels: List<Label>)
    }

    fun scan(debugDumpDescriptions: Boolean, callback: ResultCallback) {
        PerfStats.inc(PerfStats.Counter.SCAN_RUN)
        PerfStats.inc(PerfStats.Counter.ROOT_LOOKUP)
        val root = PerfStats.time(PerfStats.Section.ROOT_LOOKUP) { service.rootInActiveWindow }
        if (root == null) {
            if (debugDumpDescriptions) writeChatListSummary("scan: no rootInActiveWindow")
            callback.onResult(emptyList())
            return
        }
        lastWindowId = root.windowId
        val candidates = mutableListOf<Candidate>()
        val containers = mutableListOf<StatusContainer>()
        val timestampAnchors = mutableListOf<Rect>()
        // Conversation-view clock timestamps, kept separate from [timestampAnchors] so the new
        // "<time> • Status" pairing can never perturb the chat-list checkmark search.
        val conversationTimestamps = mutableListOf<TimestampNode>()
        val tabHits = mutableSetOf<String>()
        val convoDebugLines = if (debugDumpDescriptions) mutableListOf<String>() else null
        try {
            PerfStats.time(PerfStats.Section.TREE_WALK) {
                collectCandidates(root, candidates, containers, timestampAnchors, conversationTimestamps, tabHits, depth = 0, debugDumpDescriptions, convoDebugLines, covers = emptyList())
            }
        } finally {
            root.recycle()
        }
        val isChatListView = tabHits.containsAll(REQUIRED_TABS)
        // Media-row fallback is conversation-only: chat-list rows also carry "..., Read" on a wide
        // node, but their timestamp sits top-right with no tick beside it.
        if (!isChatListView) {
            candidates += resolveContainerCandidates(containers, conversationTimestamps, candidates, convoDebugLines)
        }
        dedupeCandidates(candidates)
        if (debugDumpDescriptions) {
            Log.d(TAG, "scan: ${candidates.size} candidates, ${timestampAnchors.size} timestamps, chatList=$isChatListView tabHits=$tabHits")
            candidates.forEach { Log.d(TAG, "  matched: kind=${it.kind} bounds=${it.bounds}") }
        }
        if (candidates.isEmpty() && !(isChatListView && timestampAnchors.isNotEmpty())) {
            if (debugDumpDescriptions) {
                if (isChatListView) {
                    writeChatListSummary(
                        "scan: no candidates and not a chat list. " +
                            "tabHits=$tabHits anchors=${timestampAnchors.size}"
                    )
                } else {
                    val header = "scan: 0 accepted candidates, view=conversation, tabHits=$tabHits"
                    val body = convoDebugLines?.joinToString("\n").orEmpty()
                    writeConvoSummary(if (body.isEmpty()) header else "$header\n$body")
                }
            }
            callback.onResult(emptyList())
            return
        }
        val signature = buildString {
            append(isChatListView).append('#')
            candidates.forEach { append(it.bounds.flattenToString()).append(it.kind).append(it.source).append(';') }
            append('#')
            timestampAnchors.forEach { append(it.flattenToString()).append(';') }
            append('#')
            conversationTimestamps.forEach { append(it.rect.flattenToString()).append(it.time).append(';') }
        }
        val reusable = lastLabels
        if (reusable != null && signature == lastSignature &&
            System.currentTimeMillis() - lastScanAtMs < LAST_SCAN_MAX_AGE_MS
        ) {
            PerfStats.inc(PerfStats.Counter.SCAN_REUSED)
            callback.onResult(reusable)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // No screenshot path on older devices — only the contentDescription fallback is usable.
            // Timestamp pairing is geometry-only, so it still works here.
            callback.onResult(candidates.mapNotNull { c ->
                val label = c.toFallbackLabel() ?: return@mapNotNull null
                if (isChatListView) label
                else label.copy(timestamp = pairConversationTimestamp(c.bounds, conversationTimestamps))
            })
            return
        }
        captureAndClassify(candidates, timestampAnchors, conversationTimestamps, isChatListView, debugDumpDescriptions, convoDebugLines, signature, callback)
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<Candidate>,
        containers: MutableList<StatusContainer>,
        timestampAnchors: MutableList<Rect>,
        conversationTimestamps: MutableList<TimestampNode>,
        tabHits: MutableSet<String>,
        depth: Int,
        debugDumpDescriptions: Boolean,
        convoDebugLines: MutableList<String>?,
        covers: List<Cover>,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        PerfStats.inc(PerfStats.Counter.TREE_NODES)
        // Capture any node whose whole text/desc is a clock time, for conversation-view pairing.
        collectConversationTimestamp(node, conversationTimestamps)
        // Diagnostic: with debug dumping on, record every node that carries text or a
        // contentDescription (capped). Reveals how WhatsApp exposes voice-note / media status,
        // which is NOT a plain "Read/Delivered/Sent" ImageView like text messages are.
        if (convoDebugLines != null && convoDebugLines.size < RAW_NODE_DUMP_CAP) {
            val cd = node.contentDescription?.toString()
            val tx = node.text?.toString()
            if (!cd.isNullOrBlank() || !tx.isNullOrBlank()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val density = service.resources.displayMetrics.density
                val wdp = if (density > 0f) (r.width() / density).toInt() else r.width()
                convoDebugLines += "node cls=${shortClassName(node.className)} w=${wdp}dp " +
                    "b=${r.toShortString()} cd=\"${truncForDebug(cd)}\" tx=\"${truncForDebug(tx)}\""
            }
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            if (debugDumpDescriptions) {
                val short = if (desc.length > 140) desc.substring(0, 140) + "…" else desc
                Log.d(TAG, "desc: \"$short\" cls=${node.className}")
            }
            val kind = classifyContentDescription(desc)
            if (kind != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val density = service.resources.displayMetrics.density
                val maxStatusWidthPx = (MAX_STATUS_NODE_WIDTH_DP * density).toInt()
                val widthPx = rect.width()
                val widthDp = (widthPx / density).toInt()
                val passWidth = !rect.isEmpty && widthPx <= maxStatusWidthPx
                // Too wide to be the tick itself. Keep it as a container only when the status word
                // is a trailing, delimited token ("Photo, 10:30 PM, Read"), so a caption such as
                // "I sent it" cannot produce a chip.
                val containerKind = if (!passWidth && !rect.isEmpty) trailingStatusKind(desc) else null
                val cover = if (passWidth) coveredBy(rect, covers) else null
                val sliver = passWidth && isSliver(rect)
                if (passWidth && cover == null && !sliver) {
                    out += Candidate(rect, kind, SOURCE_DESC)
                } else if (containerKind != null) {
                    containers += StatusContainer(rect, containerKind, covers)
                }
                if (convoDebugLines != null) {
                    val short = if (desc.length > 80) desc.substring(0, 80) + "…" else desc
                    val verdict = when {
                        rect.isEmpty -> "REJECT-empty-bounds"
                        cover != null -> "COVERED-by ${cover.name}"
                        sliver -> "REJECT-sliver"
                        passWidth -> "ACCEPT"
                        containerKind != null -> "CONTAINER(${widthDp}dp)"
                        else -> "REJECT-width(${widthDp}dp > ${MAX_STATUS_NODE_WIDTH_DP}dp)"
                    }
                    convoDebugLines += "kind=${kind.label} w=${widthDp}dp bounds=${rect.toShortString()} " +
                        "cls=${node.className} desc=\"$short\" $verdict"
                }
            } else {
                val trimmed = desc.trim()
                if (trimmed in REQUIRED_TABS) {
                    tabHits += trimmed
                } else if (isTimestampDescription(trimmed)) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) timestampAnchors += rect
                }
            }
        }
        // Tick nodes that carry no usable description (seen on media bubbles once
        // flagIncludeNotImportantViews exposes them): recognise them by WhatsApp's view id and let
        // the pixels decide the status.
        if (desc.isNullOrBlank() || classifyContentDescription(desc) == null) {
            val viewId = node.viewIdResourceName
            if (viewId != null && STATUS_VIEW_ID_SUFFIXES.any { viewId.endsWith(it) }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val density = service.resources.displayMetrics.density
                val narrow = !rect.isEmpty && rect.width() <= (MAX_STATUS_NODE_WIDTH_DP * density).toInt()
                val cover = if (narrow) coveredBy(rect, covers) else null
                val sliver = narrow && isSliver(rect)
                if (narrow && cover == null && !sliver) out += Candidate(rect, null, SOURCE_ID)
                convoDebugLines?.add(
                    "id=$viewId bounds=${rect.toShortString()} " + when {
                        !narrow -> "REJECT-by-id"
                        cover != null -> "COVERED-by ${cover.name}"
                        sliver -> "REJECT-sliver"
                        else -> "ACCEPT-by-id"
                    }
                )
            }
        }
        // Children are fetched up front because whether one child is covered depends on its
        // siblings: any sibling drawn later (higher drawing order, which is NOT the child index)
        // sits on top of it and of everything beneath it.
        val children = ArrayList<AccessibilityNodeInfo>(node.childCount)
        for (i in 0 until node.childCount) node.getChild(i)?.let { children += it }
        try {
            val drawn = children.map { child ->
                val rect = Rect().also { child.getBoundsInScreen(it) }
                DrawnChild(rect, child.drawingOrder, if (isCover(child, rect)) coverName(child) else null)
            }
            for ((index, child) in children.withIndex()) {
                val mine = drawn[index]
                var added: ArrayList<Cover>? = null
                for ((otherIndex, other) in drawn.withIndex()) {
                    if (otherIndex == index || other.coverName == null || other.order <= mine.order) continue
                    val list = added ?: ArrayList(covers).also { added = it }
                    list.add(Cover(other.rect, other.coverName))
                }
                collectCandidates(child, out, containers, timestampAnchors, conversationTimestamps, tabHits, depth + 1, debugDumpDescriptions, convoDebugLines, added ?: covers)
            }
        } finally {
            children.forEach { it.recycle() }
        }
    }

    private class DrawnChild(val rect: Rect, val order: Int, val coverName: String?)

    /**
     * Whether a later-drawn sibling really hides what is under it. WhatsApp keeps invisible
     * full-size placeholders on top of the message list (a video stub, a drag-and-drop overlay);
     * counting those would hide every label all the time. They are empty leaves, so a cover must
     * have children, or text, or be an image.
     */
    private fun isCover(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        if (rect.isEmpty || !node.isVisibleToUser) return false
        return node.childCount > 0 ||
            !node.text.isNullOrEmpty() ||
            !node.contentDescription.isNullOrEmpty() ||
            node.className?.endsWith("ImageView") == true
    }

    private fun coverName(node: AccessibilityNodeInfo): String =
        node.viewIdResourceName?.substringAfterLast('/') ?: shortClassName(node.className)

    private fun coveredBy(rect: Rect, covers: List<Cover>): Cover? {
        val x = rect.centerX()
        val y = rect.centerY()
        return covers.firstOrNull { it.rect.contains(x, y) }
    }

    /** A tick clipped by the edge of the list: only a few pixels of its row are on screen. */
    private fun isSliver(rect: Rect): Boolean =
        rect.height() < (MIN_TICK_HEIGHT_DP * service.resources.displayMetrics.density).toInt()

    /**
     * Records this node as a conversation timestamp iff its text or contentDescription is, in its
     * entirety, a short clock time (e.g. "10:30 PM", "13:45"). The whole-string + length guard
     * keeps ordinary message bodies that merely mention a time out of the anchor set.
     */
    private fun collectConversationTimestamp(node: AccessibilityNodeInfo, out: MutableList<TimestampNode>) {
        for (value in listOfNotNull(node.text?.toString(), node.contentDescription?.toString())) {
            val clock = clockTimeIfWholeString(value) ?: continue
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                out += TimestampNode(rect, clock)
                return
            }
        }
    }

    /**
     * Turns each status container into tick bounds: the short strip immediately right of the
     * bottom-most timestamp inside the container, which is where WhatsApp draws "<time> ✓✓" on
     * photo and voice-note bubbles. Containers that already hold a directly detected tick are
     * skipped, so ordinary text rows are untouched.
     */
    private fun resolveContainerCandidates(
        containers: List<StatusContainer>,
        timestamps: List<TimestampNode>,
        existing: List<Candidate>,
        convoDebugLines: MutableList<String>?,
    ): List<Candidate> {
        if (containers.isEmpty() || timestamps.isEmpty()) return emptyList()
        val density = service.resources.displayMetrics.density
        val stripWidthPx = (CONTAINER_TICK_STRIP_DP * density).toInt()
        val minStripPx = (CONTAINER_TICK_MIN_STRIP_DP * density).toInt()
        val maxBottomGapPx = (CONTAINER_TIME_BOTTOM_GAP_DP * density).toInt()
        val out = mutableListOf<Candidate>()
        for (c in containers) {
            if (existing.any { c.rect.contains(it.bounds.centerX(), it.bounds.centerY()) }) continue
            val ts = timestamps
                .filter { c.rect.contains(it.rect) && c.rect.bottom - it.rect.bottom <= maxBottomGapPx }
                .maxWithOrNull(compareBy<TimestampNode>({ it.rect.bottom }, { it.rect.right }))
            if (ts == null) {
                convoDebugLines?.add("  container ${c.rect.toShortString()} kind=${c.kind.label}: no bottom timestamp")
                continue
            }
            // The strip may run slightly past the container: the tick sits in the bubble's padding.
            val strip = Rect(ts.rect.right, ts.rect.top, ts.rect.right + stripWidthPx, ts.rect.bottom)
            if (strip.width() < minStripPx || strip.height() <= 0) continue
            val cover = coveredBy(strip, c.covers)
            if (cover != null) {
                convoDebugLines?.add("  container ${c.rect.toShortString()} strip COVERED-by ${cover.name}")
                continue
            }
            out += Candidate(strip, c.kind, SOURCE_CONTAINER)
            convoDebugLines?.add(
                "  container ${c.rect.toShortString()} kind=${c.kind.label} -> strip ${strip.toShortString()}"
            )
        }
        return out
    }

    /** Drops overlapping candidates, preferring a real tick node over an id match over a strip. */
    private fun dedupeCandidates(candidates: MutableList<Candidate>) {
        if (candidates.size < 2) return
        val kept = mutableListOf<Candidate>()
        for (c in candidates.sortedBy { it.source }) {
            if (kept.none { Rect.intersects(it.bounds, c.bounds) }) kept += c
        }
        candidates.clear()
        candidates += kept
    }

    private fun trailingStatusKind(desc: String): Kind? {
        val word = TRAILING_STATUS_PATTERN.find(desc)?.groupValues?.get(1)?.lowercase(Locale.US) ?: return null
        return when (word) {
            "read", "seen" -> Kind.READ
            "delivered" -> Kind.DELIVERED
            "sent" -> Kind.SENT
            else -> null
        }
    }

    private fun clockTimeIfWholeString(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 10) return null
        return if (CLOCK_TIME_PATTERN.matches(trimmed)) trimmed else null
    }

    private fun truncForDebug(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val oneLine = value.replace('\n', ' ')
        return if (oneLine.length > 40) oneLine.substring(0, 40) + "…" else oneLine
    }

    private fun shortClassName(cls: CharSequence?): String =
        cls?.toString()?.substringAfterLast('.') ?: "?"

    /**
     * Finds the clock time belonging to the same message row as [status]: the nearest timestamp
     * node that is vertically aligned with the status icon and sits just to its left (WhatsApp
     * renders "<time> ✓✓" on one line). Returns null when nothing qualifies, so the chip falls
     * back to status-only.
     */
    private fun pairConversationTimestamp(status: Rect, timestamps: List<TimestampNode>): String? {
        if (timestamps.isEmpty()) return null
        val density = service.resources.displayMetrics.density
        val vTol = max(status.height(), (TIME_ROW_V_TOL_DP * density).toInt())
        val maxGapPx = (MAX_TIME_STATUS_GAP_DP * density).toInt()
        val statusCenterY = status.centerY()
        val statusCenterX = status.centerX()
        var best: TimestampNode? = null
        var bestRight = Int.MIN_VALUE
        for (t in timestamps) {
            if (abs(t.rect.centerY() - statusCenterY) > vTol) continue   // not the same row
            if (t.rect.right > statusCenterX) continue                    // not to the left of the icon
            if (status.left - t.rect.right > maxGapPx) continue           // too far to belong to this row
            if (t.rect.right > bestRight) {                               // nearest one from the left
                bestRight = t.rect.right
                best = t
            }
        }
        return best?.time
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndClassify(
        candidates: List<Candidate>,
        timestampAnchors: List<Rect>,
        conversationTimestamps: List<TimestampNode>,
        isChatListView: Boolean,
        debug: Boolean,
        convoDebugLines: MutableList<String>?,
        signature: String,
        callback: ResultCallback,
    ) {
        lastCaptureAtMs = System.currentTimeMillis()
        PerfStats.inc(PerfStats.Counter.SCREENSHOT)
        val requestedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
        GrayscaleLog.log(service, "wa_screenshot", "request candidates=${candidates.size} chatList=$isChatListView")
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        PerfStats.record(PerfStats.Section.SCREENSHOT, android.os.SystemClock.elapsedRealtimeNanos() - requestedAtNs)
                        val hb = screenshot.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            val sw = PerfStats.time(PerfStats.Section.BITMAP_COPY) { hw?.copy(Bitmap.Config.ARGB_8888, false) }
                            if (sw != null) PerfStats.inc(PerfStats.Counter.COPY_BYTES_KB, sw.byteCount / 1024L)
                            if (sw == null) {
                                if (debug) writeChatListSummary("screenshot bitmap copy failed")
                                GrayscaleLog.log(service, "wa_screenshot", "bitmap copy failed")
                                callback.onResult(candidates.mapNotNull { it.toFallbackLabel() })
                                return
                            }
                            GrayscaleLog.log(service, "wa_screenshot", "ok")
                            val classifyStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                            val labels = mutableListOf<Label>()
                            for (c in candidates) {
                                var label = classifyByPixels(sw, c, convoDebugLines) ?: continue
                                if (!isChatListView) {
                                    val time = pairConversationTimestamp(c.bounds, conversationTimestamps)
                                    label = label.copy(timestamp = time)
                                    convoDebugLines?.add(
                                        "  time-pair[${c.kind?.label ?: "?"}]: ${time ?: "none"} " +
                                            "(status=${c.bounds.toShortString()}, ${conversationTimestamps.size} ts nodes)"
                                    )
                                }
                                labels += label
                            }
                            // Chat-list path is only used when we found no accessibility-tagged
                            // status nodes — i.e. we're on the chat list, where WhatsApp omits
                            // status from the accessibility tree entirely.
                            if (isChatListView && candidates.isEmpty()) {
                                labels += scanChatListPreviews(sw, timestampAnchors, debug)
                            }
                            sw.recycle()
                            PerfStats.record(PerfStats.Section.CLASSIFY, android.os.SystemClock.elapsedRealtimeNanos() - classifyStartNs)
                            if (debug && !isChatListView && convoDebugLines != null) {
                                val header = "scan: ${candidates.size} accepted candidates, view=conversation, labels=${labels.size}"
                                writeConvoSummary("$header\n" + convoDebugLines.joinToString("\n"))
                            }
                            lastSignature = signature
                            lastLabels = labels
                            lastScanAtMs = System.currentTimeMillis()
                            callback.onResult(labels)
                        } finally {
                            hb.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        PerfStats.inc(PerfStats.Counter.SCREENSHOT_FAILED)
                        Log.d(TAG, "takeScreenshot failed code=$errorCode — using contentDescription fallback")
                        if (debug) writeChatListSummary("takeScreenshot failed code=$errorCode")
                        GrayscaleLog.log(service, "wa_screenshot", "failed code=$errorCode")
                        callback.onResult(candidates.mapNotNull { it.toFallbackLabel() })
                    }
                }
            )
        } catch (t: Throwable) {
            Log.d(TAG, "takeScreenshot threw ${t.javaClass.simpleName} — using contentDescription fallback")
            if (debug) writeChatListSummary("takeScreenshot threw ${t.javaClass.simpleName}")
            GrayscaleLog.log(service, "wa_screenshot", "threw ${t.javaClass.simpleName}")
            callback.onResult(candidates.mapNotNull { it.toFallbackLabel() })
        }
    }

    /**
     * Pixel-only path used on the chat-list screen, where WhatsApp does not expose any
     * read/delivered/sent contentDescription. For each timestamp row anchor, examine a narrow
     * vertical strip in the message-preview line just to the right of the avatar — that's where
     * the leading ✓✓ icon lives if the row's last message was outgoing.
     */
    private fun scanChatListPreviews(bmp: Bitmap, anchors: List<Rect>, debug: Boolean): List<Label> {
        val density = service.resources.displayMetrics.density
        val avatarRightPx = (AVATAR_END_DP * density).toInt()
        val searchWidthPx = (CHECKMARK_SEARCH_WIDTH_DP * density).toInt()
        val previewTopOffsetPx = (PREVIEW_LINE_TOP_OFFSET_DP * density).toInt()
        val previewBottomOffsetPx = (PREVIEW_LINE_BOTTOM_OFFSET_DP * density).toInt()
        val maxIconWidthPx = (MAX_CHATLIST_ICON_WIDTH_DP * density).toInt()
        val minIconWidthPx = (MIN_CHATLIST_ICON_WIDTH_DP * density).toInt()

        val labels = mutableListOf<Label>()
        val summaryLines = mutableListOf<String>()
        if (debug) {
            summaryLines += "density=$density bmp=${bmp.width}x${bmp.height}"
            summaryLines += "geom: avatarRight=${avatarRightPx} searchW=${searchWidthPx} " +
                "topOff=${previewTopOffsetPx} botOff=${previewBottomOffsetPx} " +
                "iconW=${minIconWidthPx}..${maxIconWidthPx}"
            summaryLines += "anchors=${anchors.size}"
        }

        for ((index, anchor) in anchors.withIndex()) {
            val region = Rect(
                avatarRightPx,
                anchor.bottom + previewTopOffsetPx,
                avatarRightPx + searchWidthPx,
                anchor.bottom + previewBottomOffsetPx,
            )
            val blob = findLeadingIconBox(bmp, region)
            val line = StringBuilder()
            if (debug) {
                line.append("[$index] anchor=${anchor.toShortString()} region=${region.toShortString()} ")
                line.append("bgRgb=#${"%02X%02X%02X".format(
                    Color.red(blob.bgRgb), Color.green(blob.bgRgb), Color.blue(blob.bgRgb)
                )} ")
            }
            val box = blob.bbox
            if (box == null) {
                if (debug) {
                    line.append("REJECT: ${blob.rejectReason ?: "no-blob"}")
                    Log.d(TAG, line.toString())
                    summaryLines += line.toString()
                }
                continue
            }
            val iconW = box.width()
            if (iconW < minIconWidthPx || iconW > maxIconWidthPx) {
                if (debug) {
                    line.append("REJECT: iconW=${iconW} not in ${minIconWidthPx}..${maxIconWidthPx} (box=${box.toShortString()})")
                    Log.d(TAG, line.toString())
                    summaryLines += line.toString()
                }
                continue
            }

            val color = averageColor(bmp, box)
            val ar = Color.red(color)
            val ag = Color.green(color)
            val ab = Color.blue(color)
            val delta = max(max(abs(ar - ag), abs(ag - ab)), abs(ar - ab))
            val isColored = delta > COLOR_DELTA_THRESHOLD
            val aspect = iconW.toFloat() / box.height().coerceAtLeast(1)
            val text = when {
                isColored && ab > ar && ab > ag -> "Read"
                aspect >= DOUBLE_CHECK_ASPECT_THRESHOLD -> "Delivered"
                else -> "Sent"
            }
            labels += Label(box, text, isColored)
            if (debug) {
                line.append("ACCEPT: box=${box.toShortString()} ar=$ar ag=$ag ab=$ab delta=$delta aspect=${"%.2f".format(aspect)} -> $text")
                Log.d(TAG, line.toString())
                summaryLines += line.toString()
            }
        }

        if (debug) {
            summaryLines += "labels emitted=${labels.size}"
            writeChatListSummary(summaryLines.joinToString("\n"))
        }
        return labels
    }

    private fun writeChatListSummary(summary: String) {
        PerfStats.inc(PerfStats.Counter.PREFS_WRITE_DEBUG)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            service.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_WHATSAPP_CHATLIST_DEBUG, "$ts\n$summary")
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun writeConvoSummary(summary: String) {
        PerfStats.inc(PerfStats.Counter.PREFS_WRITE_DEBUG)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            service.getSharedPreferences("grayscaler_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_WHATSAPP_CONVO_DEBUG, "$ts\n$summary")
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun averageColor(bmp: Bitmap, rect: Rect): Int {
        val w = bmp.width
        val h = bmp.height
        val x0 = rect.left.coerceIn(0, w - 1)
        val x1 = rect.right.coerceIn(0, w - 1)
        val y0 = rect.top.coerceIn(0, h - 1)
        val y1 = rect.bottom.coerceIn(0, h - 1)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var n = 0L
        val stepX = max(1, (x1 - x0) / SAMPLE_STRIDE)
        val stepY = max(1, (y1 - y0) / SAMPLE_STRIDE)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bmp.getPixel(x, y)
                sumR += Color.red(c); sumG += Color.green(c); sumB += Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return Color.GRAY
        return Color.rgb((sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt())
    }

    private fun isTimestampDescription(value: String): Boolean {
        if (value.length > 32) return false
        return TIMESTAMP_PATTERNS.any { it.matches(value) }
    }

    private fun classifyByPixels(
        bmp: Bitmap,
        candidate: Candidate,
        convoDebugLines: MutableList<String>? = null,
    ): Label? {
        // Find the actual visible icon inside the (often over-padded) accessibility node bounds.
        val iconBox = findVisibleIconBox(bmp, candidate.bounds)
        // A tick node proves itself. An id match or a guessed strip does not, so with no painted
        // icon inside it there is nothing to label.
        if (iconBox == null && candidate.source != SOURCE_DESC) {
            convoDebugLines?.add("  classify[${candidate.sourceName}]: no icon in ${candidate.bounds.toShortString()} -> skipped")
            return null
        }
        val sampleRect = iconBox ?: candidate.bounds
        val labelBounds = iconBox ?: candidate.bounds

        val w = bmp.width
        val h = bmp.height
        // Color-sample an inset block at the centre of the (smaller, icon-tight) region.
        val insetX = max(1, sampleRect.width() / 5)
        val insetY = max(1, sampleRect.height() / 5)
        val x0 = (sampleRect.left + insetX).coerceIn(0, w - 1)
        val x1 = (sampleRect.right - insetX).coerceIn(0, w - 1)
        val y0 = (sampleRect.top + insetY).coerceIn(0, h - 1)
        val y1 = (sampleRect.bottom - insetY).coerceIn(0, h - 1)
        if (x1 <= x0 || y1 <= y0) {
            return candidate.toFallbackLabel()?.copy(bounds = labelBounds)
        }

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var n = 0L
        val stepX = max(1, (x1 - x0) / SAMPLE_STRIDE)
        val stepY = max(1, (y1 - y0) / SAMPLE_STRIDE)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bmp.getPixel(x, y)
                sumR += Color.red(c)
                sumG += Color.green(c)
                sumB += Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) {
            return candidate.toFallbackLabel()?.copy(bounds = labelBounds)
        }

        val ar = (sumR / n).toInt()
        val ag = (sumG / n).toInt()
        val ab = (sumB / n).toInt()
        val delta = max(max(abs(ar - ag), abs(ag - ab)), abs(ar - ab))
        val isColored = delta > COLOR_DELTA_THRESHOLD
        val text = when {
            isColored && ab > ar && ab > ag -> "Read"
            candidate.kind != null -> candidate.kind.label
            // No description to go by: a double tick is wider than it is tall, a single tick is not.
            sampleRect.width().toFloat() / sampleRect.height().coerceAtLeast(1) >=
                DOUBLE_CHECK_ASPECT_THRESHOLD -> "Delivered"
            else -> "Sent"
        }
        if (convoDebugLines != null) {
            val iconLabel = iconBox?.toShortString() ?: "null(fallback-to-bounds)"
            convoDebugLines += "  classify[${candidate.sourceName}/${candidate.kind?.label ?: "?"}]: iconBox=$iconLabel " +
                "rgb=($ar,$ag,$ab) delta=$delta colored=$isColored -> $text"
        }
        return Label(labelBounds, text, isColored)
    }

    /**
     * Find the bounding rectangle of pixels inside [bounds] that differ meaningfully from the
     * background sampled at the four corners. WhatsApp's accessibility node bounds often include
     * tap-target padding around (and especially below) the visible checkmark; this trims to the
     * actual painted icon, which positions chips correctly and tightens color sampling.
     */
    private fun findVisibleIconBox(bmp: Bitmap, bounds: Rect): Rect? {
        val blob = detectBlob(bmp, bounds)
        if (blob.bbox == null) return null
        if (blob.areaFrac < MIN_ICON_AREA_FRAC) return null
        return blob.bbox
    }

    private data class BlobResult(val bbox: Rect?, val areaFrac: Float, val bgRgb: Int)

    private data class LeadingBlobResult(val bbox: Rect?, val bgRgb: Int, val rejectReason: String?)

    /**
     * Chat-list-specialised blob detector. Walks columns left→right inside [region], picks the
     * first run of "filled" (non-background) columns, and terminates the blob the first time
     * [GAP_TOLERANCE_PX] consecutive background columns appear. Returns the leading blob only —
     * any trailing preview text in the same row is ignored.
     *
     * Background is estimated by taking the channel-wise median across the first
     * [BG_SAMPLE_COLUMNS] columns of [region], which sit between the avatar and the icon and
     * are reliably empty padding. This avoids the corner-pixel-averaging failure mode of
     * [detectBlob] (text touching a corner skews bg → union bbox spans the whole row).
     */
    private fun findLeadingIconBox(bmp: Bitmap, region: Rect): LeadingBlobResult {
        val w = bmp.width
        val h = bmp.height
        val x0 = region.left.coerceIn(0, w - 1)
        val x1 = region.right.coerceIn(0, w - 1)
        val y0 = region.top.coerceIn(0, h - 1)
        val y1 = region.bottom.coerceIn(0, h - 1)
        val regionW = x1 - x0 + 1
        val regionH = y1 - y0 + 1
        if (regionW <= BG_SAMPLE_COLUMNS + 2 || regionH <= 2) {
            return LeadingBlobResult(null, Color.GRAY, "region-too-small")
        }

        val pixels = IntArray(regionW * regionH)
        bmp.getPixels(pixels, 0, regionW, x0, y0, regionW, regionH)

        // Channel-wise median bg over the leftmost BG_SAMPLE_COLUMNS columns.
        val sampleCount = BG_SAMPLE_COLUMNS * regionH
        val rSamples = IntArray(sampleCount)
        val gSamples = IntArray(sampleCount)
        val bSamples = IntArray(sampleCount)
        var n = 0
        for (y in 0 until regionH) {
            val base = y * regionW
            for (x in 0 until BG_SAMPLE_COLUMNS) {
                val c = pixels[base + x]
                rSamples[n] = Color.red(c)
                gSamples[n] = Color.green(c)
                bSamples[n] = Color.blue(c)
                n++
            }
        }
        rSamples.sort(); gSamples.sort(); bSamples.sort()
        val bgR = rSamples[n / 2]
        val bgG = gSamples[n / 2]
        val bgB = bSamples[n / 2]
        val bgRgb = Color.rgb(bgR, bgG, bgB)

        val columnFilled = BooleanArray(regionW)
        for (x in 0 until regionW) {
            for (y in 0 until regionH) {
                val c = pixels[y * regionW + x]
                val d = abs(Color.red(c) - bgR) + abs(Color.green(c) - bgG) + abs(Color.blue(c) - bgB)
                if (d > BG_DELTA_THRESHOLD) {
                    columnFilled[x] = true
                    break
                }
            }
        }

        var xStart = -1
        for (x in 0 until regionW) {
            if (columnFilled[x]) { xStart = x; break }
        }
        if (xStart < 0) return LeadingBlobResult(null, bgRgb, "no-leading-blob")

        var xEnd = xStart
        var gap = 0
        for (x in xStart + 1 until regionW) {
            if (columnFilled[x]) {
                xEnd = x
                gap = 0
            } else {
                gap++
                if (gap >= GAP_TOLERANCE_PX) break
            }
        }

        var minY = regionH
        var maxY = -1
        for (y in 0 until regionH) {
            for (x in xStart..xEnd) {
                val c = pixels[y * regionW + x]
                val d = abs(Color.red(c) - bgR) + abs(Color.green(c) - bgG) + abs(Color.blue(c) - bgB)
                if (d > BG_DELTA_THRESHOLD) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    break
                }
            }
        }
        if (maxY < minY) return LeadingBlobResult(null, bgRgb, "no-pixels-in-blob")

        return LeadingBlobResult(
            Rect(x0 + xStart, y0 + minY, x0 + xEnd + 1, y0 + maxY + 1),
            bgRgb,
            null,
        )
    }

    /**
     * Raw blob detector. Returns the bounding rectangle of pixels inside [bounds] that differ
     * meaningfully from the four-corner-averaged background, alongside the area fraction and the
     * estimated background RGB. Callers may apply their own minimum-area-fraction sanity check
     * (see [MIN_ICON_AREA_FRAC] for the standard one used by [findVisibleIconBox]).
     */
    private fun detectBlob(bmp: Bitmap, bounds: Rect): BlobResult {
        val w = bmp.width
        val h = bmp.height
        val x0 = bounds.left.coerceIn(0, w - 1)
        val x1 = bounds.right.coerceIn(0, w - 1)
        val y0 = bounds.top.coerceIn(0, h - 1)
        val y1 = bounds.bottom.coerceIn(0, h - 1)
        val regionW = x1 - x0 + 1
        val regionH = y1 - y0 + 1
        if (regionW <= 2 || regionH <= 2) return BlobResult(null, 0f, Color.GRAY)

        val pixels = IntArray(regionW * regionH)
        bmp.getPixels(pixels, 0, regionW, x0, y0, regionW, regionH)

        // Background estimate: average of the four corner pixels of the region.
        val cTL = pixels[0]
        val cTR = pixels[regionW - 1]
        val cBL = pixels[(regionH - 1) * regionW]
        val cBR = pixels[regionH * regionW - 1]
        val bgR = (Color.red(cTL) + Color.red(cTR) + Color.red(cBL) + Color.red(cBR)) / 4
        val bgG = (Color.green(cTL) + Color.green(cTR) + Color.green(cBL) + Color.green(cBR)) / 4
        val bgB = (Color.blue(cTL) + Color.blue(cTR) + Color.blue(cBL) + Color.blue(cBR)) / 4
        val bgRgb = Color.rgb(bgR, bgG, bgB)

        var minX = regionW
        var maxX = -1
        var minY = regionH
        var maxY = -1
        for (y in 0 until regionH) {
            val rowBase = y * regionW
            for (x in 0 until regionW) {
                val c = pixels[rowBase + x]
                val d = abs(Color.red(c) - bgR) + abs(Color.green(c) - bgG) + abs(Color.blue(c) - bgB)
                if (d > BG_DELTA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return BlobResult(null, 0f, bgRgb)

        val detectedW = maxX - minX + 1
        val detectedH = maxY - minY + 1
        val areaFrac = (detectedW * detectedH).toFloat() / (regionW * regionH)
        val bbox = Rect(x0 + minX, y0 + minY, x0 + maxX + 1, y0 + maxY + 1)
        return BlobResult(bbox, areaFrac, bgRgb)
    }

    private fun classifyContentDescription(desc: String): Kind? {
        // Word-boundary match so chat-list rows whose contentDescription embeds the keyword in a
        // longer string (e.g. "Last message: yes!! ^_^, Read") still classify. False positives are
        // mitigated by the pixel-localization step, which bails on bounds with no visible icon.
        return when {
            READ_PATTERN.containsMatchIn(desc) -> Kind.READ
            DELIVERED_PATTERN.containsMatchIn(desc) -> Kind.DELIVERED
            SENT_PATTERN.containsMatchIn(desc) -> Kind.SENT
            else -> null
        }
    }

    /**
     * @param kind status read from a description, or null when only pixels can tell (id match).
     * @param source how the tick was found; lower values win when candidates overlap.
     */
    private data class Candidate(val bounds: Rect, val kind: Kind?, val source: Int) {
        val sourceName: String
            get() = when (source) {
                SOURCE_DESC -> "desc"
                SOURCE_ID -> "id"
                else -> "container"
            }

        fun toFallbackLabel(): Label? =
            kind?.let { Label(bounds, it.label, isColored = it == Kind.READ) }
    }

    private enum class Kind(val label: String) {
        READ("Read"),
        DELIVERED("Delivered"),
        SENT("Sent");
    }

    companion object {
        const val PREF_WHATSAPP_CHATLIST_DEBUG = "whatsapp_chatlist_debug"
        const val PREF_WHATSAPP_CONVO_DEBUG = "whatsapp_convo_debug"
        private const val TAG = "WhatsAppScanner"
        private const val MAX_TREE_DEPTH = 64
        private const val SAMPLE_STRIDE = 6
        private const val COLOR_DELTA_THRESHOLD = 12
        private const val BG_DELTA_THRESHOLD = 60  // sum-of-channel-deltas vs corner background
        private const val MIN_ICON_AREA_FRAC = 0.003f  // ignore noise blobs in mostly-empty bounds

        // Word-boundary regex match. Localizable: add alternates inside the alternation group
        // (e.g. "(?:read|leído|lu)") if other languages report missing labels.
        // Negative lookahead on (more|less) excludes WhatsApp's "Read more" / "Read less" expander
        // on long incoming messages, which would otherwise be classified as a Read status by the
        // pixel-color path (the expander is blue text).
        private val READ_PATTERN = Regex("\\b(?:read|seen)\\b(?!\\s+(?:more|less))", RegexOption.IGNORE_CASE)
        private val DELIVERED_PATTERN = Regex("\\b(?:delivered)\\b", RegexOption.IGNORE_CASE)
        private val SENT_PATTERN = Regex("\\b(?:sent)\\b", RegexOption.IGNORE_CASE)

        // Backstop for the "Read more" false positive in case the regex misses a localized variant:
        // status icons are tiny ImageView nodes (~12-18dp wide); any candidate whose bounds exceed
        // this width is almost certainly a TextView (e.g. "Read more" expander) and gets rejected.
        private const val MAX_STATUS_NODE_WIDTH_DP = 40
        private const val MIN_TICK_HEIGHT_DP = 6
        private const val LAST_SCAN_MAX_AGE_MS = 10_000L

        private const val SOURCE_DESC = 0
        private const val SOURCE_ID = 1
        private const val SOURCE_CONTAINER = 2

        // WhatsApp's message-status ImageView. Needs flagReportViewIds in the service config.
        private val STATUS_VIEW_ID_SUFFIXES = listOf(":id/status")

        // Status word as the last delimited token of a longer description.
        private val TRAILING_STATUS_PATTERN = Regex(
            """(?:^|[,;\n])\s*(read|seen|delivered|sent)\s*\.?\s*$""",
            RegexOption.IGNORE_CASE
        )
        // Media-bubble geometry: tick strip to the right of the bottom timestamp.
        private const val CONTAINER_TICK_STRIP_DP = 24
        private const val CONTAINER_TICK_MIN_STRIP_DP = 6
        private const val CONTAINER_TIME_BOTTOM_GAP_DP = 20

        // Chat-list view signals — presence of all four tab descriptions confirms we're on
        // the chat list (not inside a conversation), where WhatsApp omits status from a11y.
        private val REQUIRED_TABS = setOf("Chats", "Updates", "Communities", "Calls")

        // Conversation-view "<time> • Status" pairing. A whole-string clock time (12- or 24-hour)
        // identifies a timestamp node; a paired timestamp must be within these bounds of the status
        // icon (same row, just to its left).
        private val CLOCK_TIME_PATTERN = Regex("""\d{1,2}:\d{2}\s?(?:[AaPp][Mm])?""")
        private const val MAX_TIME_STATUS_GAP_DP = 72
        private const val TIME_ROW_V_TOL_DP = 14

        // Cap on the number of raw tree nodes recorded into the conversation debug dump.
        private const val RAW_NODE_DUMP_CAP = 160

        // Per-row timestamp patterns used as anchors for chat-list pixel detection.
        private val TIMESTAMP_PATTERNS = listOf(
            Regex("""^\d{1,2}:\d{2}\s?(?:AM|PM|am|pm)?$"""),
            Regex("""^[A-Z][a-z]+\s\d{1,2}(?:,\s\d{4})?$"""),
            Regex("""^(?:Yesterday|Today)$"""),
            Regex("""^(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)$"""),
        )

        // Chat-list checkmark search-region geometry (dp units; converted with display density).
        private const val AVATAR_END_DP = 72
        private const val CHECKMARK_SEARCH_WIDTH_DP = 32
        private const val PREVIEW_LINE_TOP_OFFSET_DP = 2
        private const val PREVIEW_LINE_BOTTOM_OFFSET_DP = 36
        private const val MIN_CHATLIST_ICON_WIDTH_DP = 6
        private const val MAX_CHATLIST_ICON_WIDTH_DP = 28
        private const val DOUBLE_CHECK_ASPECT_THRESHOLD = 1.3f

        // Chat-list leading-blob detector tunables. BG_SAMPLE_COLUMNS = how many leftmost columns
        // (just past the avatar) to median for the bg estimate. GAP_TOLERANCE_PX = how many
        // consecutive bg-coloured columns terminate the leading blob (separates the icon from
        // following preview text).
        private const val BG_SAMPLE_COLUMNS = 4
        private const val GAP_TOLERANCE_PX = 4
    }
}
