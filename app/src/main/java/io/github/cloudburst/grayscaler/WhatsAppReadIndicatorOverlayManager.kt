package io.github.cloudburst.grayscaler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Hosts a single full-screen TYPE_ACCESSIBILITY_OVERLAY window that paints small chips next to
 * each WhatsApp message-status icon. Non-touchable; purely visual.
 */
class WhatsAppReadIndicatorOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private var hostView: LabelsCanvasView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * AccessibilityNode bounds and screenshots use absolute display coordinates (origin at the
     * very top of the screen, above the status bar). TYPE_ACCESSIBILITY_OVERLAY windows on most
     * Android versions get a coordinate origin *below* the status bar, so rendering at the raw
     * node Y is exactly status-bar-height too low. Subtract this offset before drawing.
     */
    private val statusBarOffsetPx: Int = run {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    fun setLabels(labels: List<WhatsAppReadIndicatorScanner.Label>) {
        ensureAttached()
        hostView?.setLabels(labels)
    }

    fun clear() {
        hostView?.setLabels(emptyList())
    }

    fun hide() {
        val view = hostView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        hostView = null
        GrayscaleLog.log(context, "wa_overlay", "detached")
        layoutParams = null
    }

    private fun ensureAttached() {
        if (hostView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val view = LabelsCanvasView(context)
        windowManager.addView(view, params)
        GrayscaleLog.log(context, "wa_overlay", "attached")
        hostView = view
        layoutParams = params
    }

    private inner class LabelsCanvasView(context: Context) : View(context) {
        private var labels: List<WhatsAppReadIndicatorScanner.Label> = emptyList()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6111111")
            style = Paint.Style.FILL
        }
        // Regular weight for the "<time> • " prefix; bold reserved for a "Read" status word so a
        // read receipt stands out from Sent/Delivered. Each has a black stroke outline for
        // legibility over arbitrary chat backgrounds.
        private val textPaintRegular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = false
            textSize = dp(11f)
            textAlign = Paint.Align.LEFT
        }
        private val textPaintBold = Paint(textPaintRegular).apply {
            isFakeBoldText = true
        }
        private val outlineRegular = Paint(textPaintRegular).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val outlineBold = Paint(textPaintBold).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val chipRect = RectF()

        fun setLabels(next: List<WhatsAppReadIndicatorScanner.Label>) {
            labels = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (labels.isEmpty()) return
            val padH = dp(6f)
            val padV = dp(3f)
            val gap = dp(4f)
            val ascentDescent = textPaintRegular.descent() - textPaintRegular.ascent()

            for (label in labels) {
                // Chip content = optional "<time> • " prefix (regular) + status word (bold only
                // when it's "Read"). "•" is the floating-dot separator between time and status.
                val statusBold = label.text == "Read"
                val statusPaint = if (statusBold) textPaintBold else textPaintRegular
                val statusOutline = if (statusBold) outlineBold else outlineRegular
                val prefix = label.timestamp?.let { "$it • " }

                val prefixWidth = if (prefix != null) textPaintRegular.measureText(prefix) else 0f
                val statusWidth = statusPaint.measureText(label.text)
                val chipW = prefixWidth + statusWidth + padH * 2
                val chipH = ascentDescent + padV * 2
                val target: Rect = label.bounds

                // Center the chip directly OVER the checkmark so it covers the (grayscale-ambiguous)
                // ✓✓ icon rather than sitting beside it. Subtract the status bar offset because this
                // overlay's coord origin is below the status bar but node bounds are absolute display
                // coords. Then clamp to the screen so a right-edge checkmark can't push the chip off.
                var left = target.exactCenterX() - chipW / 2f
                var top = target.exactCenterY() - chipH / 2f - statusBarOffsetPx

                val maxLeft = width - gap - chipW
                if (left > maxLeft) left = maxLeft
                if (left < gap) left = gap
                val maxTop = height - gap - chipH
                if (top > maxTop) top = maxTop
                if (top < gap) top = gap

                val right = left + chipW
                val bottom = top + chipH
                chipRect.set(left, top, right, bottom)
                val radius = chipH / 2f
                canvas.drawRoundRect(chipRect, radius, radius, bgPaint)

                val baseline = top + padV - textPaintRegular.ascent()
                var textX = left + padH
                if (prefix != null) {
                    canvas.drawText(prefix, textX, baseline, outlineRegular)
                    canvas.drawText(prefix, textX, baseline, textPaintRegular)
                    textX += prefixWidth
                }
                canvas.drawText(label.text, textX, baseline, statusOutline)
                canvas.drawText(label.text, textX, baseline, statusPaint)
            }
        }

        private fun dp(value: Float): Float =
            value * resources.displayMetrics.density
    }
}
