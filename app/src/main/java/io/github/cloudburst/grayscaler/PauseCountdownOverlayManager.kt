package io.github.cloudburst.grayscaler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

class PauseCountdownOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var pillView: PauseCountdownView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pauseUntilMs: Long = 0L
    private var sessionMode: Boolean = false
    private var sessionStartMs: Long = 0L
    private var snappedLeft: Boolean = false
    private var isExpanded: Boolean = false

    // Size fields — set from prefs in createOverlay(), default = medium
    private var pillSizeDp: Int = 36
    private var pillExpandedWidthDp: Int = 160
    private var textSizeSp: Int = 14
    private var hideCollapsedText: Boolean = false
    private var startExpandedByDefault: Boolean = true
    private var autoCollapseEnabled: Boolean = true
    private var showAboveLockscreenSystem: Boolean = false
    private var overlayAnimationToken: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (sessionMode) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                pillView?.setDisplayMs(elapsed)
                handler.postDelayed(this, TICK_INTERVAL_MS)
            } else {
                val remaining = pauseUntilMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    hide()
                    return
                }
                pillView?.setDisplayMs(remaining)
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }
    private val autoCollapseRunnable = Runnable { collapseOverlay() }

    fun show(pauseUntilMs: Long) {
        this.pauseUntilMs = pauseUntilMs
        this.sessionMode = false
        loadBehaviorPrefs()
        val needsAttach = pillView == null
        if (needsAttach) createOverlay()
        val view = pillView ?: return
        cancelOverlayAnimation(view)
        applyShowState()
        if (needsAttach || view.alpha < 1f) {
            startFadeIn(view, waitForNextFrame = needsAttach)
        }
        view.setDisplayMs(pauseUntilMs - System.currentTimeMillis())
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    fun showSession(startMs: Long) {
        this.sessionStartMs = startMs
        this.sessionMode = true
        this.pauseUntilMs = 0L
        loadBehaviorPrefs()
        val needsAttach = pillView == null
        if (needsAttach) createOverlay()
        val view = pillView ?: return
        cancelOverlayAnimation(view)
        applyShowState()
        if (needsAttach || view.alpha < 1f) {
            startFadeIn(view, waitForNextFrame = needsAttach)
        }
        view.setDisplayMs(System.currentTimeMillis() - startMs)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(autoCollapseRunnable)
        isExpanded = false
        val view = pillView ?: return
        startFadeOut(view, waitForNextFrame = hasNaturallyExpired())
    }

    fun onConfigurationChanged() {
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - params.width)
        params.x = if (snappedLeft) 0 else maxX
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
        updateLayout(params)
    }

    private fun createOverlay() {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)

        val overlaySize = prefs.getString(AppearancePreferences.KEY_OVERLAY_SIZE,
            AppearancePreferences.OVERLAY_SIZE_MEDIUM) ?: AppearancePreferences.OVERLAY_SIZE_MEDIUM
        pillSizeDp          = when (overlaySize) { "small" -> 28; "large" -> 48; else -> 36 }
        pillExpandedWidthDp = when (overlaySize) { "small" -> 120; "large" -> 200; else -> 160 }
        textSizeSp          = when (overlaySize) { "small" -> 12; "large" -> 17; else -> 14 }
        hideCollapsedText   = prefs.getBoolean(AppearancePreferences.KEY_OVERLAY_HIDE_COLLAPSED_TEXT, false)
        startExpandedByDefault = prefs.getBoolean(AppearancePreferences.KEY_OVERLAY_START_EXPANDED, true)
        autoCollapseEnabled = prefs.getBoolean(AppearancePreferences.KEY_OVERLAY_AUTO_COLLAPSE, true)
        showAboveLockscreenSystem = prefs.getBoolean(
            AppearancePreferences.KEY_OVERLAY_SHOW_ABOVE_LOCKSCREEN_SYSTEM,
            false
        )

        val pillHeightPx = dp(pillSizeDp)
        val collapsedWidthPx = pillHeightPx
        val initialExpanded = startExpandedByDefault
        val initialWidthPx = if (initialExpanded) dp(pillExpandedWidthDp) else collapsedWidthPx
        val metrics = context.resources.displayMetrics
        val hasPosition = prefs.getBoolean(PREF_HAS_POSITION, false)
        val savedY = prefs.getInt(PREF_Y, 0)
        snappedLeft = prefs.getBoolean(PREF_SNAPPED_LEFT, false)
        isExpanded = initialExpanded

        val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (showAboveLockscreenSystem) WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0

        val params = WindowManager.LayoutParams(
            initialWidthPx,
            pillHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            windowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (hasPosition) {
                x = if (snappedLeft) 0 else max(0, metrics.widthPixels - collapsedWidthPx)
                y = savedY.coerceIn(0, max(0, metrics.heightPixels - pillHeightPx))
            } else {
                x = max(0, metrics.widthPixels - collapsedWidthPx - dp(24))
                y = max(dp(32), metrics.heightPixels / 3)
            }
        }

        val view = PauseCountdownView(context).apply {
            alpha = 0f
            setSnappedLeft(snappedLeft)
            setExpanded(initialExpanded)
        }
        view.setOnTouchListener(PillTouchListener())
        windowManager.addView(view, params)
        pillView = view
        layoutParams = params
    }

    private fun toggleExpanded() {
        setExpanded(!isExpanded)
    }

    private fun collapseOverlay() {
        if (!isExpanded) return
        setExpanded(false)
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val pillHeightPx = dp(pillSizeDp)
        val newWidth = if (expanded) dp(pillExpandedWidthDp) else pillHeightPx
        params.width = newWidth
        if (!snappedLeft) params.x = max(0, metrics.widthPixels - newWidth)
        pillView?.setExpanded(expanded)
        updateLayout(params)
        scheduleAutoCollapseIfNeeded()
    }

    private fun applyShowState() {
        if (startExpandedByDefault) {
            setExpanded(true)
        } else {
            setExpanded(false)
        }
    }

    private fun loadBehaviorPrefs() {
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        startExpandedByDefault = prefs.getBoolean(AppearancePreferences.KEY_OVERLAY_START_EXPANDED, true)
        autoCollapseEnabled = prefs.getBoolean(AppearancePreferences.KEY_OVERLAY_AUTO_COLLAPSE, true)
    }

    private fun startFadeIn(view: PauseCountdownView, waitForNextFrame: Boolean) {
        val animationToken = ++overlayAnimationToken
        val animateIn = Runnable {
            if (overlayAnimationToken != animationToken || pillView !== view) return@Runnable
            view.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .setListener(null)
                .start()
        }
        if (waitForNextFrame) {
            view.post(animateIn)
        } else {
            animateIn.run()
        }
    }

    private fun startFadeOut(view: PauseCountdownView, waitForNextFrame: Boolean) {
        val animationToken = ++overlayAnimationToken
        val animateOut = Runnable {
            if (overlayAnimationToken != animationToken || pillView !== view) return@Runnable
            view.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        view.animate().setListener(null)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        if (overlayAnimationToken != animationToken || pillView !== view) return
                        removeOverlayView(view)
                    }
                })
                .start()
        }
        if (waitForNextFrame) {
            view.post(animateOut)
        } else {
            animateOut.run()
        }
    }

    private fun cancelOverlayAnimation(view: PauseCountdownView) {
        overlayAnimationToken++
        view.animate().setListener(null)
        view.animate().cancel()
    }

    private fun removeOverlayView(view: PauseCountdownView) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        if (pillView === view) {
            pillView = null
            layoutParams = null
        }
    }

    private fun scheduleAutoCollapseIfNeeded() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (isExpanded && autoCollapseEnabled) {
            handler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS)
        }
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - params.width)
        snappedLeft = (params.x + params.width / 2) < metrics.widthPixels / 2
        params.x = if (snappedLeft) 0 else maxX
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
        updateLayout(params)
        pillView?.setSnappedLeft(snappedLeft)
        context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_HAS_POSITION, true)
            .putBoolean(PREF_SNAPPED_LEFT, snappedLeft)
            .putInt(PREF_Y, params.y)
            .apply()
    }

    private fun updateLayout(params: WindowManager.LayoutParams) {
        pillView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun hasNaturallyExpired(): Boolean =
        pauseUntilMs > 0L && pauseUntilMs <= System.currentTimeMillis()

    private fun remainingMsToDisplaySeconds(remainingMs: Long): Long {
        if (remainingMs <= 0L) return 0L
        val wholeSeconds = remainingMs / 1000L
        return if (remainingMs % 1000L == 0L) wholeSeconds else wholeSeconds + 1L
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private inner class PillTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragStarted = false
        private var longPressFired = false

        private val longPressRunnable = Runnable {
            longPressFired = true
            collapseOverlay()
            context.sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_GRAYSCALER).apply {
                setPackage(context.packageName)
            })
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragStarted = false
                    longPressFired = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(autoCollapseRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    params.x += dx.toInt()
                    params.y += dy.toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    val totalDx = (params.x - initialX).toFloat()
                    val totalDy = (params.y - initialY).toFloat()
                    if (!dragStarted && (abs(totalDx) > dp(DRAG_SLOP_DP) || abs(totalDy) > dp(DRAG_SLOP_DP))) {
                        dragStarted = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    val metrics = context.resources.displayMetrics
                    params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
                    params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
                    updateLayout(params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragStarted) {
                        params.x = initialX
                        params.y = initialY
                        updateLayout(params)
                        if (!longPressFired) toggleExpanded()
                    } else {
                        snapToEdge()
                    }
                    scheduleAutoCollapseIfNeeded()
                    dragStarted = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    params.x = initialX
                    params.y = initialY
                    val metrics = context.resources.displayMetrics
                    params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
                    params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
                    updateLayout(params)
                    scheduleAutoCollapseIfNeeded()
                    dragStarted = false
                    return true
                }
            }
            return false
        }
    }

    private inner class PauseCountdownView(context: Context) : View(context) {
        private var displayMs: Long = 0L
        private var isSnappedLeft: Boolean = false
        private var isExpanded: Boolean = false

        private val appIcon: Bitmap by lazy {
            val sizePx = dp(pillSizeDp - 8)
            val drawable = context.getDrawable(R.drawable.ic_qs_tile_white)!!
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val bmpCanvas = Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(bmpCanvas)
            bmp
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC111111")
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = dp(textSizeSp).toFloat()
        }

        private val outlinePaint = Paint(textPaint).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        private val bgRect = RectF()
        private val clipPath = Path()

        fun setDisplayMs(ms: Long) {
            displayMs = ms.coerceAtLeast(0L)
            invalidate()
        }

        fun setSnappedLeft(left: Boolean) {
            isSnappedLeft = left
            invalidate()
        }

        fun setExpanded(expanded: Boolean) {
            isExpanded = expanded
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f

            bgRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(bgRect, radius, radius, bgPaint)

            val totalSeconds = remainingMsToDisplaySeconds(displayMs)
            val icon = appIcon
            val iconDiameter = icon.width.toFloat()
            val iconPadding = dp(4).toFloat()
            val iconTop = (h - iconDiameter) / 2f

            if (!isExpanded) {
                // Collapsed: icon centered in circle, time label overlaid
                val iconLeft = (w - iconDiameter) / 2f
                clipPath.reset()
                clipPath.addCircle(w / 2f, h / 2f, radius - 1f, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawBitmap(icon, iconLeft, iconTop, null)
                canvas.restore()

                if (!hideCollapsedText) {
                    val label = when {
                        totalSeconds >= 3600L -> "${totalSeconds / 3600L}h"
                        totalSeconds >= 60L   -> "${totalSeconds / 60L}m"
                        else                  -> "${totalSeconds}s"
                    }
                    val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(label, w / 2f, baseline, outlinePaint)
                    canvas.drawText(label, w / 2f, baseline, textPaint)
                }
            } else {
                // Expanded: icon circle on the snapped edge side, time text in remaining space
                val iconLeft = if (isSnappedLeft) iconPadding else w - iconDiameter - iconPadding
                val iconCenterX = iconLeft + iconDiameter / 2f

                clipPath.reset()
                clipPath.addCircle(iconCenterX, h / 2f, iconDiameter / 2f - 1f, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawBitmap(icon, iconLeft, iconTop, null)
                canvas.restore()

                val text = when {
                    totalSeconds >= 3600L -> "${totalSeconds / 3600L}h"
                    else -> String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
                }

                val textCenterX = if (isSnappedLeft) {
                    (iconPadding + iconDiameter + iconPadding + w) / 2f
                } else {
                    (iconLeft - iconPadding) / 2f
                }
                val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(text, textCenterX, baseline, outlinePaint)
                canvas.drawText(text, textCenterX, baseline, textPaint)
            }
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 500L
        private const val AUTO_COLLAPSE_DELAY_MS = 3000L
        private const val OVERLAY_FADE_DURATION_MS = 180L
        private const val DRAG_SLOP_DP    = 12
        private const val LONG_PRESS_MS   = 500L

        private const val PREF_Y           = "countdown_pill_y"
        private const val PREF_SNAPPED_LEFT = "countdown_pill_snapped_left"
        private const val PREF_HAS_POSITION = "countdown_pill_has_pos"
    }
}
