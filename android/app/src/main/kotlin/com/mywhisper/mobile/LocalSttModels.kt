package com.mywhisper.mobile

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * On-device STT model catalog. Every entry runs on whisper.cpp (ggml)
 * from the downloadable runtime pack ([LocalAiRuntime]), GPU first:
 * OpenAI Whisper (ggerganov/whisper.cpp conversions) and NVIDIA Parakeet
 * TDT v3 (ggml-org/parakeet-GGUF). Each model is ONE file, SHA-256
 * pinned from the Hugging Face LFS metadata.
 *
 * Copy honesty rules (owner phone-tested: absolute speed claims are
 * wrong): no "(opt-in)" anywhere, no absolute speed adjectives; after
 * the first on-device run the row shows the measured
 * "<x>× realtime on this phone" ([speedPrefKey] / [speedFactor] /
 * [formatSpeedUs]); cost guidance is RAM-only.
 *
 * Quantization: q8_0 everywhere the GPU kernels are fastest (OpenCL and
 * Vulkan both have tuned q8_0/q4_0 paths; q5 types fall back to CPU on
 * OpenCL). Whisper medium / large-v3 are not offered: large-v3-turbo is
 * faster than medium at large-v3 accuracy.
 *
 * Dictionary: Whisper takes the custom words as its initial prompt;
 * Parakeet has no prompt channel, so it relies on the app-level
 * post-process word boost like every local model.
 */
object LocalSttModels {
    enum class Engine { WHISPER, PARAKEET }
    enum class LangMode { AUTO, MANUAL }

    /**
     * On-device vendor grouping (provider pill filter): Whisper = OpenAI
     * Whisper family, NVIDIA = Parakeet. Catalog order = display order.
     */
    enum class Provider { WHISPER, NVIDIA }

    /** Display label per provider (pill copy). Pure for unit tests. */
    @JvmStatic
    fun providerLabel(p: Provider): String = when (p) {
        Provider.WHISPER -> "Whisper"
        Provider.NVIDIA -> "NVIDIA"
    }

    /**
     * Providers present in the catalog, in catalog order. Pure for
     * unit tests.
     */
    @JvmStatic
    fun providers(): List<Provider> = MODELS.map { it.provider }.distinct()

    /**
     * Catalog entries for one provider pill (null = All). Pure for
     * unit tests.
     */
    @JvmStatic
    fun forProvider(p: Provider?): List<Entry> =
        if (p == null) MODELS else MODELS.filter { it.provider == p }

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
        /** Vendor group for the provider pill filter (desktop parity). */
        val provider: Provider,
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

    const val WHISPER_TINY_ID = "whisper-tiny-q8"
    const val WHISPER_BASE_ID = "whisper-base-q8"
    const val WHISPER_SMALL_ID = "whisper-small-q8"
    const val WHISPER_TURBO_ID = "whisper-large-v3-turbo-q8"
    const val PARAKEET_ID = "parakeet-tdt-0.6b-v3-q8"

