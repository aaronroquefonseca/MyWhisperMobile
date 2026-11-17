package com.mywhisper.mobile

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Morph only the drawn surface: the hit target never collapses with the dot. */
internal class BubbleSurface : Drawable() {
    var diameter = 0f
        set(value) { field = value; invalidateSelf() }
    var fill = android.graphics.Color.WHITE
    var density = 1f
    private var drawableAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val half = diameter / 2f
        val radius = minOf(16 * density, half)
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = android.graphics.Color.alpha(fill) * drawableAlpha / 255
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = android.graphics.Color.BLACK
        paint.alpha = drawableAlpha
        val inset = density / 2
        canvas.drawRoundRect(cx - half + inset, cy - half + inset, cx + half - inset, cy + half - inset, radius, radius, paint)
    }
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
