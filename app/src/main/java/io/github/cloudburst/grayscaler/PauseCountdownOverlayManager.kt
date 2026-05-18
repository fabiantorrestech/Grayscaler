package io.github.cloudburst.grayscaler

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.min

class PauseCountdownOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var pillView: PauseCountdownView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pauseUntilMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val remaining = pauseUntilMs - System.currentTimeMillis()
            if (remaining <= 0L) {
                hide()
                return
            }
            pillView?.setRemainingMs(remaining)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun show(pauseUntilMs: Long) {
        this.pauseUntilMs = pauseUntilMs
        if (pillView == null) createOverlay()
        pillView?.setRemainingMs(pauseUntilMs - System.currentTimeMillis())
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        pillView?.let {
            try { windowManager.removeView(it) } catch (_: IllegalArgumentException) {}
        }
        pillView = null
        layoutParams = null
    }

    private fun createOverlay() {
        val pillWidthPx = dp(PILL_WIDTH_DP)
        val pillHeightPx = dp(PILL_HEIGHT_DP)
        val prefs = context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE)
        val metrics = context.resources.displayMetrics
        val savedX = prefs.getInt(PREF_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(PREF_Y, Int.MIN_VALUE)
        val snappedLeft = prefs.getBoolean(PREF_SNAPPED_LEFT, false)

        val params = WindowManager.LayoutParams(
            pillWidthPx,
            pillHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (savedX != Int.MIN_VALUE) {
                x = if (snappedLeft) 0 else max(0, metrics.widthPixels - pillWidthPx)
                y = savedY.coerceIn(0, max(0, metrics.heightPixels - pillHeightPx))
            } else {
                x = max(0, metrics.widthPixels - pillWidthPx - dp(24))
                y = max(dp(32), metrics.heightPixels / 3)
            }
        }

        val view = PauseCountdownView(context)
        view.setOnTouchListener(PillTouchListener())
        windowManager.addView(view, params)
        pillView = view
        layoutParams = params
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - params.width)
        val snappedLeft = (params.x + params.width / 2) < metrics.widthPixels / 2
        params.x = if (snappedLeft) 0 else maxX
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
        updateLayout(params)
        context.getSharedPreferences("grayscaler_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_SNAPPED_LEFT, snappedLeft)
            .putInt(PREF_Y, params.y)
            .apply()
    }

    private fun updateLayout(params: WindowManager.LayoutParams) {
        pillView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private inner class PillTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragStarted = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragStarted = false
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
                    }
                    val metrics = context.resources.displayMetrics
                    params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
                    params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
                    updateLayout(params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragStarted) {
                        params.x = initialX
                        params.y = initialY
                        updateLayout(params)
                        context.sendBroadcast(Intent(ScheduleReceiver.ACTION_PAUSE_GRAYSCALER).apply {
                            setPackage(context.packageName)
                        })
                    } else {
                        snapToEdge()
                    }
                    dragStarted = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    params.x = initialX
                    params.y = initialY
                    val metrics = context.resources.displayMetrics
                    params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
                    params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
                    updateLayout(params)
                    dragStarted = false
                    return true
                }
            }
            return false
        }
    }

    private inner class PauseCountdownView(context: Context) : View(context) {
        private var remainingMs: Long = 0L

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC111111")
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = dp(TEXT_SIZE_SP).toFloat()
        }

        private val bgRect = RectF()

        fun setRemainingMs(ms: Long) {
            remainingMs = ms.coerceAtLeast(0L)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f
            bgRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(bgRect, radius, radius, bgPaint)

            val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            val text = String.format("%02d:%02d", minutes, seconds)

            val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, w / 2f, baseline, textPaint)
        }
    }

    companion object {
        private const val PILL_WIDTH_DP = 80
        private const val PILL_HEIGHT_DP = 36
        private const val TEXT_SIZE_SP = 14
        private const val TICK_INTERVAL_MS = 500L
        private const val DRAG_SLOP_DP = 12

        private const val PREF_X = "countdown_pill_x"
        private const val PREF_Y = "countdown_pill_y"
        private const val PREF_SNAPPED_LEFT = "countdown_pill_snapped_left"
    }
}
