package com.mywhisper.mobile

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File
import kotlin.math.sqrt

/**
 * On-device STT through whisper.cpp (Whisper + Parakeet, ggml) from the
 * downloadable runtime pack ([LocalAiRuntime]), GPU first. The selected
 * catalog model ([LocalSttModels]) decodes the whole utterance in one call
 * (whisper.cpp windows long audio itself); silence-only input never
 * reaches the model ([chunkPcm] finds no speech → server fallback).
 *
 * All entry points return null / false when the model or runtime is
 * absent, the Local chain entry is disabled, or the native call fails —
 * callers fall back to the server chain.
 */
object LocalStt {
    private const val TAG = "MwLocalStt"

    // Chunk constants: mirror core/src/local_stt.rs.
    const val MAX_CHUNK_SECS = 8.0
    const val MIN_CHUNK_SECS = 1.0
    const val SILENCE_CUT_SECS = 0.3
    const val VAD_FRAME_SAMPLES = 320
    const val VAD_RMS_THRESHOLD = 0.02f

    data class Result(val text: String, val loadMs: Long, val decodeMs: Long, val chunks: Int)

    /** Selected catalog model (SharedPreferences, default [LocalSttModels.DEFAULT_ID]). */
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

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun loadWordsOrEmpty(): List<String> =
        try {
            CoreBridge.getWords()
        } catch (_: Throwable) {
            // JVM unit tests / missing native lib: getWords only
            // catches Exception; native-link failures are Errors.
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
     * Documented approximation, NOT decoder bias (see [postProcessLocal]):
     * a word the decoder drops entirely cannot be recovered — only
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
     * Local-path dictionary post-process: the WORD word-boost
     * ([applyWordBoost]), applied EXACTLY once, in [Dictation.finish]
     * on local hits only. Snippets are not expanded here: the core
     * (`engine.rs::finalize_transcript`) recognizes them on the text
     * passed to `finishLocalUtterance`, protects them through cleanup
     * and restores them literally, the same as the server path. Never
     * throws (loader failures → passthrough).
     *
     * Bias channels: Whisper also gets the words as its decoder initial
     * prompt ([dictionaryPrompt]); Parakeet has no prompt input. This
     * post-process near-miss repair runs for both; a word the decoder
     * drops entirely is NOT recovered here.
     */
    fun postProcessLocal(text: String, words: List<String>): String = applyWordBoost(text, words)

    fun postProcessLocal(text: String): String =
        try {
            postProcessLocal(text, loadWordsOrEmpty())
        } catch (_: Throwable) {
            text
        }

    /**
     * Transcribe 16 kHz mono PCM with the selected catalog model. Null
     * when the model or runtime is not installed, the input is silence,
     * or every device failed — caller keeps the server fallback. Never
     * throws. `language` is the Capture picker code.
     */
    fun transcribe(
        context: Context,
        pcm16: ShortArray,
        sampleRate: Int = 16000,
        language: String = "en",
    ): Result? {
        try {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("mw_state", Context.MODE_PRIVATE)
            val entry = selectedEntry(prefs)
            if (pcm16.isEmpty() || !LocalSttModels.isInstalled(app.filesDir, entry)) return null
            val ranges = chunkPcm(pcm16, sampleRate)
            if (ranges.isEmpty()) return null
            if (!LocalAiRuntime.load(app.filesDir)) return null
            // Trim leading/trailing silence only; inner pauses keep context.
            val start = ranges.first().first
            val samples = FloatArray(ranges.last().last - start + 1) { i -> pcm16[start + i] / 32768.0f }
            val model = File(LocalSttModels.dirFor(app.filesDir, entry), entry.files.first().name)
            val kind = if (entry.engine == LocalSttModels.Engine.PARAKEET) LocalAiNative.STT_PARAKEET else LocalAiNative.STT_WHISPER
            val lang = LocalSttModels.whisperLanguageFor(language, entry)
            val prompt = if (entry.engine == LocalSttModels.Engine.WHISPER) dictionaryPrompt(loadWordsOrEmpty()) else ""
            val keepSecs = LocalKeepWarm.readKeepSecs(prefs, LocalKeepWarm.KIND_STT, entry.id, LocalKeepWarm.INDEFINITE)
            return LocalAiRuntime.runOnBestDevice(prefs, "stt") { device ->
                Engine.transcribe(prefs, model.absolutePath, kind, device, samples, lang, prompt, keepSecs)
            }.copy(chunks = ranges.size)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "local transcribe OOM, fallback to server")
            try { unload() } catch (_: Throwable) {}
            return null
        } catch (e: Exception) {
            Log.w(TAG, "local transcribe failed, fallback to server: ${e.message}")
            return null
        }
    }

