package com.mywhisper.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Live mic-level bar visualizer for the floating pill (desktop
 * Visualizer defaults: 11 bars, 3px wide, 3px gap, 18px lanes,
 * 4px resting height, white). Driven by real capture peaks.
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
        val d = resources.displayMetrics.density
        val bw = 3f * d
        val gap = 3f * d
        val n = bars.size
        val mid = height / 2f
        val laneMax = 18f * d
        val minHalf = 2f * d
        for (i in 0 until n) {
            val v = bars[i].coerceIn(0f, 1f)
            val h = (minHalf + v * (laneMax - minHalf)).coerceAtMost(mid - 1f)
            val x = i * (bw + gap)
            canvas.drawRoundRect(
                x, mid - h, x + bw, mid + h,
                bw / 2f, bw / 2f, paint,
            )
        }
    }

    companion object {
        private const val BAR_COUNT = 11
        private const val DECAY = 0.82f
    }
}
