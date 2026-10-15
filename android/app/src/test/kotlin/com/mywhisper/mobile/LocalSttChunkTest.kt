package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

/**
 * Unit tests for [LocalStt.chunkPcm] (VAD-chunked decode boundaries).
 * Pure JVM: no Android calls, no native code. Mirrors the Rust
 * `local_stt::chunk_pcm16` tests — chunks stay under the ~9s
 * merged-decoder failure point and split on silence.
 */
class LocalSttChunkTest {
    private fun tone(secs: Double, amp: Short = 10000): ShortArray {
        val n = (secs * 16000).toInt()
        return ShortArray(n) { i ->
            (amp * sin(2.0 * PI * 440.0 * i / 16000)).toInt().toShort()
        }
    }

    @Test
    fun shortAudio_isOneRange() {
        val pcm = tone(3.0)
        val chunks = LocalStt.chunkPcm(pcm)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].first)
        assertEquals(pcm.size - 1, chunks[0].last)
    }

    @Test
    fun silenceGap_splitsRanges() {
        val head = tone(3.0)
        val gap = ShortArray(16000)
        val tail = tone(3.0)
        val pcm = head + gap + tail
        val chunks = LocalStt.chunkPcm(pcm)
        assertEquals("ranges=$chunks", 2, chunks.size)
        for (r in chunks) {
            assertTrue("range avoids slivers: $chunks", r.last - r.first + 1 >= 16000)
        }
    }

    @Test
    fun longAudio_cappedUnderBugLimit() {
        val pcm = tone(20.0)
        val chunks = LocalStt.chunkPcm(pcm)
        assertTrue("ranges=$chunks", chunks.size >= 3)
        val cap = (LocalStt.MAX_CHUNK_SECS * 16000).toInt() + LocalStt.VAD_FRAME_SAMPLES
        for (r in chunks) {
            assertTrue("range $r exceeds cap", r.last - r.first + 1 <= cap)
        }
        assertEquals(0, chunks.first().first)
        assertEquals(pcm.size - 1, chunks.last().last)
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunks[i].last + 1, chunks[i + 1].first)
        }
    }

    @Test
    fun silenceOnlyAndEmpty_yieldNoRanges() {
        assertTrue(LocalStt.chunkPcm(ShortArray(32000)).isEmpty())
        assertTrue(LocalStt.chunkPcm(ShortArray(0)).isEmpty())
        assertTrue(LocalStt.chunkPcm(tone(1.0), 0).isEmpty())
    }
}
