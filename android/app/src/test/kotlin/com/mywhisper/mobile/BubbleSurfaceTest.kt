package com.mywhisper.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleSurfaceTest {
    @Test
    fun antialiasedEdgeIsOnlyBorderNeverFill() {
        for (density in listOf(2f, 2.625f, 3f, 3.5f)) for (dp in listOf(14f, 24f, 32f, 38f, 50f, 66f)) {
            val px = (dp * density).toInt() + 4
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            BubbleSurface().apply {
                this.density = density
                diameter = dp * density
                setBounds(0, 0, px, px)
            }.draw(Canvas(bitmap))
            for (x in 0 until px) for (y in 0 until px) {
                val c = bitmap.getPixel(x, y)
                // Any pixel not fully opaque lies on the outer edge and must be pure border, not white fill.
                if (Color.alpha(c) in 1..254) assertTrue(
                    "density=$density dp=$dp ($x,$y) ${Integer.toHexString(c)}",
                    Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40,
                )
            }
        }
    }
}
