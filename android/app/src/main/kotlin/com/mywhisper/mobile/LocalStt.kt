package com.mywhisper.mobile

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * On-device STT via the sherpa-onnx 1.13.8 AAR (`OfflineRecognizer`,
 * moonshine v2 tiny-en quantized pack under
 * `<filesDir>/models/sherpa-moonshine-v2-tiny.en`).
 *
 * V2 layout (quantized `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27`):
 * `encoder_model.ort` + `decoder_model_merged.ort` + `tokens.txt`, wired
 * through `OfflineMoonshineModelConfig(mergedDecoder=...)` (no
 * preprocessor / split decoders).
 *
 * Code-only dependency: the AAR itself is fetched by
 * `scripts/fetch-sherpa-aar.sh` (URL + SHA256 pinned) into
 * `android/app/libs/` and never committed; model files are never
 * committed either (see the JSON manifest under `core/models`).
 *
 * VAD-chunked decode: the 1.13.8 merged decoder hits an ORT broadcast
 * bug past ~9s of continuous audio (WER fails there), so utterances
 * are cut on silence ([chunkPcm], constants mirror
 * `core/src/local_stt.rs` — keep in sync by hand), decoded per chunk
 * with a fresh stream, and joined with spaces.
 *
 * All entry points return null / false when the pack is absent, the
 * Local chain entry is disabled, or the native lib fails — callers
 * fall back to the server chain.
 */
object LocalStt {
    const val PACK_REL_DIR = "models/sherpa-moonshine-v2-tiny.en"
    const val ENCODER = "encoder_model.ort"
    const val MERGED_DECODER = "decoder_model_merged.ort"
    const val TOKENS = "tokens.txt"
    const val ENGINE_NAME = "local moonshine-v2-tiny.en"
    private const val TAG = "MwLocalStt"

    // Chunk constants: mirror core/src/local_stt.rs.
    const val MAX_CHUNK_SECS = 8.0
    const val MIN_CHUNK_SECS = 1.0
    const val SILENCE_CUT_SECS = 0.3
    const val VAD_FRAME_SAMPLES = 320
    const val VAD_RMS_THRESHOLD = 0.02f

    data class Result(val text: String, val loadMs: Long, val decodeMs: Long, val chunks: Int)

    fun packDir(filesDir: File): File = File(filesDir, PACK_REL_DIR)

    fun isAvailable(filesDir: File): Boolean {
        val dir = packDir(filesDir)
        return listOf(ENCODER, MERGED_DECODER, TOKENS).all { n ->
            val f = File(dir, n)
            f.isFile && f.length() > 0
        }
    }

    @Volatile private var recognizer: Any? = null
    @Volatile private var packKey: String? = null
    private val lock = Any()

    /**
     * Split 16 kHz mono PCM into decode ranges (inclusive [IntRange]).
     * Pure: no Android calls, JVM-testable. Mirrors
     * `local_stt::chunk_pcm16` — cut on silence runs, force-cut at
     * [MAX_CHUNK_SECS]; all-silence/empty input yields no ranges
     * (caller treats that as a miss → server fallback).
     */
    fun chunkPcm(pcm: ShortArray, sampleRate: Int = 16000): List<IntRange> {
        if (pcm.isEmpty() || sampleRate <= 0) return emptyList()
        val frame = (sampleRate / 50).coerceAtLeast(1)
        val maxLen = (MAX_CHUNK_SECS * sampleRate).toInt()
        val minLen = (MIN_CHUNK_SECS * sampleRate).toInt()
        val cutFrames = ((SILENCE_CUT_SECS * sampleRate).toInt() / frame).coerceAtLeast(1)

        val nFrames = (pcm.size + frame - 1) / frame
        val speech = BooleanArray(nFrames) { f ->
            val s = f * frame
            val e = minOf(s + frame, pcm.size)
            var sum = 0.0
            for (i in s until e) {
                val x = pcm[i] / 32768.0
                sum += x * x
            }
            sqrt(sum / (e - s).coerceAtLeast(1)) >= VAD_RMS_THRESHOLD
        }

        val chunks = mutableListOf<IntRange>()
        var start = 0
        var silenceRun = 0
        var hasSpeech = false
        var f = 0
        while (f < nFrames) {
            val frameEnd = minOf((f + 1) * frame, pcm.size)
            if (speech[f]) {
                hasSpeech = true
                silenceRun = 0
            } else {
                silenceRun++
            }
            val grown = (f + 1) * frame - start
            val atMax = grown >= maxLen && frameEnd - start >= minLen
            val atCut = silenceRun >= cutFrames && grown >= minLen + silenceRun * frame
            if ((atMax || atCut) && hasSpeech) {
                val cut = if (atCut && !atMax) {
                    (frameEnd - silenceRun * frame).coerceAtLeast(start + minLen)
                } else {
                    frameEnd
                }.coerceAtMost(pcm.size)
                if (cut > start) chunks.add(start..cut - 1)
                start = cut
                hasSpeech = false
                silenceRun = 0
            }
            f++
        }
        if (start < pcm.size) {
            val tailSpeech = ((start + frame - 1) / frame until nFrames).any { speech[it] }
            if (tailSpeech || hasSpeech) {
                val last = chunks.lastOrNull()
                if (last != null && pcm.size - (last.first) < minLen) {
                    chunks[chunks.lastIndex] = last.first..pcm.size - 1
                } else if (pcm.size - start < minLen / 4 && last != null) {
                    chunks[chunks.lastIndex] = last.first..pcm.size - 1
                } else {
                    chunks.add(start..pcm.size - 1)
                }
            }
        }
        // Hard-split any over-cap range (rounding safety).
        val out = mutableListOf<IntRange>()
        for (r in chunks) {
            var c = r.first
            while (r.last + 1 - c > maxLen + frame) {
                out.add(c..c + maxLen - 1)
                c += maxLen
            }
            if (r.last >= c) out.add(c..r.last)
        }
        return out
    }

