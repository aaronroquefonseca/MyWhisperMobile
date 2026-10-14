package com.mywhisper.mobile

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-device STT via the sherpa-onnx AAR (`OfflineRecognizer`, whisper
 * tiny.en int8 pack under `<filesDir>/models/sherpa-tiny.en`).
 *
 * Code-only dependency: the AAR itself is fetched by
 * `scripts/fetch-sherpa-aar.sh` (URL + SHA256 pinned) into
 * `android/app/libs/` and never committed; model files are never
 * committed either (see the JSON manifest under `core/models`).
 *
 * All entry points return null / false when the pack is absent or the
 * native lib fails — callers fall back to the server chain.
 */
object LocalStt {
    const val PACK_REL_DIR = "models/sherpa-tiny.en"
    const val ENCODER = "tiny.en-encoder.int8.onnx"
    const val DECODER = "tiny.en-decoder.int8.onnx"
    const val TOKENS = "tiny.en-tokens.txt"
    private const val TAG = "MwLocalStt"

    data class Result(val text: String, val loadMs: Long, val decodeMs: Long)

    fun packDir(filesDir: File): File = File(filesDir, PACK_REL_DIR)

    fun isAvailable(filesDir: File): Boolean {
        val dir = packDir(filesDir)
        return listOf(ENCODER, DECODER, TOKENS).all { n ->
            val f = File(dir, n)
            f.isFile && f.length() > 0
        }
    }

    @Volatile private var recognizer: Any? = null
    @Volatile private var packKey: String? = null
    private val lock = Any()

    /**
     * Transcribe 16 kHz mono PCM. Null when unavailable — caller keeps
     * the server fallback. Never throws (all errors → null + log).
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
                    val created = createRecognizer(context, dir, language) ?: return null
                    loadMs = android.os.SystemClock.elapsedRealtime() - t0
                    recognizer = created
                    packKey = key
                    created
                }
            }
            val samples = FloatArray(pcm16.size) { i -> pcm16[i] / 32768.0f }
            val t1 = android.os.SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text ?: ""
                val decodeMs = android.os.SystemClock.elapsedRealtime() - t1
                Log.i(TAG, "local decode chars=${text.length} loadMs=$loadMs decodeMs=$decodeMs")
                return Result(text, loadMs, decodeMs)
            } finally {
                try { stream.release() } catch (_: Exception) {}
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa native missing, fallback to server: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "local transcribe failed, fallback to server: ${e.message}")
            return null
        }
    }

    fun unload() {
        synchronized(lock) {
            try { (recognizer as? com.k2fsa.sherpa.onnx.OfflineRecognizer)?.release() } catch (_: Exception) {}
            recognizer = null
            packKey = null
        }
    }

    private fun createRecognizer(
        context: Context,
        dir: File,
        language: String,
    ): com.k2fsa.sherpa.onnx.OfflineRecognizer? {
        return try {
            val whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                encoder = File(dir, ENCODER).absolutePath,
                decoder = File(dir, DECODER).absolutePath,
                language = language.ifBlank { "en" },
                task = "transcribe",
            )
            val model = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                whisper = whisper,
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
