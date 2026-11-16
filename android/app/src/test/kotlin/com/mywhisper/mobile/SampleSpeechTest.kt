package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SampleSpeech.decodeWav16kMono]: minimal WAV parsing (PCM16 mono,
 * linear resample to 16 kHz). Synthetic headers built in-test — the
 * shipped debug asset itself is verified on-device by the self-test
 * row (blank decode on reference speech FAILs there).
 */
class SampleSpeechTest {

    private fun wavBytes(
        rate: Int,
        channels: Int,
        formatTag: Int = 1,
        samples: ShortArray = ShortArray(160) { (it * 100).toShort() },
        extraChunk: Boolean = false,
    ): ByteArray {
        val data = ByteArray(samples.size * 2 * channels) { 0 }
        // Interleave channels with the same mono content.
        for (i in samples.indices) {
            for (c in 0 until channels) {
                val o = (i * channels + c) * 2
                data[o] = (samples[i].toInt() and 0xFF).toByte()
                data[o + 1] = ((samples[i].toInt() ushr 8) and 0xFF).toByte()
            }
        }
        fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        fun u32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        )
        val out = mutableListOf<Byte>()
        out.addAll("RIFF".toByteArray().toList())
        out.addAll(u32(0).toList()) // size placeholder (parser ignores it)
        out.addAll("WAVE".toByteArray().toList())
        if (extraChunk) {
            out.addAll("JUNK".toByteArray().toList())
            out.addAll(u32(4).toList())
            out.addAll(byteArrayOf(1, 2, 3, 4).toList())
        }
        out.addAll("fmt ".toByteArray().toList())
        out.addAll(u32(16).toList())
        out.addAll(u16(formatTag).toList())
        out.addAll(u16(channels).toList())
        out.addAll(u32(rate).toList())
        out.addAll(u32(rate * channels * 2).toList())
        out.addAll(u16(channels * 2).toList())
        out.addAll(u16(16).toList())
        out.addAll("data".toByteArray().toList())
        out.addAll(u32(data.size).toList())
        out.addAll(data.toList())
        return out.toByteArray()
    }

    @Test
    fun assetFor_englishOnlyGetsEnAsset() {
        val enEntry = LocalSttModels.Entry(
            id = "x", name = "x", sizeLabel = "x",
            engine = LocalSttModels.Engine.WHISPER,
            provider = LocalSttModels.Provider.WHISPER,
            langMode = LocalSttModels.LangMode.MANUAL,
            languages = listOf("en"),
            dictionary = false, runtimeNote = "x", phoneTier = "x",
            dirRel = "x", files = emptyList(),
        )
        assertEquals(SampleSpeech.EN_ASSET, SampleSpeech.assetFor(enEntry))
        val multiEntry = enEntry.copy(
            engine = LocalSttModels.Engine.PARAKEET,
            provider = LocalSttModels.Provider.NVIDIA,
            langMode = LocalSttModels.LangMode.AUTO,
            languages = LocalSttModels.PARAKEET_LANGS,
        )
        assertEquals(SampleSpeech.ES_ASSET, SampleSpeech.assetFor(multiEntry))
    }

    @Test
    fun mono16k_passthrough() {
        val samples = ShortArray(160) { (it * 100).toShort() }
        val out = SampleSpeech.decodeWav16kMono(wavBytes(16000, 1, samples = samples))!!
        assertEquals(samples.toList(), out.toList())
    }

    @Test
    fun mono22050_resampledTo16k() {
        // 22050Hz x 1s = 22050 frames → 16000 out.
        val samples = ShortArray(22050) { (10_000 * kotlin.math.sin(it * 0.02)).toInt().toShort() }
        val out = SampleSpeech.decodeWav16kMono(wavBytes(22050, 1, samples = samples))!!
        assertEquals(16000, out.size)
        // Resampled sine keeps its shape (sign pattern preserved).
        assertTrue(out.count { it > 1000 } > 4000)
        assertTrue(out.count { it < -1000 } > 4000)
    }

    @Test
    fun skipsUnknownChunks() {
        val samples = ShortArray(160) { (it * 100).toShort() }
        val out = SampleSpeech.decodeWav16kMono(
            wavBytes(16000, 1, samples = samples, extraChunk = true),
        )!!
        assertEquals(samples.toList(), out.toList())
    }

    @Test
    fun rejectsNonPcmStereoAndGarbage() {
        val samples = ShortArray(160) { 1 }
        assertNull(SampleSpeech.decodeWav16kMono(wavBytes(16000, 2, samples = samples)))
        assertNull(SampleSpeech.decodeWav16kMono(wavBytes(16000, 1, formatTag = 3, samples = samples)))
        assertNull(SampleSpeech.decodeWav16kMono(ByteArray(0)))
        assertNull(SampleSpeech.decodeWav16kMono("not a wav at all........................".toByteArray()))
        assertNull(SampleSpeech.decodeWav16kMono(ByteArray(44) { 0 }))
    }
}
