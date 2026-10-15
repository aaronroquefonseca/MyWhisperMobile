package com.mywhisper.mobile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog registry tests (see LocalSttModels): metadata completeness
 * per entry + the per-model download matrix (state / delete), plus
 * the whisper language wiring rule (auto-detect vs pinned code,
 * fail-safe on unknown codes so the native decoder never hard-exits).
 */
class LocalSttModelsTest {

    @Test
    fun catalog_hasThreePhoneSaneEntries() {
        assertEquals(
            listOf(
                LocalSttModels.MOONSHINE_ID,
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.WHISPER_BASE_ID,
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
        for (id in listOf(LocalSttModels.WHISPER_TINY_ID, LocalSttModels.WHISPER_BASE_ID)) {
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
    fun downloadMatrix_under500MbPerModel() {
        for (m in LocalSttModels.MODELS) {
            val total = LocalSttModels.totalBytes(m)
            assertTrue(total in 1..500_000_000)
        }
        // Base costs more than tiny; moonshine stays the smallest.
        val tiny = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_TINY_ID))
        val base = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.WHISPER_BASE_ID))
        val moon = LocalSttModels.totalBytes(LocalSttModels.findById(LocalSttModels.MOONSHINE_ID))
        assertTrue(moon < tiny)
        assertTrue(tiny < base)
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
    fun matrix_garbageFilesAreMismatchPerModel() {
        val dir = Files.createTempDirectory("stt-matrix").toFile()
        try {
            for (m in LocalSttModels.MODELS) {
                val pack = LocalSttModels.dirFor(dir, m)
                pack.mkdirs()
                for (f in m.files) {
                    java.io.File(pack, f.name).writeBytes("garbage".toByteArray())
                }
                assertEquals(
                    m.id,
                    LocalModelDownload.RowState.MISMATCH,
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
