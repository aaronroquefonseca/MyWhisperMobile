package com.mywhisper.mobile

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device STT model catalog (phone mirror of desktop `stt.rs`
 * `WHISPER_MODELS` / `model_languages` / `capabilities`, trimmed to
 * phone-sane sherpa-onnx 1.13.8 offline entries).
 *
 * Seed rules (verified on HF, never invented):
 * - moonshine tiny-en: kept as the English fast option (existing
 *   pack + SHAs under `core/models`, reused here, never redefined).
 * - whisper tiny/base multilingual int8 (`csukuangfj` sherpa ports):
 *   existence + int8 ONNX magic + full-download SHA-256 verified,
 *   ≤500MB each, Spanish + 98 more via the shared multilingual vocab.
 *
 * Drops (with reason, no download path):
 * - whisper small int8 (~358MB): under the cap but ~3.6x tiny —
 *   too heavy for phone CPU.
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

    /** Default selected model (legacy behavior: English fast pack). */
    const val DEFAULT_ID = MOONSHINE_ID

    const val PREF_SELECTED_MODEL = "local_stt_model"

    val MODELS: List<Entry> = listOf(
        Entry(
            id = MOONSHINE_ID,
            name = "Moonshine Tiny EN",
            sizeLabel = "~44MB",
            engine = Engine.MOONSHINE,
            langMode = LangMode.MANUAL,
            languages = listOf("en"),
            dictionary = true,
            runtimeNote = "English-only · fastest on-device option",
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
            runtimeNote = "99 languages incl. Spanish · auto-detect · int8",
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
            runtimeNote = "99 languages incl. Spanish · better quality, slower · int8",
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

    /** Per-model install state (missing / present / mismatch). Pure java.io. */
    fun stateFor(filesDir: File, entry: Entry): LocalModelDownload.RowState {
        val dir = dirFor(filesDir, entry)
        var anyFile = false
        for (f in entry.files) {
            val file = File(dir, f.name)
            if (!file.isFile || file.length() <= 0) continue
            anyFile = true
            if (!LocalLlmManager.verifySha256(file, f.sha256)) {
                return LocalModelDownload.RowState.MISMATCH
            }
        }
        return when {
            anyFile && isInstalled(filesDir, entry) -> LocalModelDownload.RowState.PRESENT
            anyFile -> LocalModelDownload.RowState.MISMATCH
            else -> LocalModelDownload.RowState.MISSING
        }
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
