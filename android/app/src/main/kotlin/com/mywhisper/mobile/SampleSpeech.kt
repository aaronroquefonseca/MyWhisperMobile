package com.mywhisper.mobile

/**
 * Reference-speech probe for the on-device self-test rows: real
 * recorded speech (Spanish, from the Nemotron 3.5 model pack's own
 * `test_wavs`), so a blank decode FAILS instead of passing as "pack
 * ok" (the 1s 440Hz sine in [ProviderTest.samplePcm16] can never
 * produce text — it only proves the pipeline runs).
 *
 * Debug builds only (`src/debug/assets`): release APKs carry no
 * asset and the self-test falls back to the sine probe. Binaries
 * rule still holds for MODEL files; this 229KB fixture is a test
 * vector, pinned bytes (tarball `test_wavs/es.wav`, 22050Hz mono).
 *
 * WAV parsing is pure JVM (hand-rolled chunk scan, no android.jar):
 * PCM16 mono at any rate, linear-resampled to 16 kHz. Anything else
 * (non-PCM, stereo, corrupt) returns null → sine fallback.
 */
object SampleSpeech {
    const val ES_ASSET = "selftest/speech-es.wav"

    /** Load + decode the asset to 16 kHz mono PCM, or null. */
    fun loadAsset(context: android.content.Context, path: String = ES_ASSET): ShortArray? {
        return try {
            context.assets.open(path).use { ins ->
                decodeWav16kMono(ins.readBytes())
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Minimal WAV→16kHz-mono decoder: RIFF/WAVE container, fmt chunk
     * (PCM16 = format 1), data chunk; other chunks skipped. Mono
     * only; other rates linear-resampled. Pure for unit tests.
     */
    fun decodeWav16kMono(bytes: ByteArray): ShortArray? {
        try {
            if (bytes.size < 44) return null
            fun s(off: Int): String = bytes.decodeToString(off, off + 4)
            fun u16(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            fun u32(off: Int): Long =
                (bytes[off].toLong() and 0xFF) or ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 16) or ((bytes[off + 3].toLong() and 0xFF) shl 24)
            if (s(0) != "RIFF" || s(8) != "WAVE") return null
            var off = 12
            var fmtRate = 0
            var fmtChannels = 0
            var fmtBits = 0
            var dataOff = -1
            var dataLen = 0
            while (off + 8 <= bytes.size) {
                val id = s(off)
                val len = u32(off + 4).toInt()
                if (len < 0 || off + 8 + len > bytes.size) return null
                when (id) {
                    "fmt " -> {
                        if (len < 16) return null
                        if (u16(off + 8) != 1) return null // PCM only
                        fmtChannels = u16(off + 10)
                        fmtRate = u32(off + 12).toInt()
                        fmtBits = u16(off + 22)
                    }
                    "data" -> {
                        dataOff = off + 8
                        dataLen = len
                    }
                }
                off += 8 + len + (len and 1) // pad byte
            }
            if (fmtBits != 16 || fmtChannels != 1 || fmtRate <= 0) return null
            if (dataOff < 0 || dataLen < 2) return null
            val frames = dataLen / 2
            val mono = ShortArray(frames) { i ->
                val o = dataOff + i * 2
                ((bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)).toShort()
            }
            if (fmtRate == 16000) return mono
            // Linear resample to 16 kHz.
            val outLen = ((frames.toLong() * 16000) / fmtRate).toInt()
            if (outLen < 1) return null
            return ShortArray(outLen) { i ->
                val pos = (i.toLong() * fmtRate) / 16000
                val p0 = pos.coerceIn(0, (frames - 1).toLong()).toInt()
                val p1 = (p0 + 1).coerceAtMost(frames - 1)
                val frac = (((i.toLong() * fmtRate) % 16000).toFloat() / 16000f)
                (mono[p0] + frac * (mono[p1] - mono[p0])).toInt().toShort()
            }
        } catch (_: Exception) {
            return null
        }
    }
}
