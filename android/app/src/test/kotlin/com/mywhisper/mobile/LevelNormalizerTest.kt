package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelNormalizerTest {

    private fun rms(db: Double) = Math.pow(10.0, db / 20.0)

    private fun LevelNormalizer.feed(db: Double, blocks: Int): Float {
        var last = 0f
        repeat(blocks) { last = push(rms(db)) }
        return last
    }

    @Test
    fun steadyRoomNoise_isGatedToZero() {
        val n = LevelNormalizer()
        assertEquals(0f, n.feed(-45.0, 200), 0f)
    }

    @Test
    fun normalSpeechAfterSilence_fillsMostOfTheBar() {
        val n = LevelNormalizer()
        n.feed(-65.0, 30)
        // Peak-normalized speech at -30 dBFS used to draw at ~3% height.
        assertTrue(n.push(rms(-30.0)) > 0.9f)
        assertTrue(n.push(rms(-40.0)) in 0.4f..0.9f)
    }

    @Test
    fun quietTalker_isAutoGained() {
        val n = LevelNormalizer()
        n.feed(-70.0, 30)
        n.feed(-42.0, 60)
        assertTrue(n.push(rms(-42.0)) > 0.9f)
    }

    @Test
    fun digitalSilenceWarmup_doesNotPinFloor() {
        val n = LevelNormalizer()
        repeat(10) { n.push(0.0) }
        // Noise at -50 must be gated within a few seconds.
        assertEquals(0f, n.feed(-50.0, 120), 0f)
    }

    @Test
    fun noisyRoom_isLearnedImmediately() {
        val n = LevelNormalizer()
        assertEquals(0f, n.feed(-35.0, 3), 0f)
        assertTrue(n.push(rms(-12.0)) > 0.9f)
    }
}
