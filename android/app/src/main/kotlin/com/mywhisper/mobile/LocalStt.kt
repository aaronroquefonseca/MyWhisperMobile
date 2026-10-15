package com.mywhisper.mobile

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File
import kotlin.math.sqrt

/**
 * On-device STT via the sherpa-onnx 1.13.8 AAR (`OfflineRecognizer`,
 * moonshine v2 tiny-en quantized pack under
 * `<filesDir>/models/sherpa-moonshine-v2-tiny.en`).
 *
 * The AAR is full-flavor-only and NEVER on the lite classpath: every
 * `com.k2fsa.sherpa.onnx` touch goes through the [SherpaRunner]
 * reflection bridge (mirroring
 * `LocalLlmManager.MediaPipeRunner`), so this file compiles and runs
 * with no sherpa classes present — misses degrade to null and callers
 * keep the server fallback.
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

    /**
     * Legacy moonshine pack id (registry [LocalSttModels.MOONSHINE_ID]
     * is the source of truth; kept for URL/shape compat).
     */
    const val PACK_ID = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"

    /** Registry view of the legacy moonshine pack. */
    fun moonshineEntry(): LocalSttModels.Entry =
        LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)

    data class PackAsset(
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    /** Legacy pack files (registry moonshine entry, same content). */
    fun packAssets(): List<PackAsset> = moonshineEntry().files.map {
        PackAsset(it.name, it.url, it.sha256, it.sizeBytes)
    }

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

    /** SHA-verified legacy pack state (registry moonshine entry). */
    fun packState(filesDir: File): LocalModelDownload.RowState =
        LocalSttModels.stateFor(filesDir, moonshineEntry())

    /** Legacy pack download (registry moonshine entry). */
    fun downloadPack(
        filesDir: File,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = LocalSttModels.download(filesDir, moonshineEntry(), onProgress)

    /** Legacy pack delete (registry moonshine entry). */
    fun deletePack(filesDir: File) = LocalSttModels.delete(filesDir, moonshineEntry())

    /** Selected catalog model (SharedPreferences, default = moonshine fast pack). */
    fun selectedEntry(prefs: android.content.SharedPreferences): LocalSttModels.Entry =
        LocalSttModels.findById(
            prefs.getString(LocalSttModels.PREF_SELECTED_MODEL, LocalSttModels.DEFAULT_ID),
        )

    fun setSelectedEntry(prefs: android.content.SharedPreferences, id: String) {
        prefs.edit {
            putString(LocalSttModels.PREF_SELECTED_MODEL, id)
        }
        unload()
    }

    fun engineName(entry: LocalSttModels.Entry): String = "local ${entry.id}"

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
     * Transcribe 16 kHz mono PCM with VAD-chunked decode using the
     * selected catalog model ([LocalSttModels]). Null when the model
     * is not installed, the Local chain entry is disabled, or the
     * native lib fails — caller keeps the server fallback. Never
     * throws (all errors → null + log). `language` is the Capture
     * picker code; AUTO catalog entries map `auto` to sherpa
     * auto-detect, MANUAL entries pin their single language.
     */
    fun transcribe(
        context: Context,
        pcm16: ShortArray,
        sampleRate: Int = 16000,
        language: String = "en",
    ): Result? {
        try {
            val prefs = context.getSharedPreferences("mw_state", Context.MODE_PRIVATE)
            val entry = selectedEntry(prefs)
            val dir = LocalSttModels.dirFor(context.filesDir, entry)
            if (!LocalSttModels.isInstalled(context.filesDir, entry)) return null
            if (pcm16.isEmpty()) return null
            val whisperLang = LocalSttModels.whisperLanguageFor(language, entry)
            val ranges = chunkPcm(pcm16, sampleRate)
            if (ranges.isEmpty()) return null
            // Cache key covers model dir + whisper language (changing
            // either rebuilds the native recognizer).
            val key = dir.absolutePath + "|" + entry.id + "|" + whisperLang
            var loadMs = 0L
            val rec: Any = synchronized(lock) {
                val cached = if (packKey == key) recognizer else null
                if (cached != null) {
                    cached
                } else {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    SherpaRunner.release(recognizer)
                    recognizer = null
                    val created = SherpaRunner.createRecognizer(dir, entry, whisperLang) ?: return null
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
                val text = SherpaRunner.decodeChunkText(rec, samples, sampleRate) ?: ""
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(text.trim())
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
     * Local chain entry + selected model installed + non-blank
     * decode, else null (server fallback). The Capture language
     * comes from committed settings (AUTO models follow it,
     * MANUAL models pin theirs). Never throws.
     */
    fun transcribeIfEnabled(context: Context, pcm16: ShortArray): Pair<String, String>? {
        try {
            if (pcm16.isEmpty()) return null
            if (!isLocalEntryEnabled()) return null
            val prefs = context.getSharedPreferences("mw_state", Context.MODE_PRIVATE)
            val entry = selectedEntry(prefs)
            val r = transcribe(context, pcm16, 16000, captureLanguage()) ?: return null
            if (r.text.isBlank()) return null
            return r.text to engineName(entry)
        } catch (_: OutOfMemoryError) {
            // OOM is an Error: drop native state and fall back to server.
            try { unload() } catch (_: Throwable) {}
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun captureLanguage(): String {
        return try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("stt")?.optString("language", "auto")?.ifBlank { "auto" } ?: "auto"
        } catch (_: Exception) {
            "auto"
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
            SherpaRunner.release(recognizer)
            recognizer = null
            packKey = null
        }
    }

    /**
     * Reflection bridge to `com.k2fsa.sherpa.onnx` (mirrors
     * `LocalLlmManager.MediaPipeRunner`): the sherpa AAR is
     * full-flavor-only, so no direct references may appear here —
     * everything resolves via `Class.forName` at call time. Missing
     * classes / native lib → null (callers keep the server fallback).
     * Configs are built with the public no-arg constructor + setters
     * (verified against sherpa-onnx 1.13.8 `classes.jar`), never the
     * synthetic Kotlin-default constructors.
     */
    internal object SherpaRunner {
        private const val PKG = "com.k2fsa.sherpa.onnx"

        /** False on lite builds / JVM tests (no sherpa classes). */
        fun isSupported(): Boolean = loadClass("$PKG.OfflineRecognizer") != null

        internal fun loadClass(name: String): Class<*>? =
            try {
                Class.forName(name)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }

        private fun newOf(name: String): Any? =
            try {
                loadClass(name)?.getDeclaredConstructor()?.newInstance()
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }

        private fun call(target: Any, method: String, vararg args: Any?): Any? =
            try {
                val m = target.javaClass.methods.firstOrNull {
                    it.name == method && it.parameterCount == args.size
                } ?: return null
                m.invoke(target, *args)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }

        private fun modelConfig(moonshine: Any? = null, whisper: Any? = null, tokens: String): Any? {
            val mc = newOf("$PKG.OfflineModelConfig") ?: return null
            if (moonshine != null) call(mc, "setMoonshine", moonshine)
            if (whisper != null) call(mc, "setWhisper", whisper)
            call(mc, "setNumThreads", 2)
            call(mc, "setProvider", "cpu")
            call(mc, "setTokens", tokens)
            return mc
        }

        /** Null when the AAR is absent, files are missing, or the native lib fails. */
        fun createRecognizer(
            dir: File,
            entry: LocalSttModels.Entry,
            whisperLang: String,
        ): Any? {
            try {
                val tokens = File(
                    dir,
                    entry.files.first { it.name.endsWith("tokens.txt") }.name,
                ).absolutePath
                val model = when (entry.engine) {
                    LocalSttModels.Engine.MOONSHINE -> {
                        val moonshine = newOf("$PKG.OfflineMoonshineModelConfig") ?: return null
                        call(moonshine, "setEncoder", File(dir, ENCODER).absolutePath)
                        call(moonshine, "setMergedDecoder", File(dir, MERGED_DECODER).absolutePath)
                        modelConfig(moonshine = moonshine, tokens = tokens) ?: return null
                    }
                    LocalSttModels.Engine.WHISPER -> {
                        val enc = entry.files.first { "encoder" in it.name }.name
                        val dec = entry.files.first { "decoder" in it.name }.name
                        val whisper = newOf("$PKG.OfflineWhisperModelConfig") ?: return null
                        call(whisper, "setEncoder", File(dir, enc).absolutePath)
                        call(whisper, "setDecoder", File(dir, dec).absolutePath)
                        call(whisper, "setLanguage", whisperLang)
                        call(whisper, "setTask", "transcribe")
                        call(whisper, "setTailPaddings", 1000)
                        call(whisper, "setEnableTokenTimestamps", false)
                        call(whisper, "setEnableSegmentTimestamps", false)
                        modelConfig(whisper = whisper, tokens = tokens) ?: return null
                    }
                }
                val feat = newOf("$PKG.FeatureConfig") ?: return null
                call(feat, "setSampleRate", 16000)
                call(feat, "setFeatureDim", 80)
                val config = newOf("$PKG.OfflineRecognizerConfig") ?: return null
                call(config, "setFeatConfig", feat)
                call(config, "setModelConfig", model)
                call(config, "setDecodingMethod", "greedy_search")
                call(config, "setMaxActivePaths", 4)
                val recCls = loadClass("$PKG.OfflineRecognizer") ?: return null
                // OfflineRecognizer(AssetManager, OfflineRecognizerConfig):
                // null assets → newFromFile, models live in filesDir.
                val ctor = recCls.constructors.firstOrNull { it.parameterCount == 2 } ?: return null
                return ctor.newInstance(null, config)
            } catch (_: Exception) {
                Log.w(TAG, "recognizer create failed")
                return null
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "sherpa native missing")
                return null
            } catch (_: LinkageError) {
                Log.w(TAG, "sherpa classes missing (lite build?)")
                return null
            }
        }

        /** Decoded text for one VAD chunk, or null (→ blank, skipped). Never throws. */
        fun decodeChunkText(rec: Any, samples: FloatArray, sampleRate: Int): String? {
            try {
                val stream = call(rec, "createStream") ?: return null
                try {
                    call(stream, "acceptWaveform", samples, sampleRate)
                    call(rec, "decode", stream)
                    val result = call(rec, "getResult", stream) ?: return null
                    return call(result, "getText") as? String
                } finally {
                    call(stream, "release")
                }
            } catch (_: Exception) {
                return null
            } catch (_: UnsatisfiedLinkError) {
                return null
            } catch (_: LinkageError) {
                return null
            }
        }

        /** Best-effort native release. Never throws (unload-safe). */
        fun release(rec: Any?) {
            if (rec == null) return
            call(rec, "release")
        }
    }
}