    /**
     * Transcribe 16 kHz mono PCM with VAD-chunked decode. Null when
     * unavailable — caller keeps the server fallback. Never throws
     * (all errors → null + log). `language` is accepted for API
     * symmetry; the tiny-en pack is English-only.
     */
    fun transcribe(
        context: Context,
        pcm16: ShortArray,
        sampleRate: Int = 16000,
        language: String = "en",
    ): Result? {
        try {
            val dir = packDir(context.filesDir)
            if (!isAvailable(context.filesDir)) return null
            if (pcm16.isEmpty()) return null
            val ranges = chunkPcm(pcm16, sampleRate)
            if (ranges.isEmpty()) return null
            val key = dir.absolutePath
            var loadMs = 0L
            val rec: com.k2fsa.sherpa.onnx.OfflineRecognizer = synchronized(lock) {
                val cached = if (packKey == key) recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer else null
                if (cached != null) {
                    cached
                } else {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    try { (recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer)?.release() } catch (_: Exception) {}
                    recognizer = null
                    val created = createRecognizer(dir) ?: return null
                    loadMs = android.os.SystemClock.elapsedRealtime() - t0
                    recognizer = created
                    packKey = key
                    created
                }
            }
            val sb = StringBuilder()
            var decodeMs = 0L
            for (r in ranges) {
                val slice = pcm16.sliceArray(r)
                val samples = FloatArray(slice.size) { i -> slice[i] / 32768.0f }
                val t1 = android.os.SystemClock.elapsedRealtime()
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text ?: ""
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(text.trim())
                    }
                } finally {
                    try { stream.release() } catch (_: Exception) {}
                }
                decodeMs += android.os.SystemClock.elapsedRealtime() - t1
            }
            Log.i(TAG, "local decode chunks=${ranges.size} chars=${sb.length} loadMs=$loadMs decodeMs=$decodeMs")
            return Result(sb.toString(), loadMs, decodeMs, ranges.size)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa native missing, fallback to server: ${e.message}")
            return null
        } catch (e: OutOfMemoryError) {
            // OOM is an Error, not an Exception: release the cached
            // recognizer (native + Java heap) and fall back to server.
            Log.w(TAG, "local transcribe OOM, fallback to server")
            try { unload() } catch (_: Throwable) {}
            return null
        } catch (e: Exception) {
            Log.w(TAG, "local transcribe failed, fallback to server: ${e.message}")
            return null
        }
    }

    /**
     * Production entry for the [Dictation] local-first hook: enabled
     * Local chain entry + pack present + non-blank decode, else null
     * (server fallback). Never throws.
     */
    fun transcribeIfEnabled(context: Context, pcm16: ShortArray): Pair<String, String>? {
        try {
            if (pcm16.isEmpty()) return null
            if (!isLocalEntryEnabled()) return null
            val r = transcribe(context, pcm16) ?: return null
            if (r.text.isBlank()) return null
            return r.text to ENGINE_NAME
        } catch (_: OutOfMemoryError) {
            // OOM is an Error: drop native state and fall back to server.
            try { unload() } catch (_: Throwable) {}
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun isLocalEntryEnabled(): Boolean {
        try {
            val s = CoreBridge.getSettings().getOrThrow()
            val entries = s.optJSONObject("stt")?.optJSONArray("entries") ?: return false
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                if (e.optString("kind") == "Local" && e.optBoolean("enabled", true)) return true
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    fun unload() {
        synchronized(lock) {
            try { (recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer)?.release() } catch (_: Exception) {}
            recognizer = null
            packKey = null
        }
    }

    private fun createRecognizer(
        dir: File,
    ): com.k2fsa.sherpa.onnx.OfflineRecognizer? {
        return try {
            val moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(
                encoder = File(dir, ENCODER).absolutePath,
                mergedDecoder = File(dir, MERGED_DECODER).absolutePath,
            )
            val model = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                moonshine = moonshine,
                numThreads = 2,
                provider = "cpu",
                tokens = File(dir, TOKENS).absolutePath,
            )
            val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = model,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )
            // Null assets → newFromFile: models live in filesDir, not APK assets.
            com.k2fsa.sherpa.onnx.OfflineRecognizer(null, config)
        } catch (e: Exception) {
            Log.w(TAG, "recognizer create failed: ${e.message}")
            null
        }
    }
}