    /**
     * Whisper initial prompt from the user dictionary: comma-joined head
     * words, capped well under Whisper's 224-token prompt window. Pure.
     */
    @JvmStatic
    fun dictionaryPrompt(words: List<String>, maxChars: Int = 400): String {
        val sb = StringBuilder()
        for (w in words) {
            val t = w.trim()
            if (t.isEmpty()) continue
            if (sb.length + t.length + 2 > maxChars) break
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(t)
        }
        return sb.toString()
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

    /**
     * Server entries around the first enabled Local entry, in Settings
     * order: (any enabled above it, any enabled below it). Without an
     * enabled Local entry every server counts as above. [Dictation]
     * runs the servers above, then the on-device decode, then the
     * servers below. Never throws; unreadable settings → servers first.
     */
    fun remotesAroundLocal(): Pair<Boolean, Boolean> =
        try {
            remotesAroundLocal(CoreBridge.getSettings().getOrThrow().optJSONObject("stt")?.optJSONArray("entries"))
        } catch (_: Exception) {
            true to false
        }

    /** Pure order rule behind [remotesAroundLocal]; mirrors core `ChainPart` (unit-tested). */
    @JvmStatic
    internal fun remotesAroundLocal(entries: org.json.JSONArray?): Pair<Boolean, Boolean> {
        var above = false
        var below = false
        var localSeen = false
        for (i in 0 until (entries?.length() ?: 0)) {
            val e = entries?.optJSONObject(i) ?: continue
            if (!e.optBoolean("enabled", true)) continue
            if (e.optString("kind") == "Local") localSeen = true
            else if (localSeen) below = true
            else above = true
        }
        return above to below
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

    fun unload() = Engine.release()

    /** One resident STT model (memory bound), keyed by path + kind + device. */
    private object Engine {
        private var handle = 0L
        private var key: String? = null
        private var lastUsedMs = 0L
        private val lock = Any()

        fun transcribe(
            prefs: android.content.SharedPreferences,
            path: String,
            kind: Int,
            device: String,
            samples: FloatArray,
            language: String,
            prompt: String,
            keepSecs: Long,
        ): Result = synchronized(lock) {
            val k = "$path|$kind|$device"
            if (handle != 0L && (key != k || LocalKeepWarm.isExpired(lastUsedMs, keepSecs, LocalKeepWarm.nowMs()))) closeLocked()
            var loadMs = 0L
            val warm = handle != 0L
            if (handle == 0L) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                handle = LocalAiNative.sttLoad(path, kind, device, LocalAiRuntime.threads())
                key = k
                loadMs = android.os.SystemClock.elapsedRealtime() - t0
            }
            try {
                val t1 = android.os.SystemClock.elapsedRealtime()
                val text = LocalAiNative.sttTranscribe(handle, samples, language, prompt)
                val decodeMs = android.os.SystemClock.elapsedRealtime() - t1
                // ms of compute per second of audio; cold calls include
                // one-time GPU shader compiles (see LocalLlmManager.Engine).
                if (warm || keepSecs == LocalKeepWarm.IMMEDIATE) {
                    LocalAiRuntime.recordCost(prefs, "stt", device, decodeMs * 16000.0 / samples.size)
                }
                Log.i(TAG, "local decode on $device chars=${text.length} loadMs=$loadMs decodeMs=$decodeMs")
                Result(text, loadMs, decodeMs, 1)
            } catch (e: Exception) {
                closeLocked()
                throw e
            } finally {
                if (keepSecs == LocalKeepWarm.IMMEDIATE) closeLocked() else lastUsedMs = LocalKeepWarm.nowMs()
            }
        }

        fun release() = synchronized(lock) { closeLocked() }

        private fun closeLocked() {
            if (handle != 0L) LocalAiNative.sttFree(handle)
            handle = 0L
            key = null
            lastUsedMs = 0L
        }
    }
}
