package com.mywhisper.mobile

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
    fun catalog_hasFivePhoneSaneEntries() {
        assertEquals(
            listOf(
                LocalSttModels.MOONSHINE_ID,
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.WHISPER_BASE_ID,
                LocalSttModels.WHISPER_SMALL_ID,
                LocalSttModels.WHISPER_MEDIUM_ID,
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
        // Phone-sane cap (500MB) applies to every entry EXCEPT the
        // flagship medium tier, which is over the cap by design
        // (verified ~902MB) and gated by total RAM instead.
        for (m in LocalSttModels.MODELS) {
            val total = LocalSttModels.totalBytes(m)
            if (m.id == LocalSttModels.WHISPER_MEDIUM_ID) {
                assertEquals(946072270L, total)
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
        assertTrue(moon < tiny)
        assertTrue(tiny < base)
        assertTrue(base < small)
        assertTrue(small < medium)
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
    fun ramGate_flagsMediumOnly() {
        val medium = LocalSttModels.findById(LocalSttModels.WHISPER_MEDIUM_ID)
        // Note10+-class device (12GB) passes; 4GB phone does not.
        assertTrue(LocalSttModels.passesRamGate(12_000, medium))
        assertTrue(LocalSttModels.passesRamGate(6_000, medium))
        assertFalse(LocalSttModels.passesRamGate(4_000, medium))
        // Unreadable RAM fails closed on gated entries.
        assertFalse(LocalSttModels.passesRamGate(0, medium))
        assertFalse(LocalSttModels.passesRamGate(-1, medium))
        // Ungated entries always pass (small kept as-is: RAM note, no gate).
        for (m in LocalSttModels.MODELS) {
            if (m.id == LocalSttModels.WHISPER_MEDIUM_ID) continue
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
}
