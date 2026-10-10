package com.mywhisper.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Live mic-level bar visualizer for the floating pill (desktop
 * Visualizer look): white rounded bars driven by real capture peaks,
 * decaying smoothly when quiet.
 */
class BarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    private val bars = FloatArray(BAR_COUNT) { 0f }

    /** Push a 0..1 peak; older bars decay. Redraws on the UI thread. */
    fun pushLevel(peak: Float) {
        for (i in bars.size - 1 downTo 1) {
            bars[i] = bars[i - 1] * DECAY
        }
        bars[0] = peak.coerceIn(0f, 1f).coerceAtLeast(bars[0] * DECAY)
        invalidate()
    }

    fun clear() {
        bars.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = bars.size
        val gap = 3f * resources.displayMetrics.density
        val bw = (width - gap * (n - 1)) / n
        val mid = height / 2f
        val maxHalf = height / 2f - 2f
        for (i in 0 until n) {
            // Recency-weighted: newest bar full, older decayed (matches
            // the push/decay model so motion flows left-to-right).
            val v = bars[i].coerceIn(0f, 1f)
            val h = (4f * resources.displayMetrics.density).coerceAtLeast(v * maxHalf)
            val x = i * (bw + gap)
            canvas.drawRoundRect(
                x, mid - h, x + bw, mid + h,
                bw / 2f, bw / 2f, paint,
            )
        }
    }

    companion object {
        private const val BAR_COUNT = 24
        private const val DECAY = 0.82f
    }
}
