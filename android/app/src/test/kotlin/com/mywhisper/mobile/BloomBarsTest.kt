package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BloomBarsTest {

    private fun BloomBars.run(level: Float, ms: Long, startMs: Long): Long {
        var now = startMs
        while (now < startMs + ms) {
            if ((now - startMs) % 32 == 0L) push(level, now)
            step(1f / 60f, now)
            now += 16
        }
        return now
    }

    @Test
    fun silence_staysFlat() {
        val b = BloomBars(11, Random(1))
        b.run(0f, 1000, 0)
        assertTrue(b.heights.all { it == 0f })
    }

    @Test
    fun voice_isSymmetricAndCenterTallest() {
        val b = BloomBars(11, Random(2))
        b.run(0.8f, 600, 0)
        for (i in 0 until 5) assertEquals(b.heights[i], b.heights[10 - i], 1e-6f)
        assertTrue(b.heights[5] > 0.5f)
        assertTrue(b.heights[5] > b.heights[2])
        assertTrue(b.heights[2] > b.heights[0])
    }

    @Test
    fun opensFromCenterFirst() {
        val b = BloomBars(11, Random(3))
        b.run(0.8f, 48, 0)
        assertTrue(b.heights[5] > 0f)
        assertEquals(0f, b.heights[0], 0f)
    }

    @Test
    fun closesAndSettlesAfterVoiceStops() {
        val b = BloomBars(11, Random(4))
        val t = b.run(0.8f, 600, 0)
        val end = b.run(0f, 2000, t)
        assertFalse(b.step(1f / 60f, end))
        assertTrue(b.heights.all { it < 0.01f })
    }
}
