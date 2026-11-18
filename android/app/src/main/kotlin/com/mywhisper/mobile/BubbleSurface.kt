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
    /** Horizontal shift from center, used to hug the docked screen edge as a dot. */
    var offsetX = 0f
        set(value) { field = value; invalidateSelf() }
    var density = 1f
    var uniform = false
        set(value) { field = value; invalidateSelf() }
    /** 0 = squircle, 1 = circle; animated so a shrink morphs the corners too. */
    var roundness = 0f
        set(value) { field = value; invalidateSelf() }
    private var drawableAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX() + offsetX
        val cy = bounds.exactCenterY()
        val half = diameter / 2f
        val squircle = BubbleAppearance.cornerRadius(diameter, density, uniform)
        val radius = squircle + (half - squircle) * roundness
        paint.style = Paint.Style.FILL
        // Border is the outer shape and fill sits strictly inside it, so no fill pixel can reach past the outline.
        paint.color = android.graphics.Color.BLACK
        paint.alpha = drawableAlpha
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, radius, radius, paint)
        val border = density
        val inner = (radius - border).coerceAtLeast(0f)
        paint.color = fill
        paint.alpha = android.graphics.Color.alpha(fill) * drawableAlpha / 255
        canvas.drawRoundRect(cx - half + border, cy - half + border, cx + half - border, cy + half - border, inner, inner, paint)
    }
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
