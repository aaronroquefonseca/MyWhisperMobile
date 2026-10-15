package com.mywhisper.mobile

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * On-device STT model catalog (phone mirror of desktop `stt.rs`
 * `WHISPER_MODELS` / `model_languages` / `capabilities`, trimmed to
 * phone-sane sherpa-onnx 1.13.8 offline entries).
 *
 * Copy honesty rules (owner phone-tested: absolute speed claims are
 * wrong — Note10+ runs whisper-small fast):
 * - no "(opt-in)" anywhere: ALL local models are opt-in by design
 *   (Local entry off by default, verified pack required), the label
 *   is noise.
 * - no absolute speed adjectives ("SLOW", "fastest", "slower"):
 *   pre-first-run copy stays neutral ("speed varies by phone"); after
 *   the first on-device run the row shows the measured
 *   "<x>× realtime on this phone" from the persisted last-decode
 *   speed factor ([speedPrefKey] / [speedFactor] /
 *   [formatSpeedUs]).
 * - cost guidance is RAM-only ("needs ~N free RAM", "requires
 *   flagship"), never speed.
 *
 * Seed rules (verified on HF, never invented):
 * - moonshine tiny-en: kept as the English option and default
 *   (existing pack + SHAs under `core/models`, reused here,
 *   never redefined).
 * - whisper tiny/base multilingual int8 (`csukuangfj` sherpa ports):
 *   existence + int8 ONNX magic + full-download SHA-256 verified,
 *   ≤500MB each, Spanish + 98 more via the shared multilingual vocab.
 * - whisper small multilingual int8 (`csukuangfj/sherpa-onnx-whisper-small`):
 *   existence + `onnx.quantize` magic + full-download SHA-256 verified
 *   (encoder 112442483B, decoder 262226114B, shared tokens 816730B;
 *   ~358MB total, ≤500MB cap). Needs ~1GB free RAM — the registry
 *   carries a RAM cost note; moonshine stays the default.
 * - whisper medium multilingual int8
 *   (`csukuangfj/sherpa-onnx-whisper-medium`, flagship tier):
 *   existence + `onnx.quantize` magic (both files open with the ONNX
 *   protobuf header containing "quantize") + full-download SHA-256
 *   verified 2026-10-15 (encoder 374196283B sha
 *   `1c54582b…d193e`, decoder 571059257B sha `595d00a3…412747`,
 *   shared multilingual tokens 816730B sha `b34b360d…a126` — same
 *   vocab pin as tiny/base/small; ~902MB total). Over the 500MB
 *   phone-sane cap by design: flagship-only tier gated by device
 *   total RAM ([WHISPER_MEDIUM_ID.minTotalRamMb]) with a clear
 *   requires-flagship note; moonshine stays the default.
 *
 * Drops (with reason, no download path):
 * - sense-voice int8: no Spanish (zh/en/ja/ko/yue only).
 * - whistle: own non-sherpa engine absent on phone (+ URL
 *   TO-VERIFY upstream).
 * - parakeet 0.6B int8 (~661MB): over the 500MB cap + transducer
 *   engine work.
 * - `.en` whisper repos: English-only by construction.
 *
 * No streaming claims anywhere (owner-waived): the badge set is
 * languages + dictionary + size only. Dictionary is marked per
 * model (desktop `capabilities` parity: whisper prompts natively;
 * moonshine follows the app-level post-process word-bias
 * convention) — no decoder hotwords are wired in this build, so the
 * effective path for custom words stays the server chain; see the
 * PR note.
 */
object LocalSttModels {
    enum class Engine { MOONSHINE, WHISPER }
    enum class LangMode { AUTO, MANUAL }

    data class ModelFile(
        val name: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    data class Entry(
        val id: String,
        val name: String,
        val sizeLabel: String,
        val engine: Engine,
        val langMode: LangMode,
        /** Full supported code list (desktop WHISPER_LANGS parity for whisper). */
        val languages: List<String>,
        /** Word-bias support flag (desktop capabilities parity + post-process). */
        val dictionary: Boolean,
        val runtimeNote: String,
        /** Recommended phone tier (RAM-cost guidance, shown in reviews/tests). */
        val phoneTier: String,
        /**
         * Minimum device TOTAL RAM in MB for the flagship gate (0 = no
         * gate). Compared against ActivityManager totalMem by callers;
         * see [passesRamGate]. Only the medium flagship tier sets this.
         */
        val minTotalRamMb: Long = 0,
        /** Relative dir under filesDir (moonshine keeps its legacy path). */
        val dirRel: String,
        val files: List<ModelFile>,
    )

    /** Full whisper upstream language list (99 codes, desktop parity). */
    val WHISPER_LANGS: List<String> = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv",
        "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr",
        "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu",
        "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
        "mg", "as", "tt", "ln", "ha", "ba", "jw", "su", "yue",
    )

    const val MOONSHINE_ID = "moonshine-tiny-en"
    const val WHISPER_TINY_ID = "whisper-tiny-multilingual-int8"
    const val WHISPER_BASE_ID = "whisper-base-multilingual-int8"
    const val WHISPER_SMALL_ID = "whisper-small-multilingual-int8"
    const val WHISPER_MEDIUM_ID = "whisper-medium-multilingual-int8"

    /** Default selected model (legacy behavior: English pack). */
    const val DEFAULT_ID = MOONSHINE_ID

    const val PREF_SELECTED_MODEL = "local_stt_model"

    /**
     * Per-model last-decode speed store: SharedPreferences float key
     * per model id holding audio-seconds per decode-second
     * ("× realtime", see [speedFactor]). Written by the decode path
     * after each on-device run, read by the model rows; absent (or
     * invalid) means "not measured on this phone yet".
     */
    fun speedPrefKey(modelId: String): String = "local_stt_speed_$modelId"

    /** Flagship gate: device needs this much TOTAL RAM (MB) for medium. */
    const val MEDIUM_MIN_TOTAL_RAM_MB = 6_000L

    /**
     * Speed factor from one decode: audio seconds per decode second
     * (2.0 = "2.0× realtime"). Null on non-positive inputs
     * (fail-closed: never show a computed claim from bad timings).
     * Pure for unit tests.
     */
    @JvmStatic
    fun speedFactor(audioSecs: Double, decodeMs: Long): Double? {
        if (!audioSecs.isFinite() || audioSecs <= 0.0) return null
        if (decodeMs <= 0) return null
        val f = (audioSecs * 1000.0) / decodeMs.toDouble()
        if (!f.isFinite() || f <= 0.0) return null
        return f
    }

    /**
     * Measured-speed row label, en-US reference ("2.3× realtime on this
     * phone"). Null when never measured / invalid (caller shows the
     * neutral "speed varies by phone" copy instead). Keep in sync with
     * `stt_speed_measured` in values/strings.xml by hand.
     * Pure for unit tests.
     */
    @JvmStatic
    fun formatSpeedUs(speedFactor: Double?): String? {
        if (speedFactor == null || !speedFactor.isFinite() || speedFactor <= 0.0) return null
        return String.format(Locale.US, "%.1f× realtime on this phone", speedFactor)
    }

    /**
     * Stored-float (SharedPreferences) variant of [formatSpeedUs]:
     * NaN / missing / non-positive reads as "not measured yet".
     * Pure for unit tests.
     */
    @JvmStatic
    fun formatStoredSpeed(stored: Float?): String? = formatSpeedUs(storedSpeedFactor(stored))

    /**
     * Stored-float (SharedPreferences) to factor for resource
     * formatting (`stt_speed_measured`): null when never measured /
     * invalid. Pure for unit tests.
     */
    @JvmStatic
    fun storedSpeedFactor(stored: Float?): Double? {
        if (stored == null || !stored.isFinite() || stored <= 0f) return null
        return stored.toDouble()
    }

    /**
     * Flagship RAM gate for [entry] against device total RAM in MB.
     * Entries without a gate always pass; unreadable RAM (<=0) fails
     * a gated entry closed (warn rather than claim it fits).
     * Pure for unit tests.
     */
    @JvmStatic
    fun passesRamGate(totalRamMb: Long, entry: Entry): Boolean {
        if (entry.minTotalRamMb <= 0) return true
        if (totalRamMb <= 0) return false
        return totalRamMb >= entry.minTotalRamMb
    }

    val MODELS: List<Entry> = listOf(
        Entry(
            id = MOONSHINE_ID,
            name = "Moonshine Tiny EN",
            sizeLabel = "~44MB",
            engine = Engine.MOONSHINE,
            langMode = LangMode.MANUAL,
            languages = listOf("en"),
            dictionary = true,
            runtimeNote = "English-only · speed varies by phone",
            phoneTier = "any phone · default",
            dirRel = "models/sherpa-moonshine-v2-tiny.en",
            files = listOf(
                ModelFile(
                    "encoder_model.ort",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/main/encoder_model.ort",
                    "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221",
                    13281600,
                ),
                ModelFile(
                    "decoder_model_merged.ort",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/main/decoder_model_merged.ort",
                    "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0",
                    30412256,
                ),
                ModelFile(
                    "tokens.txt",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/main/tokens.txt",
                    "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049",
                    549350,
                ),
            ),
        ),
        Entry(
            id = WHISPER_TINY_ID,
            name = "Whisper Tiny Multilingual",
            sizeLabel = "~99MB",
            engine = Engine.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · auto-detect · int8 · speed varies by phone",
            phoneTier = "low-RAM phones OK",
            dirRel = "models/sherpa-whisper-tiny-int8",
            files = listOf(
                ModelFile(
                    "tiny-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                    "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                    12937772,
                ),
                ModelFile(
                    "tiny-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                    "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
                    89855401,
                ),
                ModelFile(
                    "tiny-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
        Entry(
            id = WHISPER_BASE_ID,
            name = "Whisper Base Multilingual",
            sizeLabel = "~153MB",
            engine = Engine.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · auto-detect · int8 · speed varies by phone",
            phoneTier = "mid-tier phones and up",
            dirRel = "models/sherpa-whisper-base-int8",
            files = listOf(
                ModelFile(
                    "base-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
                    "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                    29120534,
                ),
                ModelFile(
                    "base-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
                    "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
                    130672026,
                ),
                ModelFile(
                    "base-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
        Entry(
            id = WHISPER_SMALL_ID,
            name = "Whisper Small Multilingual",
            sizeLabel = "~358MB",
            engine = Engine.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · needs ~1GB free RAM · speed varies by phone",
            phoneTier = "flagship RAM recommended · needs ~1GB free RAM",
            dirRel = "models/sherpa-whisper-small-int8",
            files = listOf(
                ModelFile(
                    "small-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                    "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                    112442483,
                ),
                ModelFile(
                    "small-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                    "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
                    262226114,
                ),
                ModelFile(
                    "small-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
        Entry(
            id = WHISPER_MEDIUM_ID,
            name = "Whisper Medium Multilingual",
            sizeLabel = "~902MB",
            engine = Engine.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · requires flagship · needs ~2GB free RAM · speed varies by phone",
            phoneTier = "requires flagship · 6GB+ total RAM",
            minTotalRamMb = MEDIUM_MIN_TOTAL_RAM_MB,
            dirRel = "models/sherpa-whisper-medium-int8",
            files = listOf(
                ModelFile(
                    "medium-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-encoder.int8.onnx",
                    "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e",
                    374196283,
                ),
                ModelFile(
                    "medium-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-decoder.int8.onnx",
                    "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747",
                    571059257,
                ),
                ModelFile(
                    "medium-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
    )

    fun findById(id: String?): Entry =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT_ID }

    fun dirFor(filesDir: File, entry: Entry): File = File(filesDir, entry.dirRel)

    fun totalBytes(entry: Entry): Long = entry.files.sumOf { it.sizeBytes }

    fun isInstalled(filesDir: File, entry: Entry): Boolean {
        val dir = dirFor(filesDir, entry)
        return entry.files.all { f ->
            val file = File(dir, f.name)
            file.isFile && file.length() > 0 && LocalLlmManager.verifySha256(file, f.sha256)
        }
    }

    /**
     * Per-model install state. MISMATCH needs COMPLETE files that
     * fail verify (full count at the pinned sizes); a subset of
     * files, truncated files (short of [ModelFile.sizeBytes] when
     * known), or stale `.part` leftovers (interrupted download) read
     * as INCOMPLETE — resumable via [download] (verified files are
     * skipped), never "SHA mismatch". Pure java.io.
     */
    /**
     * Per-model install state. MISMATCH needs COMPLETE files that
     * fail verify (full count at the pinned sizes); a subset of
     * files, truncated files (short of [ModelFile.sizeBytes] when
     * known), or stale `.part` leftovers (interrupted download) read
     * as INCOMPLETE — resumable via [download] (verified files are
     * skipped), never "SHA mismatch". Pure java.io.
     */
    fun stateFor(filesDir: File, entry: Entry): LocalModelDownload.RowState {
        val dir = dirFor(filesDir, entry)
        var present = 0
        var complete = 0
        for (f in entry.files) {
            val file = File(dir, f.name)
            if (!file.isFile || file.length() <= 0) continue
            present++
            // Unknown size (<= 0) reads as complete (legacy MISMATCH
            // behavior); otherwise the file must reach its pinned size.
            if (f.sizeBytes <= 0 || file.length() >= f.sizeBytes) complete++
        }
        if (present == 0 && !hasPartials(dir, entry)) {
            return LocalModelDownload.RowState.MISSING
        }
        val fullSet = present == entry.files.size && complete == entry.files.size
        if (fullSet && isInstalled(filesDir, entry)) {
            return LocalModelDownload.RowState.PRESENT
        }
        if (fullSet) {
            return LocalModelDownload.RowState.MISMATCH
        }
        return LocalModelDownload.RowState.INCOMPLETE
    }

    /** True when a stale `.part` from an interrupted download lingers. Pure java.io. */
    fun hasPartials(dir: File, entry: Entry): Boolean =
        entry.files.any { f -> File(dir, "${f.name}.part").isFile }

    /** Verified bytes already on disk (INCOMPLETE progress numerator). Pure java.io. */
    fun presentBytes(filesDir: File, entry: Entry): Long {
        val dir = dirFor(filesDir, entry)
        var sum = 0L
        for (f in entry.files) {
            val file = File(dir, f.name)
            if (file.isFile && LocalLlmManager.verifySha256(file, f.sha256)) {
                sum += file.length()
            }
        }
        return sum
    }

    /**
     * Download missing/mismatched files for [entry] (atomic .part
     * rename, SHA-256 verified per file). Skips files that already
     * verify. [onProgress] gets aggregate (received, total).
     */
    fun download(
        filesDir: File,
        entry: Entry,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        val dir = dirFor(filesDir, entry)
        dir.mkdirs()
        val total = totalBytes(entry)
        var receivedBase = 0L
        for (f in entry.files) {
            val dest = File(dir, f.name)
            if (LocalLlmManager.verifySha256(dest, f.sha256)) {
                receivedBase += f.sizeBytes
                onProgress(receivedBase, total)
                continue
            }
            val tmp = File(dir, "${f.name}.part")
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("local-stt download http ${conn.responseCode}")
                }
                conn.inputStream.buffered().use { ins ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(65536)
                        var fileReceived = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            fileReceived += n
                            onProgress(
                                receivedBase + fileReceived.coerceAtMost(f.sizeBytes),
                                total,
                            )
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!LocalLlmManager.verifySha256(tmp, f.sha256)) {
                tmp.delete()
                throw IllegalStateException("local-stt sha256 mismatch for ${f.name}")
            }
            if (dest.exists() && !dest.delete()) throw IllegalStateException("local-stt replace failed")
            if (!tmp.renameTo(dest)) throw IllegalStateException("local-stt rename failed")
            receivedBase += f.sizeBytes
            onProgress(receivedBase, total)
        }
    }

    /** Remove model files + partials (delete action + failed-download cleanup). */
    fun delete(filesDir: File, entry: Entry) {
        val dir = dirFor(filesDir, entry)
        for (f in entry.files) {
            try { File(dir, f.name).delete() } catch (_: Exception) {}
            try { File(dir, "${f.name}.part").delete() } catch (_: Exception) {}
        }
    }

    /**
     * Ids with a verified pack on disk, in catalog order. Packs
     * coexist (one dir per entry); selecting never deletes others.
     * java.io only, JVM-testable.
     */
    fun installedIds(filesDir: File): List<String> =
        MODELS.filter { isInstalled(filesDir, it) }.map { it.id }

    /**
     * Single-active fallback after deleting [deletedId] when
     * [selectedId] was active: first other installed pack, else
     * [DEFAULT_ID] (server chain until anything verifies). Never a
     * dead selection. java.io only, JVM-testable.
     */
    fun fallbackSelection(filesDir: File, deletedId: String, selectedId: String): String =
        LocalModelDownload.fallbackAfterDelete(
            deletedId,
            selectedId,
            installedIds(filesDir),
            DEFAULT_ID,
        )

    /**
     * Whisper language for the sherpa config from the Capture picker
     * code. Verified against sherpa-onnx source
     * (`offline-whisper-greedy-search-decoder.cc`): empty string runs
     * `DetectLanguage` (auto); any other value MUST be a valid code
     * or the native process hard-exits — so unknown codes fail safe
     * to auto instead of crashing. MANUAL entries ignore the picker
     * and pin their single language.
     * Pure for unit tests.
     */
    @JvmStatic
    fun whisperLanguageFor(pickedCode: String, entry: Entry): String {
        if (entry.langMode == LangMode.MANUAL) return entry.languages.first()
        val t = pickedCode.trim().lowercase()
        if (t.isEmpty() || t == "auto") return ""
        return if (entry.languages.contains(t)) t else ""
    }
}
