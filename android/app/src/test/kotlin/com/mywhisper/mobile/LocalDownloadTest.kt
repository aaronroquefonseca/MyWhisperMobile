package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row-state machine for the Local download rows (see
 * LocalModelDownload + the STT/LLM Local rows in MainActivity):
 * missing / downloading / present / mismatch / failed, with a
 * fail-closed toggle gate (PRESENT only).
 */
class LocalDownloadTest {

    @Test
    fun resolve_coversAllFiveStates() {
        assertEquals(
            LocalModelDownload.RowState.MISSING,
            LocalModelDownload.resolve(false, false, false, null),
        )
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            LocalModelDownload.resolve(false, false, true, null),
        )
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, false, false, null),
        )
        assertEquals(
            LocalModelDownload.RowState.MISMATCH,
            LocalModelDownload.resolve(false, true, false, null),
        )
        assertEquals(
            LocalModelDownload.RowState.FAILED,
            LocalModelDownload.resolve(false, false, false, "boom"),
        )
    }

    @Test
    fun resolve_precedence_downloadingOverErrorOverPack() {
        // In-flight progress wins over a stale error.
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            LocalModelDownload.resolve(false, false, true, "boom"),
        )
        // A terminal error pins FAILED even with a verified pack
        // until Retry/Delete clears it.
        assertEquals(
            LocalModelDownload.RowState.FAILED,
            LocalModelDownload.resolve(true, false, false, "boom"),
        )
        // Blank error = no error.
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, false, false, ""),
        )
        // Verified pack wins over a stale mismatch flag.
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, true, false, null),
        )
    }

    @Test
    fun toggle_allowedOnlyWhenPresent() {
        assertTrue(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.PRESENT))
        assertFalse(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.MISSING))
        assertFalse(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.DOWNLOADING))
        assertFalse(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.MISMATCH))
        assertFalse(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.FAILED))
    }

    @Test
    fun toggle_matchesLegacyGate() {
        // The row gate must agree with the MainActivity pack gate:
        // PRESENT enables, everything else stays off.
        for (s in LocalModelDownload.RowState.entries) {
            val legacy = when (s) {
                LocalModelDownload.RowState.PRESENT ->
                    MainActivity.LocalPackState.PRESENT
                LocalModelDownload.RowState.MISMATCH ->
                    MainActivity.LocalPackState.MISMATCH
                else -> MainActivity.LocalPackState.MISSING
            }
            assertEquals(
                MainActivity.localToggleAllowed(legacy),
                LocalModelDownload.toggleAllowed(s),
            )
        }
    }

    @Test
    fun progressFraction_nullWhenTotalUnknown_clamped() {
        assertNull(LocalModelDownload.progressFraction(10, null))
        assertNull(LocalModelDownload.progressFraction(10, 0))
        assertNull(LocalModelDownload.progressFraction(-1, 100))
        assertEquals(0.5f, LocalModelDownload.progressFraction(50, 100)!!, 0.001f)
        assertEquals(1.0f, LocalModelDownload.progressFraction(200, 100)!!, 0.001f)
    }

    @Test
    fun sttPack_emptyDirIsMissing() {
        val dir = Files.createTempDirectory("stt-pack").toFile()
        try {
            assertEquals(
                LocalModelDownload.RowState.MISSING,
                LocalStt.packState(dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sttPack_sparseFullGarbageIsMismatch() {
        // Full-size-but-corrupt legacy pack: MISMATCH only here. Sparse
        // (instant, no blocks) so the pinned sizes cost nothing.
        val dir = Files.createTempDirectory("stt-pack").toFile()
        try {
            val pack = LocalStt.packDir(dir)
            pack.mkdirs()
            for (a in LocalStt.packAssets()) {
                val file = File(pack, a.fileName)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(a.sizeBytes) }
                file.appendBytes("x".toByteArray())
            }
            assertEquals(
                LocalModelDownload.RowState.MISMATCH,
                LocalStt.packState(dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sttPack_assetsMatchPinnedManifest() {
        val assets = LocalStt.packAssets()
        assertEquals(
            listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"),
            assets.map { it.fileName },
        )
        assertTrue(assets.all { it.url.contains(LocalStt.PACK_ID) })
        assertTrue(assets.all { it.sha256.length == 64 })
        assertTrue(assets.all { it.sizeBytes > 0 })
    }

    @Test
    fun sttDeletePack_removesFilesAndPartials() {
        val dir = Files.createTempDirectory("stt-pack").toFile()
        try {
            val pack = LocalStt.packDir(dir)
            pack.mkdirs()
            for (a in LocalStt.packAssets()) {
                File(pack, a.fileName).writeBytes("x".toByteArray())
                File(pack, "${a.fileName}.part").writeBytes("y".toByteArray())
            }
            LocalStt.deletePack(dir)
            for (a in LocalStt.packAssets()) {
                assertFalse(File(pack, a.fileName).exists())
                assertFalse(File(pack, "${a.fileName}.part").exists())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llmDeleteModel_removesDestAndPartials() {
        val dir = Files.createTempDirectory("llm-del").toFile()
        try {
            val dest = File(dir, "cleanup.task")
            dest.writeBytes("model".toByteArray())
            val assets = listOf(
                LocalLlmManager.ModelAsset("a", "https://example/a.task", "0".repeat(64), 5),
                LocalLlmManager.ModelAsset("b", "https://example/b.task", "1".repeat(64), 5),
            )
            for (a in assets) {
                LocalLlmManager.partFileFor(dest, a.id).writeBytes("part".toByteArray())
            }
            LocalLlmManager.deleteModel(dest, assets)
            assertFalse(dest.exists())
            for (a in assets) {
                assertFalse(LocalLlmManager.partFileFor(dest, a.id).exists())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
