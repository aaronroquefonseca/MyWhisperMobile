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
 * - whisper large-v3 multilingual int8
 *   (`csukuangfj/sherpa-onnx-whisper-large-v3`): int8 ONNX magic +
 *   full-download SHA-256 verified (encoder 766671985B, decoder
 *   1008265203B, shared multilingual tokens 816730B — same vocab pin
 *   `b34b360d…a126` as the smaller whispers; ~1.7GB total). Flagship
 *   tier gated at 10GB total RAM; moonshine stays the default.
 * - whisper turbo multilingual int8
 *   (`csukuangfj/sherpa-onnx-whisper-turbo`): int8 ONNX magic +
 *   full-download SHA-256 verified (encoder 674716297B, decoder
 *   361080764B, same shared vocab pin; ~989MB total). Flagship tier
 *   gated at 8GB total RAM; moonshine stays the default.
 * - nemotron speech streaming en 0.6B int8 (transducer,
 *   `csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14`):
 *   int8 ONNX magic + full-download SHA-256 verified (encoder
 *   652916830B, decoder 7257753B, joiner 1735862B, tokens 8952B;
 *   ~631MB total). English-only MANUAL entry, dictionary off (no
 *   hotwords channel for transducers yet), decoded through the
 *   online recognizer path per VAD chunk. No chunk-size claim: the
 *   repo README does not state one.
 * - nemotron 3.5 streaming multilingual 0.6B int8 (transducer,
 *   `csukuangfj2/…-560ms-int8-2026-06-11`): int8 ONNX magic +
 *   full-download SHA-256 verified (encoder 657601403B, decoder
 *   14978075B, joiner 9504438B, tokens 131440B; ~651MB total).
 *   28 base-code languages (19 ready + 13 broad per the sherpa
 *   Nemotron docs; adaptation locales excluded), auto-detect default
 *   with explicit codes via the stream `setOption("language", …)`
 *   knob, dictionary off like its EN sibling.
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
    enum class Engine { MOONSHINE, WHISPER, TRANSDUCER }
    enum class LangMode { AUTO, MANUAL }

    /**
     * On-device vendor grouping (desktop `local_provider` parity,
     * phone-sane subset: Moonshine = Useful Sensors moonshine,
     * Whisper = OpenAI-family via csukuangfj sherpa ports, NVIDIA =
     * Nemotron streaming transducers). Drives the provider pill filter
     * in the models section; catalog order = display order.
     */
    enum class Provider { MOONSHINE, WHISPER, NVIDIA }

    /** Display label per provider (pill copy). Pure for unit tests. */
    @JvmStatic
    fun providerLabel(p: Provider): String = when (p) {
        Provider.MOONSHINE -> "Moonshine"
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

    const val MOONSHINE_ID = "moonshine-tiny-en"
    const val WHISPER_TINY_ID = "whisper-tiny-multilingual-int8"
    const val WHISPER_BASE_ID = "whisper-base-multilingual-int8"
    const val WHISPER_SMALL_ID = "whisper-small-multilingual-int8"
    const val WHISPER_MEDIUM_ID = "whisper-medium-multilingual-int8"
    const val WHISPER_LARGE_ID = "whisper-large-multilingual-int8"
    const val WHISPER_TURBO_ID = "whisper-turbo-multilingual-int8"
    const val NEMOTRON_EN_ID = "nemotron-streaming-en-0.6b-int8"
    const val NEMOTRON35_ID = "nemotron-35-streaming-multilingual-0.6b-int8"

    /**
     * Nemotron 3.5 streaming language codes (base codes; region
     * variants like en-US/en-GB fold in). 19 transcription-ready +
     * 13 broad-coverage locales per the sherpa Nemotron docs; the 8
     * adaptation-ready locales (el/lt/lv/mt/sl/he/th/nn) are excluded
     * — they need fine-tuning before they transcribe.
     */
    val NEMOTRON35_LANGS: List<String> = listOf(
        "en", "es", "fr", "it", "pt", "nl", "de", "tr", "ru", "ar",
        "hi", "ja", "ko", "vi", "uk", "pl", "sv", "cs", "nb", "da",
        "bg", "fi", "hr", "sk", "zh", "hu", "ro", "et",
    )

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

    /** Flagship gate for whisper large-v3 int8 (~1.7GB of weights). */
    const val LARGE_MIN_TOTAL_RAM_MB = 10_000L

    /** Flagship gate for whisper turbo int8 (~989MB of weights). */
    const val TURBO_MIN_TOTAL_RAM_MB = 8_000L

    /** RAM gate for the Nemotron streaming transducers (~650MB of weights). */
    const val NEMOTRON_MIN_TOTAL_RAM_MB = 6_000L

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
            provider = Provider.MOONSHINE,
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
            provider = Provider.WHISPER,
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
            provider = Provider.WHISPER,
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
            provider = Provider.WHISPER,
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
            provider = Provider.WHISPER,
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
        Entry(
            id = WHISPER_LARGE_ID,
            name = "Whisper Large v3 Multilingual",
            sizeLabel = "~1.7GB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · requires flagship · needs ~4GB free RAM · speed varies by phone",
            phoneTier = "requires flagship · 10GB+ total RAM",
            minTotalRamMb = LARGE_MIN_TOTAL_RAM_MB,
            dirRel = "models/sherpa-whisper-large-v3-int8",
            files = listOf(
                ModelFile(
                    "large-v3-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3/resolve/main/large-v3-encoder.int8.onnx",
                    "d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85",
                    766671985,
                ),
                ModelFile(
                    "large-v3-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3/resolve/main/large-v3-decoder.int8.onnx",
                    "ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af",
                    1008265203,
                ),
                ModelFile(
                    "large-v3-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-large-v3/resolve/main/large-v3-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
        Entry(
            id = WHISPER_TURBO_ID,
            name = "Whisper Turbo Multilingual",
            sizeLabel = "~989MB",
            engine = Engine.WHISPER,
            provider = Provider.WHISPER,
            langMode = LangMode.AUTO,
            languages = WHISPER_LANGS,
            dictionary = true,
            runtimeNote = "99 languages incl. Spanish · requires flagship · needs ~2GB free RAM · speed varies by phone",
            phoneTier = "requires flagship · 8GB+ total RAM",
            minTotalRamMb = TURBO_MIN_TOTAL_RAM_MB,
            dirRel = "models/sherpa-whisper-turbo-int8",
            files = listOf(
                ModelFile(
                    "turbo-encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx",
                    "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
                    674716297,
                ),
                ModelFile(
                    "turbo-decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx",
                    "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547",
                    361080764,
                ),
                ModelFile(
                    "turbo-tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt",
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                    816730,
                ),
            ),
        ),
        Entry(
            id = NEMOTRON_EN_ID,
            name = "Nemotron Streaming EN",
            sizeLabel = "~631MB",
            engine = Engine.TRANSDUCER,
            provider = Provider.NVIDIA,
            langMode = LangMode.MANUAL,
            languages = listOf("en"),
            dictionary = false,
            runtimeNote = "English-only · transducer · needs ~1.5GB free RAM · speed varies by phone",
            phoneTier = "6GB+ total RAM",
            minTotalRamMb = NEMOTRON_MIN_TOTAL_RAM_MB,
            dirRel = "models/sherpa-nemotron-streaming-en-0.6b",
            files = listOf(
                ModelFile(
                    "encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main/encoder.int8.onnx",
                    "2f6ae81fe4ccd69ef04cdf048ecd49628e2d3148a6195e152a91b4d2497952dc",
                    652916830,
                ),
                ModelFile(
                    "decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main/decoder.int8.onnx",
                    "1fb1795cb46e7d0e99b2e096eae83f7e324294e895975a1a894b0384cbbe37f6",
                    7257753,
                ),
                ModelFile(
                    "joiner.int8.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main/joiner.int8.onnx",
                    "a3f41dccc0f67f37e4210051d1c39a29d473c841cfc32fe574135bac890db91d",
                    1735862,
                ),
                ModelFile(
                    "tokens.txt",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main/tokens.txt",
                    "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff",
                    8952,
                ),
            ),
        ),
        Entry(
            id = NEMOTRON35_ID,
            name = "Nemotron 3.5 Streaming",
            sizeLabel = "~651MB",
            engine = Engine.TRANSDUCER,
            provider = Provider.NVIDIA,
            langMode = LangMode.AUTO,
            languages = NEMOTRON35_LANGS,
            dictionary = false,
            runtimeNote = "28 languages incl. Spanish · auto-detect · 560ms transducer · needs ~1.5GB free RAM · speed varies by phone",
            phoneTier = "6GB+ total RAM",
            minTotalRamMb = NEMOTRON_MIN_TOTAL_RAM_MB,
            dirRel = "models/sherpa-nemotron-3.5-streaming-0.6b-560ms",
            files = listOf(
                ModelFile(
                    "encoder.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/main/encoder.int8.onnx",
                    "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
                    657601403,
                ),
                ModelFile(
                    "decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/main/decoder.int8.onnx",
                    "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
                    14978075,
                ),
                ModelFile(
                    "joiner.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/main/joiner.int8.onnx",
                    "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
                    9504438,
                ),
                ModelFile(
                    "tokens.txt",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/main/tokens.txt",
                    "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a",
                    131440,
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
        inspectWithVerifier(filesDir, entry, LocalLlmManager::verifySha256)

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
     * and pin their single language. TRANSDUCER entries follow the
     * same rule; the online runner passes non-empty codes via the
     * stream `setOption("language", …)` knob (verified present in
     * the 1.13.8 AAR via javap — no `setLanguage` exists) and empty
     * stays auto-detect (3.5 emits tags that sherpa strips).
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
