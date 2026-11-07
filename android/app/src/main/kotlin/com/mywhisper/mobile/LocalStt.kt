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
     * Snippet trigger pairs for [expandSnippets]: comma-split, trimmed,
     * empties dropped. Mirrors core `Snippet::triggers` flatMap in
     * `engine.rs::finish_inner` — Kotlin `CoreBridge.getSnippets`
     * returns the whole trigger field per row, so the split must happen
     * here for parity (a "ask, aks" row yields two pairs).
     * Pure: no Android calls, JVM-testable.
     */
    fun snippetPairs(snippets: List<Pair<String, String>>): List<Pair<String, String>> =
        snippets.flatMap { (trigger, replacement) ->
            trigger.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it to replacement }
        }

    private data class SnippetTrigger(
        val pattern: List<Char>,
        val dotlessOk: List<Boolean>,
        val replacement: String,
    )

    private fun foldTextChar(c: Char): Char =
        if (c == 'İ') 'i' else c.lowercaseChar()

    private fun compileTrigger(trigger: String, replacement: String): SnippetTrigger? {
        val folded = trigger.trim().replace('İ', 'i')
        if (folded.isEmpty()) return null
        val lowered = folded.lowercase().toList()
        val raw = trigger.trim().toList()
        val pattern = mutableListOf<Char>()
        val dotlessOk = mutableListOf<Boolean>()
        lowered.forEachIndexed { ri, c ->
            val r = raw.getOrNull(ri) ?: '?'
            pattern.add(c)
            dotlessOk.add(r == 'i' || r == 'I')
        }
        return SnippetTrigger(pattern, dotlessOk, replacement)
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Snippet expansion over STT text. Byte-for-byte port of core
     * `textnorm::expand_snippets`: longest-first single pass, word
     * boundaries (start/end or non-`[alphanumeric_]`),
     * case-insensitive with Turkish dotted/dotless-i readings.
     * Pure: no Android calls, JVM-testable. Unit vectors mirror the
     * Rust tests — any drift there must land here too.
     */
    fun expandSnippets(text: String, pairs: List<Pair<String, String>>): String {
        val triggers = pairs.mapNotNull { (t, r) -> compileTrigger(t, r) }
            .sortedByDescending { it.pattern.size }
        if (triggers.isEmpty()) return text
        val chars = text.toList()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < chars.size) {
            val atBoundary = i == 0 || !isWordChar(chars[i - 1])
            var hit: SnippetTrigger? = null
            var hitLen = 0
            if (atBoundary) {
                for (tr in triggers) {
                    val n = tr.pattern.size
                    if (i + n > chars.size) continue
                    var ok = true
                    for (k in 0 until n) {
                        val p = tr.pattern[k]
                        val t = foldTextChar(chars[i + k])
                        val match = t == p ||
                            (tr.dotlessOk[k] && ((p == 'i' && t == 'ı') || (p == 'ı' && t == 'i')))
                        if (!match) {
                            ok = false
                            break
                        }
                    }
                    if (ok && (i + n == chars.size || !isWordChar(chars[i + n]))) {
                        hit = tr
                        hitLen = n
                        break
                    }
                }
            }
            if (hit != null) {
                out.append(hit.replacement)
                i += hitLen
            } else {
                out.append(chars[i])
                i++
            }
        }
        return out.toString()
    }

    private fun loadSnippetsOrEmpty(): List<Pair<String, String>> =
        try {
            CoreBridge.getSnippets()
        } catch (_: Throwable) {
            // JVM unit tests / missing native lib: Throwable (not just
            // Exception — UnsatisfiedLinkError is an Error). Passthrough.
            emptyList()
        }

    private fun loadWordsOrEmpty(): List<String> =
        try {
            CoreBridge.getWords()
        } catch (_: Throwable) {
            // Same loader hazard as snippets above (getWords only
            // catches Exception; native-link failures are Errors).
            emptyList()
        }

    /**
     * Latency guard for [applyWordBoost]: the finish worker runs the
     * boost inline, so a pathological dictionary (sync-merged across
     * peers) cannot turn the scan quadratic. The server prompt budget
     * (~900 chars ≈ dozens of words) drops everything past its tail
     * anyway; 128 head words is generous by comparison.
     */
    const val MAX_WORD_BOOST_WORDS = 128

    private fun boostAllowedDistance(len: Int): Int = when {
        len < 4 -> -1 // short tokens: exact (case-insensitive) repair only
        len <= 5 -> 1
        else -> 2
    }

    /** Levenshtein distance with early exit past [cap]. Pure, JVM-testable. */
    internal fun levenshteinCapped(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        if (cap < 0) return cap + 1
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cap) return cap + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private fun canonicalBoostFor(token: String, words: List<String>): String? {
        if (token.isEmpty()) return null
        val folded = token.lowercase()
        // Exact pass first: the curated spelling always beats a fuzzy
        // neighbor, regardless of entry-length ordering below.
        for (w in words) {
            val entry = w.trim()
            if (entry.isEmpty() || ' ' in entry) continue
            if (folded == entry.lowercase() && token != entry) return entry
        }
        if (token.any { it.isDigit() }) return null
        for (w in words) {
            val entry = w.trim()
            if (entry.isEmpty() || ' ' in entry) continue
            if (folded == entry.lowercase()) continue
            val target = entry.lowercase()
            val allowed = boostAllowedDistance(maxOf(token.length, entry.length))
            if (allowed < 0) continue
            // First-letter gate: real misspellings keep the onset;
            // without it short tokens collide across the dictionary.
            if (folded[0] != target[0]) continue
            if (levenshteinCapped(folded, target, allowed) <= allowed) return entry
        }
        return null
    }

    /**
     * Dictionary WORD post-process over STT text: repairs each
     * word-token to the dictionary's exact spelling on an exact
     * case-insensitive match (decoder output is usually lowercase, so
     * casing repair alone is a real win) or a near-miss within
     * [boostAllowedDistance] (same onset; digit-bearing tokens get
     * exact repair only — versions and numbers are never
     * fuzzy-rewritten). Multi-word entries repair on exact
     * case-insensitive phrase match only.
     *
     * Documented approximation, NOT decoder bias: sherpa-onnx 1.13.8
     * exposes no bias channel for our engines (see [postProcessLocal]),
     * so a word the decoder drops entirely cannot be recovered — only
     * close misspellings snap to the curated spelling. Pure: no
     * Android calls, JVM-testable. Never throws (empty words → passthrough).
     */
    fun applyWordBoost(text: String, words: List<String>): String {
        val cleaned = words.mapNotNull { w ->
            val t = w.trim()
            if (t.isEmpty()) null else t
        }.take(MAX_WORD_BOOST_WORDS)
        if (text.isEmpty() || cleaned.isEmpty()) return text
        val single = cleaned.filter { ' ' !in it }
        val phrases = cleaned.filter { ' ' in it }
        // Single-token pass: longest entries first so overlapping
        // spellings are deterministic.
        val ordered = single.sortedByDescending { it.length }
        val chars = text.toList()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < chars.size) {
            if (isWordChar(chars[i])) {
                var j = i
                while (j < chars.size && isWordChar(chars[j])) j++
                val token = chars.subList(i, j).joinToString("")
                out.append(canonicalBoostFor(token, ordered) ?: token)
                i = j
            } else {
                out.append(chars[i])
                i++
            }
        }
        var boosted = out.toString()
        // Phrase pass: exact case-insensitive whole-phrase repair.
        // NOTE: never lowercase-then-index — 'İ'.lowercase() expands to
        // 2 chars ("i\u0307"), desyncing lowered offsets from the original
        // and throwing StringIndexOutOfBoundsException (this fn never
        // throws). regionMatches scans original offsets directly, so no
        // fold expansion can desync; the end guard is belt-and-braces.
        for (p in phrases.sortedByDescending { it.length }) {
            if (p.length > boosted.length) continue
            var idx = -1
            var s = 0
            while (s + p.length <= boosted.length) {
                if (boosted.regionMatches(s, p, 0, p.length, ignoreCase = true)) {
                    idx = s
                    break
                }
                s++
            }
            if (idx < 0) continue
            val beforeOk = idx == 0 || !isWordChar(boosted[idx - 1])
            val end = idx + p.length
            if (end > boosted.length) continue
            val afterOk = end == boosted.length || !isWordChar(boosted[end])
            if (beforeOk && afterOk && boosted.substring(idx, end) != p) {
                boosted = boosted.substring(0, idx) + p + boosted.substring(end)
            }
        }
        return boosted
    }

    /**
     * Local-path dictionary post-process: the same snippet expansion
     * the server path gets in `engine.rs::finish_inner`
     * (`expand_snippets` over comma-split pairs), followed by the
     * WORD word-boost ([applyWordBoost]). Applied EXACTLY once, in
     * [Dictation.finish] on local hits only — server text is already
     * expanded in Rust, and double application would diverge on nested
     * triggers. Never throws (loader failures → passthrough).
     *
     * Channel findings (sherpa-onnx 1.13.8, verified against
     * `classes.jar` via javap + upstream `sherpa-onnx/csrc` at
     * tag v1.13.8):
     * - (1) whisper prompt: NO channel. `OfflineWhisperModelConfig`
     *   exposes only encoder/decoder/language/task/tailPaddings/
     *   timestamps — no prompt setter (the server path sends `prompt`
     *   as an HTTP multipart field; nothing equivalent exists offline).
     * - (2) ruleFsts/ruleFars: knob exists on
     *   `OfflineRecognizerConfig`, but it is inverse-text-normalization
     *   (kaldifst rewrite rules applied post-decode), not word bias —
     *   it needs precompiled .fst/.far artifacts per dictionary state
     *   and cannot be synthesized Kotlin-side.
     * - (3) hotwordsFile/hotwordsScore: knobs exist, but native
     *   `Validate()` hard-fails unless
     *   `decoding_method=modified_beam_search`, which is transducer-only;
     *   our moonshine/whisper path runs greedy_search, so setting
     *   hotwords would break recognizer creation outright (whisper +
     *   moonshine impls never consult hotwords).
     * With no viable decoder channel, WORDS ship as this explicit
     * post-process approximation: near-miss repair, never acoustic
     * re-bias. A word the decoder drops entirely is NOT recovered.
     */
    fun postProcessLocal(
        text: String,
        snippets: List<Pair<String, String>>,
        words: List<String> = emptyList(),
    ): String = applyWordBoost(expandSnippets(text, snippetPairs(snippets)), words)

    fun postProcessLocal(text: String): String =
        try {
            postProcessLocal(text, loadSnippetsOrEmpty(), loadWordsOrEmpty())
        } catch (_: Throwable) {
            text
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
            // Streaming transducers take the whole utterance (desktop
            // parity: no VAD trim, no short-utterance skip); the
            // offline engines still need at least one VAD range.
            if (ranges.isEmpty() && entry.engine != LocalSttModels.Engine.TRANSDUCER) return null
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
            if (entry.engine == LocalSttModels.Engine.TRANSDUCER) {
                // Streaming transducer: one stream for the whole
                // utterance (see decodeOnlineUtterance) — per-chunk
                // fresh streams starve its language/decoder state.
                val t1 = android.os.SystemClock.elapsedRealtime()
                val utterance =
                    SherpaRunner.decodeOnlineUtterance(rec, pcm16, sampleRate) ?: ""
                if (utterance.isNotBlank()) sb.append(utterance.trim())
                decodeMs = android.os.SystemClock.elapsedRealtime() - t1
            } else {
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

        /**
         * Trailing-silence pad for utterance streaming (samples @16k):
         * one 560ms chunk + margin so the final speech frames sit in a
         * full decode window with right context. See
         * [decodeOnlineUtterance].
         */
        private const val ONLINE_TAIL_PAD_SAMPLES = 9600

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

        /**
         * Loud twin of [call] for the online path: missing methods and
         * throws are logged (method + message) instead of swallowed, so
         * a silently-noop streaming call shows up in logcat. Never
         * throws itself.
         */
        private fun callOnline(target: Any, method: String, vararg args: Any?): Any? {
            try {
                val m = target.javaClass.methods.firstOrNull {
                    it.name == method && it.parameterCount == args.size
                } ?: run {
                    Log.w(TAG, "online missing $method/${args.size}")
                    return null
                }
                return m.invoke(target, *args)
            } catch (e: Exception) {
                Log.w(TAG, "online $method threw ${e.javaClass.simpleName}: ${e.message}")
                return null
            } catch (e: LinkageError) {
                Log.w(TAG, "online $method linkage ${e.javaClass.simpleName}: ${e.message}")
                return null
            }
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
                    LocalSttModels.Engine.TRANSDUCER -> {
                        // Streaming transducer (Nemotron): a different
                        // recognizer class — hand off to the online path.
                        return createOnlineRecognizer(dir, entry)
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

        /**
         * Streaming-transducer recognizer (Nemotron) via the ONLINE
         * API. Class/method spec verified against the 1.13.8 AAR
         * `classes.jar` via javap (constructor takes nullable
         * AssetManager + config like the offline one; `createStream`
         * takes a hotwords string — empty here; per-stream language
         * goes through `setOption("language", …)`; no `setLanguage`
         * exists). Null when the AAR is absent, files are missing, or
         * the native lib fails.
         */
        fun createOnlineRecognizer(dir: File, entry: LocalSttModels.Entry): Any? {
            try {
                fun fileFor(vararg keys: String): String {
                    val name = entry.files.first { f ->
                        keys.any { k -> k in f.name }
                    }.name
                    return File(dir, name).absolutePath
                }
                val tokens = File(
                    dir,
                    entry.files.first { it.name.endsWith("tokens.txt") }.name,
                ).absolutePath
                val transducer = newOf("$PKG.OnlineTransducerModelConfig") ?: return null
                call(transducer, "setEncoder", fileFor("encoder"))
                call(transducer, "setDecoder", fileFor("decoder"))
                call(transducer, "setJoiner", fileFor("joiner"))
                val model = newOf("$PKG.OnlineModelConfig") ?: return null
                call(model, "setTransducer", transducer)
                call(model, "setTokens", tokens)
                // Threads stay at the upstream default (1): every
                // working reference (Kotlin demo, CLI, desktop server)
                // runs the online path single-threaded; the offline
                // path keeps its own numThreads=2 below.
                call(model, "setProvider", "cpu")
                val feat = newOf("$PKG.FeatureConfig") ?: return null
                call(feat, "setSampleRate", 16000)
                call(feat, "setFeatureDim", 80)
                val config = newOf("$PKG.OnlineRecognizerConfig") ?: return null
                call(config, "setFeatConfig", feat)
                call(config, "setModelConfig", model)
                call(config, "setDecodingMethod", "greedy_search")
                call(config, "setMaxActivePaths", 4)
                val recCls = loadClass("$PKG.OnlineRecognizer") ?: return null
                // OnlineRecognizer(AssetManager, OnlineRecognizerConfig):
                // null assets → newFromFile, models live in filesDir.
                val ctor = recCls.constructors.firstOrNull { it.parameterCount == 2 } ?: return null
                return ctor.newInstance(null, config)
            } catch (_: Exception) {
                Log.w(TAG, "online recognizer create failed")
                return null
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "sherpa native missing")
                return null
            } catch (_: LinkageError) {
                Log.w(TAG, "sherpa classes missing (lite build?)")
                return null
            }
        }

        /**
         * Streaming-transducer UTTERANCE decode: ONE online stream for
         * the whole utterance. Transducers carry decoder + language
         * state across frames — per-chunk fresh streams starve the
         * language detector on short VAD segments (owner phone repro:
         * valid pipeline, zero tokens). Desktop parity: the FULL
         * utterance goes in (no VAD trim, no short-utterance skip —
         * server-side endpointing owns that path), fed as mic-sized
         * slices with periodic drains; trailing-silence pad (below),
         * inputFinished + final drain + single read at the end. No
         * language is sent (desktop parity: auto and explicit are
         * byte-identical there — the model decides). Emits
         * diagnostics (fed, slices, iterations, tokens, chars). Null
         * (→ server fallback) on any failure. Never throws.
         */
        fun decodeOnlineUtterance(
            rec: Any,
            pcm16: ShortArray,
            sampleRate: Int,
        ): String? {
            try {
                val stream = callOnline(rec, "createStream", "") ?: run {
                    Log.w(TAG, "online stream create failed")
                    return null
                }
                try {
                    var iters = 0
                    var slices = 0
                    // ~100ms mic-sized slices (1600 samples @16k floor).
                    val slice = (sampleRate / 10).coerceAtLeast(1600)
                    var offset = 0
                    while (offset < pcm16.size) {
                        val end = minOf(offset + slice, pcm16.size)
                        val n = end - offset
                        val buf = FloatArray(n) { i -> pcm16[offset + i] / 32768.0f }
                        callOnline(stream, "acceptWaveform", buf, sampleRate)
                        slices++
                        var guard = 0
                        while ((callOnline(rec, "isReady", stream) as? Boolean) == true && guard++ < 50) {
                            callOnline(rec, "decode", stream)
                            iters++
                        }
                        offset = end
                    }
                    // Trailing-silence pad: the streaming encoder needs
                    // right context past the last speech frame to emit
                    // the tail (owner repro in EN+ES: the final word
                    // always missing — "prueba"/"test" dropped). A live
                    // mic always has trailing audio; a burst feed has
                    // none, so one chunk of zeros (~600ms) guarantees a
                    // full final window. Silence decodes to blanks,
                    // never text.
                    callOnline(stream, "acceptWaveform", FloatArray(ONLINE_TAIL_PAD_SAMPLES), sampleRate)
                    callOnline(stream, "inputFinished")
                    var guard = 0
                    while ((callOnline(rec, "isReady", stream) as? Boolean) == true && guard++ < 1000) {
                        callOnline(rec, "decode", stream)
                        iters++
                    }
                    // NOTE: no unconditional tail flush — decoding past
                    // the buffered frames aborts the process (owner
                    // phone repro: crash before commit). The ready gate
                    // above is load-bearing; the tail (< 1 chunk) is
                    // consumed by the last gated pass.
                    val endpoint = callOnline(rec, "isEndpoint", stream) as? Boolean
                    val result = callOnline(rec, "getResult", stream) ?: run {
                        Log.w(TAG, "online getResult null fed=${pcm16.size} slices=$slices iters=$iters")
                        return null
                    }
                    val text = callOnline(result, "getText") as? String
                    val tokens = callOnline(result, "getTokens") as? Array<*>
                    Log.i(TAG, "online utterance fed=${pcm16.size} slices=$slices iters=$iters endpoint=$endpoint tokens=${tokens?.size ?: -1} chars=${text?.length ?: -1}")
                    return text
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