    /** Parakeet TDT 0.6B v3 languages (NVIDIA model card: 25 European languages). */
    val PARAKEET_LANGS: List<String> = listOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
        "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
    )

    /** Default selected model: small, multilingual, runs on any phone. */
    const val DEFAULT_ID = WHISPER_BASE_ID

    const val PREF_SELECTED_MODEL = "local_stt_model"

    /**
     * Per-model last-decode speed store: SharedPreferences float key
     * per model id holding audio-seconds per decode-second
     * ("× realtime", see [speedFactor]). Written by the decode path
     * after each on-device run, read by the model rows; absent (or
     * invalid) means "not measured on this phone yet".
     */
    fun speedPrefKey(modelId: String): String = "local_stt_speed_$modelId"

    /** RAM gate for whisper large-v3-turbo q8 (~874MB of weights). */
    const val TURBO_MIN_TOTAL_RAM_MB = 6_000L

    /** RAM gate for Parakeet TDT 0.6B q8 (~669MB of weights). */
    const val PARAKEET_MIN_TOTAL_RAM_MB = 4_000L

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
            id = WHISPER_TINY_ID,
            name = "Whisper Tiny",
            sizeLabel = "~44MB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages · auto-detect · speed varies by phone",
            phoneTier = "any phone",
            minTotalRamMb = 0,
            dirRel = "models/ggml-whisper-tiny",
            files = listOf(
                ModelFile(
                    "ggml-tiny-q8_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
                    "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca",
                    43537433,
                ),
            ),
        ),
        Entry(
            id = WHISPER_BASE_ID,
            name = "Whisper Base",
            sizeLabel = "~82MB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages · auto-detect · speed varies by phone",
            phoneTier = "any phone · default",
            minTotalRamMb = 0,
            dirRel = "models/ggml-whisper-base",
            files = listOf(
                ModelFile(
                    "ggml-base-q8_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin",
                    "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
                    81768585,
                ),
            ),
        ),
        Entry(
            id = WHISPER_SMALL_ID,
            name = "Whisper Small",
            sizeLabel = "~264MB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages · auto-detect · needs ~600MB free RAM · speed varies by phone",
            phoneTier = "4GB+ phones",
            minTotalRamMb = 0,
            dirRel = "models/ggml-whisper-small",
            files = listOf(
                ModelFile(
                    "ggml-small-q8_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
                    "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
                    264464607,
                ),
            ),
        ),
        Entry(
            id = WHISPER_TURBO_ID,
            name = "Whisper Large v3 Turbo",
            sizeLabel = "~874MB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages · auto-detect · needs ~1.5GB free RAM · speed varies by phone",
            phoneTier = "6GB+ total RAM",
            minTotalRamMb = TURBO_MIN_TOTAL_RAM_MB,
            dirRel = "models/ggml-whisper-large-v3-turbo",
            files = listOf(
                ModelFile(
                    "ggml-large-v3-turbo-q8_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
                    "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
                    874188075,
                ),
            ),
        ),
        Entry(
            id = PARAKEET_ID,
            name = "Parakeet TDT 0.6B v3",
            sizeLabel = "~669MB",
            engine = Engine.PARAKEET,
            provider = Provider.NVIDIA,
            langMode = LangMode.AUTO,
            languages = PARAKEET_LANGS,
            dictionary = false,
            runtimeNote = "25 European languages incl. Spanish · auto-detect · needs ~1.2GB free RAM · speed varies by phone",
            phoneTier = "4GB+ total RAM",
            minTotalRamMb = PARAKEET_MIN_TOTAL_RAM_MB,
            dirRel = "models/ggml-parakeet-tdt-0.6b-v3",
            files = listOf(
                ModelFile(
                    "ggml-parakeet-tdt-0.6b-v3-q8_0.bin",
                    "https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q8_0.bin",
                    "4d64e9e96c2792186d072fde0034df0ad670cf680a2f53069052ead827fd600e",
                    668757119,
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
     * skipped), never "SHA mismatch". Single-pass via [inspect].
     * Pure java.io.
     */
    fun stateFor(filesDir: File, entry: Entry): LocalModelDownload.RowState =
        inspect(filesDir, entry).state

    /** Single-pass pack read: row state + verified on-disk bytes. */
    data class PackInspect(
        val state: LocalModelDownload.RowState,
        val verifiedBytes: Long,
    )

    /**
     * Per-model install state + verified bytes in ONE pass: each
     * present file is SHA-verified at most once (truncated files skip
     * the hash — they can never verify). Two separate passes
     * ([stateFor] then [presentBytes]) would hash full packs twice per
     * recomposition bump. [verify] is injected so unit tests can count
     * calls. Pure java.io.
     */
    fun inspectWithVerifier(
        filesDir: File,
        entry: Entry,
        verify: (File, String) -> Boolean,
    ): PackInspect {
        val dir = dirFor(filesDir, entry)
        var present = 0
        var complete = 0
        var verified = 0
        var verifiedBytes = 0L
        for (f in entry.files) {
            val file = File(dir, f.name)
            if (!file.isFile || file.length() <= 0) continue
            present++
            // Unknown size (<= 0) reads as complete; otherwise the
            // file must reach its pinned size — truncated files stay
            // INCOMPLETE and skip the wasted hash.
            if (f.sizeBytes > 0 && file.length() < f.sizeBytes) continue
            complete++
            if (verify(file, f.sha256)) {
                verified++
                verifiedBytes += file.length()
            }
        }
        if (present == 0 && !hasPartials(dir, entry)) {
            return PackInspect(LocalModelDownload.RowState.MISSING, 0L)
        }
        val fullSet = present == entry.files.size && complete == entry.files.size
        return when {
            fullSet && verified == entry.files.size ->
                PackInspect(LocalModelDownload.RowState.PRESENT, verifiedBytes)
            fullSet ->
                PackInspect(LocalModelDownload.RowState.MISMATCH, verifiedBytes)
            else ->
                PackInspect(LocalModelDownload.RowState.INCOMPLETE, verifiedBytes)
        }
    }

    /** Single-pass inspect with the real SHA verifier. Pure java.io. */
    fun inspect(filesDir: File, entry: Entry): PackInspect =
        // Direct-call lambda, NOT a ::verifySha256 function reference
        // (callable references need Kotlin metadata at runtime, which
        // R8 mangles — release showed every pack as missing).
        inspectWithVerifier(filesDir, entry, { f, s -> LocalLlmManager.verifySha256(f, s) })

    /** True when a stale `.part` from an interrupted download lingers. Pure java.io. */
    fun hasPartials(dir: File, entry: Entry): Boolean =
        entry.files.any { f -> File(dir, "${f.name}.part").isFile }

    /**
     * Verified bytes already on disk (INCOMPLETE progress numerator).
     * Single-pass via [inspect] — shares the one verify per file
     * instead of re-hashing. Pure java.io.
     */
    fun presentBytes(filesDir: File, entry: Entry): Long =
        inspect(filesDir, entry).verifiedBytes

    /**
     * Download missing/mismatched files for [entry] (atomic .part
     * rename, SHA-256 verified per file). Skips files that already
     * verify. Interrupted files resume via HTTP Range (see
     * [LocalLlmManager.resumeAction]); servers that ignore Range get
     * one clean restart, and the trailing SHA-verify keeps a
     * stale/corrupt partial fail-closed either way.
     * [onProgress] gets aggregate (received, total).
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
            // Resume loop (single restart allowed): see
            // LocalLlmManager.downloadModel for the shared protocol.
            var existing = if (tmp.isFile) tmp.length() else 0L
            var allowRestart = true
            while (true) {
                if (existing <= 0) {
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                }
                val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                // VERIFY-hit skips the stream below (partial already
                // good); every other path streams, then shares the
                // verify/rename tail and the loop's closing break.
                var verified = false
                try {
                    if (existing > 0) {
                        conn.setRequestProperty("Range", "bytes=$existing-")
                    }
                    val code = conn.responseCode
                    when (LocalLlmManager.resumeAction(existing, code)) {
                        LocalLlmManager.ResumeAction.FAIL -> throw IllegalStateException(
                            "local-stt download http $code",
                        )
                        LocalLlmManager.ResumeAction.VERIFY -> {
                            if (LocalLlmManager.verifySha256(tmp, f.sha256)) {
                                // Verified partial: skip the stream;
                                // the shared tail below renames and
                                // accounts (receivedBase added once).
                                verified = true
                            } else if (!allowRestart) {
                                try {
                                    tmp.delete()
                                } catch (_: Exception) {
                                }
                                throw IllegalStateException("local-stt sha256 mismatch for ${f.name}")
                            } else {
                                allowRestart = false
                                existing = 0
                                continue
                            }
                        }
                        LocalLlmManager.ResumeAction.RESTART -> {
                            if (allowRestart) {
                                allowRestart = false
                                existing = 0
                                continue
                            }
                            existing = 0
                        }
                        LocalLlmManager.ResumeAction.APPEND -> Unit
                    }
                    if (!verified) {
                        var fileReceived = existing
                        onProgress(
                            (receivedBase + fileReceived).coerceAtMost(total),
                            total,
                        )
                        conn.inputStream.buffered().use { ins ->
                            java.io.FileOutputStream(tmp, existing > 0).buffered().use { out ->
                                val buf = ByteArray(65536)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    fileReceived += n
                                    onProgress(
                                        (receivedBase + fileReceived).coerceAtMost(total),
                                        total,
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (!verified && !LocalLlmManager.verifySha256(tmp, f.sha256)) {
                    tmp.delete()
                    throw IllegalStateException("local-stt sha256 mismatch for ${f.name}")
                }
                if (dest.exists() && !dest.delete()) throw IllegalStateException("local-stt replace failed")
                if (!tmp.renameTo(dest)) throw IllegalStateException("local-stt rename failed")
                receivedBase += f.sizeBytes
                onProgress(receivedBase, total)
                break
            }
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
     * whisper.cpp language from the Capture picker code: "" = auto-detect.
     * Unknown codes fail safe to auto. MANUAL entries pin their single
     * language; Parakeet always auto-detects (no language input).
     * Pure for unit tests.
     */
    @JvmStatic
    fun whisperLanguageFor(pickedCode: String, entry: Entry): String {
        if (entry.engine == Engine.PARAKEET) return ""
        if (entry.langMode == LangMode.MANUAL) return entry.languages.first()
        val t = pickedCode.trim().lowercase()
        if (t.isEmpty() || t == "auto") return ""
        return if (entry.languages.contains(t)) t else ""
    }
}
