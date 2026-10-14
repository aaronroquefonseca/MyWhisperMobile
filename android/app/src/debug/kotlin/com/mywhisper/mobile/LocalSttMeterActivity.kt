package com.mywhisper.mobile

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Debug-only meter harness (src/debug, never in release builds).
 * Launched by `scripts/meter-local-stt.sh`:
 *
 *   am start -n com.mywhisper.mobile/.LocalSttMeterActivity \
 *     --es wavs "/abs/path/a.wav,/abs/path/b.wav" --es lang en
 *
 * Decodes each 16-bit mono WAV via [LocalStt] and logs one line per
 * clip plus a summary, all tagged `MW_METER`:
 *
 *   MW_METER clip=<name> loadMs=<n> decodeMs=<n> audioSecs=<f> rtf=<f> chars=<n> text=<...>
 *   MW_METER done clips=<n>
 *
 * WER is computed host-side from the logged texts.
 */
class LocalSttMeterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                runMeter()
            } catch (e: Exception) {
                Log.i("MW_METER", "error ${e.message}")
            } finally {
                LocalStt.unload()
                finish()
            }
        }.start()
    }

    private fun runMeter() {
        val lang = intent.getStringExtra("lang")?.ifBlank { "en" } ?: "en"
        val wavs = (intent.getStringExtra("wavs") ?: "").split(",").filter { it.isNotBlank() }
        Log.i("MW_METER", "packAvailable=${LocalStt.isAvailable(filesDir)} filesDir=$filesDir")
        if (wavs.isEmpty()) {
            Log.i("MW_METER", "done clips=0")
            return
        }
        // Warm-up load (timed) so clip rows measure steady-state decode.
        var n = 0
        for (w in wavs) {
            val f = File(w)
            if (!f.isFile) {
                Log.i("MW_METER", "clip=$w missing")
                continue
            }
            val pcm = readMono16k(f) ?: run {
                Log.i("MW_METER", "clip=${f.name} badwav")
                continue
            }
            val audioSecs = pcm.size / 16000.0
            val t0 = SystemClock.elapsedRealtime()
            val r = LocalStt.transcribe(this, pcm, 16000, lang)
            val wallMs = SystemClock.elapsedRealtime() - t0
            if (r == null) {
                Log.i("MW_METER", "clip=${f.name} localMiss")
                continue
            }
            val rtf = wallMs / 1000.0 / audioSecs.coerceAtLeast(0.01)
            // Full text in 400-char chunks (logcat truncates long lines;
            // WER is scored host-side from the joined chunks).
            val full = r.text.replace('\n', ' ')
            Log.i(
                "MW_METER",
                "clip=${f.name} loadMs=${r.loadMs} decodeMs=${r.decodeMs} " +
                    "audioSecs=%.2f rtf=%.3f chars=%d".format(audioSecs, rtf, r.text.length),
            )
            full.chunked(400).forEachIndexed { i, part ->
                Log.i("MW_METER", "cliptext=${f.name} part=$i text=$part")
            }
            n++
        }
        Log.i("MW_METER", "done clips=$n")
    }

    /** Minimal WAV reader: 16-bit mono, any rate (resample below). */
    private fun readMono16k(f: File): ShortArray? {
        val b = f.readBytes()
        if (b.size < 44 || b[0] != 'R'.code.toByte()) return null
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = (u16(o)) or (u16(o + 2) shl 16)
        if (u16(20) != 1 || u16(34) != 16) return null // PCM16 only
        val channels = u16(22)
        val rate = u32(24)
        var off = 12
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= b.size) {
            val id = String(b, off, 4, Charsets.US_ASCII)
            val len = u32(off + 4)
            if (id == "data") {
                dataOff = off + 8
                dataLen = len
                break
            }
            off += 8 + len
        }
        if (dataOff < 0) return null
        val frames = dataLen / 2 / channels.coerceAtLeast(1)
        val mono = ShortArray(frames) { i ->
            var acc = 0
            for (c in 0 until channels) {
                val o = dataOff + (i * channels + c) * 2
                if (o + 1 < b.size) acc += ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
            }
            (acc / channels).toShort()
        }
        if (rate == 16000) return mono
        // Linear resample to 16k.
        val ratio = 16000.0 / rate
        val out = ShortArray((frames * ratio).toInt())
        for (i in out.indices) {
            val pos = i / ratio
            val j = pos.toInt().coerceIn(0, frames - 1)
            out[i] = mono[j]
        }
        return out
    }
}
