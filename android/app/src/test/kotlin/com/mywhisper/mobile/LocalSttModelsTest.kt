package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog registry tests (see LocalSttModels): metadata completeness
 * per entry + the per-model download matrix (state / delete), copy
 * honesty (no opt-in noise, no absolute speed claims — measured
 * ×realtime only), the RTF label helpers, the flagship RAM gate, the
 * medium SHA pins, plus the whisper language wiring rule
 * (auto-detect vs pinned code, fail-safe on unknown codes so the
 * native decoder never hard-exits).
 */
class LocalSttModelsTest {

    @Test
    fun catalog_hasNinePhoneSaneEntries() {
        assertEquals(
            listOf(
                LocalSttModels.MOONSHINE_ID,
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.WHISPER_BASE_ID,
                LocalSttModels.WHISPER_SMALL_ID,
                LocalSttModels.WHISPER_MEDIUM_ID,
                LocalSttModels.WHISPER_LARGE_ID,
                LocalSttModels.WHISPER_TURBO_ID,
                LocalSttModels.NEMOTRON_EN_ID,
                LocalSttModels.NEMOTRON35_ID,
            ),
            LocalSttModels.MODELS.map { it.id },
        )
    }

    @Test
    fun metadata_completePerEntry() {
        val hex64 = Regex("[0-9a-fA-F]{64}")
        for (m in LocalSttModels.MODELS) {
            assertTrue(m.id.isNotBlank())
            assertTrue(m.name.isNotBlank())
            assertTrue(m.sizeLabel.isNotBlank())
            assertTrue(m.runtimeNote.isNotBlank())
            assertTrue(m.phoneTier.isNotBlank())
            assertTrue(m.dirRel.isNotBlank())
            assertTrue(m.languages.isNotEmpty())
            assertTrue(m.files.isNotEmpty())
            for (f in m.files) {
                assertTrue(f.name.isNotBlank())
                assertTrue(f.url.startsWith("https://"))
                assertTrue(hex64.matches(f.sha256))
                assertFalse(MainActivity.isPlaceholderSha(f.sha256))
                assertTrue(f.sizeBytes > 0)
            }
        }
        // Unique ids, dirs and download URLs across the catalog.
        assertEquals(
            LocalSttModels.MODELS.size,
            LocalSttModels.MODELS.map { it.id }.toSet().size,
        )
        assertEquals(
            LocalSttModels.MODELS.size,
            LocalSttModels.MODELS.map { it.dirRel }.toSet().size,
        )
        val urls = LocalSttModels.MODELS.flatMap { m -> m.files.map { it.url } }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun whisperEntries_coverSpanishAnd99Langs() {
        for (id in listOf(
            LocalSttModels.WHISPER_TINY_ID,
            LocalSttModels.WHISPER_BASE_ID,
            LocalSttModels.WHISPER_SMALL_ID,
            LocalSttModels.WHISPER_MEDIUM_ID,
            LocalSttModels.WHISPER_LARGE_ID,
            LocalSttModels.WHISPER_TURBO_ID,
        )) {
            val m = LocalSttModels.findById(id)
            assertEquals(LocalSttModels.Engine.WHISPER, m.engine)
            assertEquals(LocalSttModels.LangMode.AUTO, m.langMode)
            assertEquals(99, m.languages.size)
            assertTrue(m.languages.contains("es"))
            assertTrue(m.languages.contains("en"))
        }
        val moon = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
        assertEquals(LocalSttModels.Engine.MOONSHINE, moon.engine)
        assertEquals(LocalSttModels.LangMode.MANUAL, moon.langMode)
        assertEquals(listOf("en"), moon.languages)
    }

    @Test
    fun downloadMatrix_sizesPinned() {
        // Phone-sane cap (500MB) applies to every UNGATED entry; the
        // flagship/RAM-gated tiers (medium, large, turbo, both
        // Nemotrons) are over the cap by design with verified totals.
        val gatedTotals = mapOf(
            LocalSttModels.WHISPER_MEDIUM_ID to 946072270L,
            LocalSttModels.WHISPER_LARGE_ID to 1775753918L,
            LocalSttModels.WHISPER_TURBO_ID to 1036613791L,
            LocalSttModels.NEMOTRON_EN_ID to 661919397L,
            LocalSttModels.NEMOTRON35_ID to 682215356L,
        )
        for (m in LocalSttModels.MODELS) {
            val total = LocalSttModels.totalBytes(m)
            val pinned = gatedTotals[m.id]
            if (pinned != null) {
                assertEquals(pinned, total)
            } else {
                assertTrue(m.id, total in 1..500_000_000)
            }
        }
        // Cost ordering holds end to end; moonshine stays smallest.
        val tiny = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_TINY_ID))
        val base = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_BASE_ID))
        val small = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_SMALL_ID))
        val medium = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_MEDIUM_ID))
        val moon = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.MOONSHINE_ID))
        val turbo = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_TURBO_ID))
        val large = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_LARGE_ID))
        assertTrue(moon < tiny)
        assertTrue(tiny < base)
        assertTrue(base < small)
        assertTrue(small < medium)
        assertTrue(medium < turbo)
        assertTrue(turbo < large)
    }

    @Test
    fun copy_hasNoOptInNoiseOrAbsoluteSpeedClaims() {
        // ALL local models are opt-in by design — the label is noise;
        // absolute speed adjectives are wrong (owner phone-tested).
        // RAM-cost guidance ("needs ~N free RAM", "requires
        // flagship") is the only cost copy allowed.
        val banned = listOf("opt-in", "opt in", "slow", "fastest", "slower", "fast pick")
        for (m in LocalSttModels.MODELS) {
            val copy = "${m.name}\n${m.runtimeNote}\n${m.phoneTier}".lowercase()
            for (b in banned) {
                assertFalse("$b in ${m.id}", copy.contains(b))
            }
        }
    }

    @Test
    fun smallEntry_neutralCopyWithRamNote() {
        val small = LocalSttModels.findById(LocalSttModels.WHISPER_SMALL_ID)
        // Never the default; unknown ids still fail closed to moonshine.
        assertFalse(small.id == LocalSttModels.DEFAULT_ID)
        assertEquals(LocalSttModels.MOONSHINE_ID, LocalSttModels.DEFAULT_ID)
        // Verified download size pins (HF full-download, never invented).
        assertEquals(375485327L, LocalSttModels.totalBytes(small))
        assertEquals("~358MB", small.sizeLabel)
        // RAM cost note only — no speed adjectives, no opt-in noise.
        val note = small.runtimeNote.lowercase()
        assertTrue(note.contains("ram"))
        assertTrue(note.contains("speed varies by phone"))
    }

    @Test
    fun mediumEntry_verifiedShaPinsAndFlagshipGate() {
        val medium = LocalSttModels.findById(LocalSttModels.WHISPER_MEDIUM_ID)
        // Flagship tier: never the default.
        assertFalse(medium.id == LocalSttModels.DEFAULT_ID)
        assertEquals(LocalSttModels.Engine.WHISPER, medium.engine)
        assertEquals(LocalSttModels.LangMode.AUTO, medium.langMode)
        assertEquals("~902MB", medium.sizeLabel)
        assertEquals("models/sherpa-whisper-medium-int8", medium.dirRel)
        // Full-download SHA-256 pins verified 2026-10-15 (never invented).
        val byName = medium.files.associateBy { it.name }
        assertEquals(
            "1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e",
            byName["medium-encoder.int8.onnx"]?.sha256,
        )
        assertEquals(374196283L, byName["medium-encoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747",
            byName["medium-decoder.int8.onnx"]?.sha256,
        )
        assertEquals(571059257L, byName["medium-decoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            byName["medium-tokens.txt"]?.sha256,
        )
        assertEquals(816730L, byName["medium-tokens.txt"]?.sizeBytes)
        // Clear requires-flagship note: copy + RAM gate.
        assertTrue(medium.phoneTier.lowercase().contains("flagship"))
        assertTrue(medium.runtimeNote.lowercase().contains("flagship"))
        assertEquals(LocalSttModels.MEDIUM_MIN_TOTAL_RAM_MB, medium.minTotalRamMb)
        assertEquals(6_000L, medium.minTotalRamMb)
    }

    @Test
    fun providers_presentInCatalogOrder() {
        assertEquals(
            listOf(
                LocalSttModels.Provider.MOONSHINE,
                LocalSttModels.Provider.WHISPER,
                LocalSttModels.Provider.NVIDIA,
            ),
            LocalSttModels.providers(),
        )
        assertEquals("Moonshine", LocalSttModels.providerLabel(LocalSttModels.Provider.MOONSHINE))
        assertEquals("Whisper", LocalSttModels.providerLabel(LocalSttModels.Provider.WHISPER))
        assertEquals("NVIDIA", LocalSttModels.providerLabel(LocalSttModels.Provider.NVIDIA))
    }

    @Test
    fun forProvider_filtersByVendor() {
        assertEquals(LocalSttModels.MODELS, LocalSttModels.forProvider(null))
        assertEquals(
            listOf(LocalSttModels.MOONSHINE_ID),
            LocalSttModels.forProvider(LocalSttModels.Provider.MOONSHINE).map { it.id },
        )
        val whisper = LocalSttModels.forProvider(LocalSttModels.Provider.WHISPER)
        assertEquals(
            listOf(
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.WHISPER_BASE_ID,
                LocalSttModels.WHISPER_SMALL_ID,
                LocalSttModels.WHISPER_MEDIUM_ID,
                LocalSttModels.WHISPER_LARGE_ID,
                LocalSttModels.WHISPER_TURBO_ID,
            ),
            whisper.map { it.id },
        )
        assertEquals(
            listOf(LocalSttModels.NEMOTRON_EN_ID, LocalSttModels.NEMOTRON35_ID),
            LocalSttModels.forProvider(LocalSttModels.Provider.NVIDIA).map { it.id },
        )
    }

    @Test
    fun nemotron35Langs_desktopParity15() {
        val langs = LocalSttModels.NEMOTRON35_LANGS
        assertEquals(
            listOf(
                "en", "es", "fr", "it", "pt", "nl", "de", "tr", "ru", "ar",
                "hi", "ja", "ko", "vi", "uk",
            ),
            langs,
        )
        // Broad-coverage (pl/sv/zh/…) and adaptation-ready
        // (el/lt/th/…) locales stay out, like desktop NEMOTRON_15.
        for (c in listOf("pl", "sv", "cs", "zh", "el", "lt", "lv", "mt", "sl", "he", "th", "nn")) {
            assertFalse(c, langs.contains(c))
        }
    }

    @Test
    fun transducerLanguage_alwaysAuto() {
        val entry = LocalSttModels.Entry(
            id = "x", name = "x", sizeLabel = "x",
            engine = LocalSttModels.Engine.TRANSDUCER,
            provider = LocalSttModels.Provider.NVIDIA,
            langMode = LocalSttModels.LangMode.AUTO,
            languages = LocalSttModels.NEMOTRON35_LANGS,
            dictionary = false, runtimeNote = "x", phoneTier = "x",
            dirRel = "x", files = emptyList(),
        )
        // Desktop parity: the online path sends no language at all —
        // auto and explicit are identical, the model decides.
        assertEquals("", LocalSttModels.whisperLanguageFor("auto", entry))
        assertEquals("", LocalSttModels.whisperLanguageFor("es", entry))
        assertEquals("", LocalSttModels.whisperLanguageFor("xx", entry))
    }

    @Test
    fun largeTurboEntries_verifiedShaPinsAndGates() {
        val large = LocalSttModels.findById(LocalSttModels.WHISPER_LARGE_ID)
        assertFalse(large.id == LocalSttModels.DEFAULT_ID)
        assertEquals(LocalSttModels.Engine.WHISPER, large.engine)
        assertEquals(LocalSttModels.Provider.WHISPER, large.provider)
        assertEquals(LocalSttModels.LangMode.AUTO, large.langMode)
        assertEquals("~1.7GB", large.sizeLabel)
        assertEquals("models/sherpa-whisper-large-v3-int8", large.dirRel)
        val lbyName = large.files.associateBy { it.name }
        assertEquals(
            "d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85",
            lbyName["large-v3-encoder.int8.onnx"]?.sha256,
        )
        assertEquals(766671985L, lbyName["large-v3-encoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af",
            lbyName["large-v3-decoder.int8.onnx"]?.sha256,
        )
        assertEquals(1008265203L, lbyName["large-v3-decoder.int8.onnx"]?.sizeBytes)
        // Shared multilingual vocab pin, same as the smaller whispers.
        assertEquals(
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            lbyName["large-v3-tokens.txt"]?.sha256,
        )
        assertEquals(10_000L, large.minTotalRamMb)
        assertTrue(large.phoneTier.lowercase().contains("flagship"))

        val turbo = LocalSttModels.findById(LocalSttModels.WHISPER_TURBO_ID)
        assertEquals(LocalSttModels.Engine.WHISPER, turbo.engine)
        assertEquals(LocalSttModels.Provider.WHISPER, turbo.provider)
        assertEquals("~989MB", turbo.sizeLabel)
        assertEquals("models/sherpa-whisper-turbo-int8", turbo.dirRel)
        val tbyName = turbo.files.associateBy { it.name }
        assertEquals(
            "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
            tbyName["turbo-encoder.int8.onnx"]?.sha256,
        )
        assertEquals(674716297L, tbyName["turbo-encoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547",
            tbyName["turbo-decoder.int8.onnx"]?.sha256,
        )
        assertEquals(361080764L, tbyName["turbo-decoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            tbyName["turbo-tokens.txt"]?.sha256,
        )
        assertEquals(8_000L, turbo.minTotalRamMb)
    }

    @Test
    fun nemotronEntries_transducerEngineVerifiedPins() {
        val en = LocalSttModels.findById(LocalSttModels.NEMOTRON_EN_ID)
        assertEquals(LocalSttModels.Engine.TRANSDUCER, en.engine)
        assertEquals(LocalSttModels.Provider.NVIDIA, en.provider)
        assertEquals(LocalSttModels.LangMode.MANUAL, en.langMode)
        assertEquals(listOf("en"), en.languages)
        assertEquals("~631MB", en.sizeLabel)
        assertEquals("models/sherpa-nemotron-streaming-en-0.6b", en.dirRel)
        // No decoder-bias channel for transducers yet: dictionary off.
        assertEquals(false, en.dictionary)
        val ebyName = en.files.associateBy { it.name }
        assertEquals(
            "2f6ae81fe4ccd69ef04cdf048ecd49628e2d3148a6195e152a91b4d2497952dc",
            ebyName["encoder.int8.onnx"]?.sha256,
        )
        assertEquals(652916830L, ebyName["encoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "1fb1795cb46e7d0e99b2e096eae83f7e324294e895975a1a894b0384cbbe37f6",
            ebyName["decoder.int8.onnx"]?.sha256,
        )
        assertEquals(
            "a3f41dccc0f67f37e4210051d1c39a29d473c841cfc32fe574135bac890db91d",
            ebyName["joiner.int8.onnx"]?.sha256,
        )
        assertEquals(1735862L, ebyName["joiner.int8.onnx"]?.sizeBytes)
        assertEquals(6_000L, en.minTotalRamMb)

        val multi = LocalSttModels.findById(LocalSttModels.NEMOTRON35_ID)
        assertEquals(LocalSttModels.Engine.TRANSDUCER, multi.engine)
        assertEquals(LocalSttModels.Provider.NVIDIA, multi.provider)
        assertEquals(LocalSttModels.LangMode.AUTO, multi.langMode)
        assertEquals(15, multi.languages.size)
        assertTrue(multi.languages.contains("es"))
        assertEquals("~651MB", multi.sizeLabel)
        assertEquals("models/sherpa-nemotron-3.5-streaming-0.6b-560ms", multi.dirRel)
        assertEquals(false, multi.dictionary)
        val mbyName = multi.files.associateBy { it.name }
        assertEquals(
            "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
            mbyName["encoder.int8.onnx"]?.sha256,
        )
        assertEquals(657601403L, mbyName["encoder.int8.onnx"]?.sizeBytes)
        assertEquals(
            "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
            mbyName["decoder.int8.onnx"]?.sha256,
        )
        assertEquals(
            "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
            mbyName["joiner.int8.onnx"]?.sha256,
        )
        assertEquals(6_000L, multi.minTotalRamMb)
        // Online path sends no language (desktop parity): auto and
        // explicit both resolve empty — the model decides.
        assertEquals("", LocalSttModels.whisperLanguageFor("auto", multi))
        assertEquals("", LocalSttModels.whisperLanguageFor("es", multi))
        assertEquals("", LocalSttModels.whisperLanguageFor("es", en))
    }

    @Test
    fun rtf_speedFactorMath() {
        // 10s of audio decoded in 5s = 2.0× realtime.
        assertEquals(2.0, LocalSttModels.speedFactor(10.0, 5_000)!!, 1e-9)
        assertEquals(0.5, LocalSttModels.speedFactor(5.0, 10_000)!!, 1e-9)
        // Fail-closed: bad timings never produce a claim.
        assertEquals(null, LocalSttModels.speedFactor(0.0, 5_000))
        assertEquals(null, LocalSttModels.speedFactor(-1.0, 5_000))
        assertEquals(null, LocalSttModels.speedFactor(Double.NaN, 5_000))
        assertEquals(null, LocalSttModels.speedFactor(10.0, 0))
        assertEquals(null, LocalSttModels.speedFactor(10.0, -3))
    }

    @Test
    fun rtf_labelFormatting() {
        assertEquals("2.0× realtime on this phone", LocalSttModels.formatSpeedUs(2.0))
        assertEquals("2.3× realtime on this phone", LocalSttModels.formatSpeedUs(2.34))
        assertEquals("0.5× realtime on this phone", LocalSttModels.formatSpeedUs(0.5))
        // Invalid / unmeasured → null (caller shows neutral copy).
        assertEquals(null, LocalSttModels.formatSpeedUs(null))
        assertEquals(null, LocalSttModels.formatSpeedUs(0.0))
        assertEquals(null, LocalSttModels.formatSpeedUs(-1.0))
        assertEquals(null, LocalSttModels.formatSpeedUs(Double.NaN))
        assertEquals(null, LocalSttModels.formatSpeedUs(Double.POSITIVE_INFINITY))
        // Stored-float path (SharedPreferences reads).
        assertEquals("2.0× realtime on this phone", LocalSttModels.formatStoredSpeed(2.0f))
        assertEquals(null, LocalSttModels.formatStoredSpeed(null))
        assertEquals(null, LocalSttModels.formatStoredSpeed(Float.NaN))
        assertEquals(null, LocalSttModels.formatStoredSpeed(0f))
        assertEquals(null, LocalSttModels.formatStoredSpeed(-2f))
        // Resource-formatting path (stt_speed_measured, locale string).
        assertEquals(2.0, LocalSttModels.storedSpeedFactor(2.0f)!!, 1e-9)
        assertEquals(null, LocalSttModels.storedSpeedFactor(null))
        assertEquals(null, LocalSttModels.storedSpeedFactor(Float.NaN))
        assertEquals(null, LocalSttModels.storedSpeedFactor(0f))
        assertEquals(null, LocalSttModels.storedSpeedFactor(-1f))
        // Pref keys are stable and unique per model.
        val keys = LocalSttModels.MODELS.map { LocalSttModels.speedPrefKey(it.id) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("local_stt_speed_") })
    }

    @Test
    fun ramGate_flagsGatedTiersOnly() {
        val medium = LocalSttModels.findById(LocalSttModels.WHISPER_MEDIUM_ID)
        // Note10+-class device (12GB) passes; 4GB phone does not.
        assertTrue(LocalSttModels.passesRamGate(12_000, medium))
        assertTrue(LocalSttModels.passesRamGate(6_000, medium))
        assertFalse(LocalSttModels.passesRamGate(4_000, medium))
        // Unreadable RAM fails closed on gated entries.
        assertFalse(LocalSttModels.passesRamGate(0, medium))
        assertFalse(LocalSttModels.passesRamGate(-1, medium))
        // Gated tiers and their floors.
        val gates = mapOf(
            LocalSttModels.WHISPER_MEDIUM_ID to 6_000L,
            LocalSttModels.WHISPER_LARGE_ID to 10_000L,
            LocalSttModels.WHISPER_TURBO_ID to 8_000L,
            LocalSttModels.NEMOTRON_EN_ID to 6_000L,
            LocalSttModels.NEMOTRON35_ID to 6_000L,
        )
        for ((id, floor) in gates) {
            val m = LocalSttModels.findById(id)
            assertEquals(id, floor, m.minTotalRamMb)
            assertTrue(id, LocalSttModels.passesRamGate(12_000, m))
            assertFalse(id, LocalSttModels.passesRamGate(0, m))
        }
        // The 12GB owner phone passes every gate.
        for (m in LocalSttModels.MODELS) {
            assertTrue(m.id, LocalSttModels.passesRamGate(12_000, m))
        }
        // Ungated entries always pass (small kept as-is: RAM note, no gate).
        for (m in LocalSttModels.MODELS) {
            if (m.id in gates) continue
            assertEquals(m.id, 0L, m.minTotalRamMb)
            assertTrue(m.id, LocalSttModels.passesRamGate(0, m))
            assertTrue(m.id, LocalSttModels.passesRamGate(512, m))
        }
    }

    @Test
    fun defaultAndFallbackResolveToMoonshine() {
        assertEquals(
            LocalSttModels.MOONSHINE_ID,
            LocalSttModels.findById(null).id,
        )
        assertEquals(
            LocalSttModels.MOONSHINE_ID,
            LocalSttModels.findById("nope").id,
        )
        assertEquals(LocalSttModels.DEFAULT_ID, LocalSttModels.MOONSHINE_ID)
    }

    @Test
    fun whisperLanguageFor_autoDetectVsPinned() {
        val whisper = LocalSttModels.findById(LocalSttModels.WHISPER_TINY_ID)
        // Picker auto (or blank) -> empty = sherpa DetectLanguage.
        assertEquals("", LocalSttModels.whisperLanguageFor("auto", whisper))
        assertEquals("", LocalSttModels.whisperLanguageFor("", whisper))
        assertEquals("", LocalSttModels.whisperLanguageFor("  AUTO ", whisper))
        // Valid codes pass through.
        assertEquals("es", LocalSttModels.whisperLanguageFor("es", whisper))
        assertEquals("en", LocalSttModels.whisperLanguageFor("en", whisper))
        // Unknown codes fail safe to auto (native hard-exits otherwise).
        assertEquals("", LocalSttModels.whisperLanguageFor("xx", whisper))
        // Manual entries pin their language whatever the picker says.
        val moon = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
        assertEquals("en", LocalSttModels.whisperLanguageFor("auto", moon))
        assertEquals("en", LocalSttModels.whisperLanguageFor("es", moon))
    }

    @Test
    fun matrix_emptyDirIsMissingPerModel() {
        val dir = Files.createTempDirectory("stt-matrix").toFile()
        try {
            for (m in LocalSttModels.MODELS) {
                assertEquals(
                    m.id,
                    LocalModelDownload.RowState.MISSING,
                    LocalSttModels.stateFor(dir, m),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun matrix_sparseFullGarbageIsMismatch() {
        // Full-size-but-corrupt files: MISMATCH only here. Sparse
        // (instant, no blocks) so the pinned sizes cost nothing.
        val dir = Files.createTempDirectory("stt-matrix").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                val file = java.io.File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
                file.appendBytes("x".toByteArray())
            }
            assertEquals(
                LocalModelDownload.RowState.MISMATCH,
                LocalSttModels.stateFor(dir, m),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun matrix_truncatedFullCountIsIncompletePerModel() {
        // Full file count but truncated (1 byte each, short of the
        // pinned sizes): INCOMPLETE per the stateFor KDoc, never
        // MISMATCH. Cheap for every catalog entry.
        val dir = Files.createTempDirectory("stt-trunc").toFile()
        try {
            for (m in LocalSttModels.MODELS) {
                val pack = LocalSttModels.dirFor(dir, m)
                pack.mkdirs()
                for (f in m.files) {
                    java.io.File(pack, f.name).writeBytes("x".toByteArray())
                }
                assertEquals(
                    m.id,
                    LocalModelDownload.RowState.INCOMPLETE,
                    LocalSttModels.stateFor(dir, m),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun matrix_deleteRemovesFilesAndPartialsPerModel() {
        val dir = Files.createTempDirectory("stt-matrix").toFile()
        try {
            for (m in LocalSttModels.MODELS) {
                val pack = LocalSttModels.dirFor(dir, m)
                pack.mkdirs()
                for (f in m.files) {
                    java.io.File(pack, f.name).writeBytes("x".toByteArray())
                    java.io.File(pack, "${f.name}.part").writeBytes("y".toByteArray())
                }
                LocalSttModels.delete(dir, m)
                for (f in m.files) {
                    assertFalse(java.io.File(pack, f.name).exists())
                    assertFalse(java.io.File(pack, "${f.name}.part").exists())
                }
                assertEquals(
                    m.id,
                    LocalModelDownload.RowState.MISSING,
                    LocalSttModels.stateFor(dir, m),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sttPackFiles_coverPackAndParts() {
        val entry = LocalSttModels.Entry(
            id = "x", name = "x", sizeLabel = "x",
            engine = LocalSttModels.Engine.WHISPER,
            provider = LocalSttModels.Provider.WHISPER,
            langMode = LocalSttModels.LangMode.AUTO,
            languages = listOf("en"),
            dictionary = false, runtimeNote = "x", phoneTier = "x",
            dirRel = "models/x",
            files = listOf(
                LocalSttModels.ModelFile("a.onnx", "u", "c".repeat(64), 3),
                LocalSttModels.ModelFile("b.onnx", "u", "c".repeat(64), 4),
            ),
        )
        val dir = Files.createTempDirectory("stt-files").toFile()
        try {
            // Absent pack: stable fingerprint across calls.
            val missing1 = MainActivity.sttPackFiles(dir, entry)
                .let { LocalModelDownload.packFingerprint(it) }
            assertEquals(missing1, LocalModelDownload.packFingerprint(MainActivity.sttPackFiles(dir, entry)))
            // Present pack + a growing part move the fingerprint.
            val modelDir = File(dir, "models/x").apply { mkdirs() }
            File(modelDir, "a.onnx").writeBytes(ByteArray(6))
            val part = File(modelDir, "b.onnx.part").apply { writeBytes(ByteArray(2)) }
            val grown = LocalModelDownload.packFingerprint(MainActivity.sttPackFiles(dir, entry))
            assertTrue(grown != missing1)
            part.appendBytes(ByteArray(1))
            assertTrue(LocalModelDownload.packFingerprint(MainActivity.sttPackFiles(dir, entry)) != grown)
        } finally {
            dir.deleteRecursively()
        }
    }
}
