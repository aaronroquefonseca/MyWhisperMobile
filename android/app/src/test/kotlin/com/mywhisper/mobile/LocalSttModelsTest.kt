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
 * whisper language wiring rule (auto-detect vs pinned code,
 * fail-safe on unknown codes).
 */
class LocalSttModelsTest {

    @Test
    fun catalog_whisperFamilyThenParakeet() {
        assertEquals(
            listOf(
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.WHISPER_BASE_ID,
                LocalSttModels.WHISPER_SMALL_ID,
                LocalSttModels.WHISPER_TURBO_ID,
                LocalSttModels.PARAKEET_ID,
            ),
            LocalSttModels.MODELS.map { it.id },
        )
        assertEquals(
            listOf(LocalSttModels.Provider.WHISPER, LocalSttModels.Provider.NVIDIA),
            LocalSttModels.providers(),
        )
        assertEquals(listOf(LocalSttModels.PARAKEET_ID), LocalSttModels.forProvider(LocalSttModels.Provider.NVIDIA).map { it.id })
        assertEquals("Whisper", LocalSttModels.providerLabel(LocalSttModels.Provider.WHISPER))
        assertEquals("NVIDIA", LocalSttModels.providerLabel(LocalSttModels.Provider.NVIDIA))
    }

    @Test
    fun metadata_oneGgmlFilePerEntryWithPins() {
        val dirs = mutableSetOf<String>()
        for (m in LocalSttModels.MODELS) {
            assertTrue(m.id, m.name.isNotBlank() && m.sizeLabel.startsWith("~"))
            assertEquals(m.id, 1, m.files.size)
            val f = m.files.single()
            assertTrue(m.id, f.name.startsWith("ggml-") && f.name.endsWith(".bin"))
            assertTrue(m.id, f.url.startsWith("https://huggingface.co/") && f.url.endsWith("/" + f.name))
            assertTrue(m.id, f.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.id, f.sizeBytes > 0)
            assertTrue(m.id, dirs.add(m.dirRel))
            assertEquals(m.id, m.engine == LocalSttModels.Engine.WHISPER, m.dictionary)
        }
    }

    @Test
    fun languages_whisper99AndParakeet25IncludeSpanish() {
        for (m in LocalSttModels.MODELS) {
            assertTrue(m.id, m.languages.contains("es") && m.languages.contains("en"))
            assertEquals(m.id, LocalSttModels.LangMode.AUTO, m.langMode)
        }
        assertEquals(99, LocalSttModels.WHISPER_LANGS.size)
        assertEquals(25, LocalSttModels.PARAKEET_LANGS.toSet().size)
    }

    @Test
    fun copy_hasNoOptInNoiseOrAbsoluteSpeedClaims() {
        // ALL local models are opt-in by design — the label is noise;
        // absolute speed adjectives are wrong (owner phone-tested).
        // RAM-cost guidance ("needs ~N free RAM", "requires
        // flagship") is the only cost copy allowed.
        val banned = listOf("opt-in", "opt in", "slow", "fastest", "slower", "fast pick")
        for (m in LocalSttModels.MODELS) {
            val copy = "${m.name}\n${m.phoneTier}".lowercase()
            for (b in banned) {
                assertFalse("$b in ${m.id}", copy.contains(b))
            }
        }
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
        val gates = mapOf(
            LocalSttModels.WHISPER_TURBO_ID to LocalSttModels.TURBO_MIN_TOTAL_RAM_MB,
            LocalSttModels.PARAKEET_ID to LocalSttModels.PARAKEET_MIN_TOTAL_RAM_MB,
        )
        for (m in LocalSttModels.MODELS) {
            val floor = gates[m.id] ?: 0L
            assertEquals(m.id, floor, m.minTotalRamMb)
            assertTrue(m.id, LocalSttModels.passesRamGate(12_000, m))
            // Unreadable RAM fails closed on gated entries only.
            assertEquals(m.id, floor == 0L, LocalSttModels.passesRamGate(0, m))
        }
        assertFalse(LocalSttModels.passesRamGate(4_000, LocalSttModels.findById(LocalSttModels.WHISPER_TURBO_ID)))
    }

    @Test
    fun defaultAndFallbackResolveToWhisperBase() {
        assertEquals(LocalSttModels.WHISPER_BASE_ID, LocalSttModels.DEFAULT_ID)
        assertEquals(LocalSttModels.DEFAULT_ID, LocalSttModels.findById(null).id)
        // Ids from the sherpa-onnx era (moonshine, nemotron) fall back too.
        assertEquals(LocalSttModels.DEFAULT_ID, LocalSttModels.findById("moonshine-tiny-en").id)
    }

    @Test
    fun whisperLanguageFor_autoDetectVsPinned() {
        val whisper = LocalSttModels.findById(LocalSttModels.WHISPER_TINY_ID)
        assertEquals("", LocalSttModels.whisperLanguageFor("auto", whisper))
        assertEquals("", LocalSttModels.whisperLanguageFor("", whisper))
        assertEquals("", LocalSttModels.whisperLanguageFor("  AUTO ", whisper))
        assertEquals("es", LocalSttModels.whisperLanguageFor("es", whisper))
        assertEquals("en", LocalSttModels.whisperLanguageFor("en", whisper))
        assertEquals("", LocalSttModels.whisperLanguageFor("xx", whisper))
        // Parakeet always auto-detects (no language input).
        val parakeet = LocalSttModels.findById(LocalSttModels.PARAKEET_ID)
        assertEquals("", LocalSttModels.whisperLanguageFor("es", parakeet))
        // Manual entries pin their language whatever the picker says.
        val pinned = whisper.copy(langMode = LocalSttModels.LangMode.MANUAL, languages = listOf("en"))
        assertEquals("en", LocalSttModels.whisperLanguageFor("es", pinned))
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
            val m = LocalSttModels.findById(LocalSttModels.WHISPER_BASE_ID)
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
            dictionary = false, phoneTier = "x",
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
