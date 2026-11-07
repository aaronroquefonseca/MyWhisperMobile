package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-pack coexistence + selection (see LocalModelDownload, STT
 * catalog [LocalSttModels] + LLM per-asset slots in MainActivity):
 * several downloaded packs coexist, each downloaded row offers
 * Select (single-active) + Delete, selecting a missing pack offers
 * Download (never deletes others), deleting the active pack falls
 * back with a server-chain note (never a dead Local selection), and
 * the choice persists via stable `mw_state` pref keys.
 */
class LocalMultiPackTest {

    // -- select gate (STT + LLM identically) ---

    @Test
    fun select_missingOffersDownload_presentSelectsDirectly() {
        assertTrue(
            LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.MISSING),
        )
        assertTrue(
            LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.DOWNLOADING),
        )
        assertTrue(
            LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.MISMATCH),
        )
        assertTrue(
            LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.FAILED),
        )
        assertFalse(
            LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.PRESENT),
        )
    }

    // -- single-active fallback (shared machine) ---

    @Test
    fun fallback_bystanderDeleteKeepsSelection() {
        assertEquals(
            "b",
            LocalModelDownload.fallbackAfterDelete(
                deletedId = "a",
                selectedId = "b",
                presentIds = listOf("b", "c"),
                defaultId = "a",
            ),
        )
    }

    @Test
    fun fallback_activeDeletePicksFirstRemainingPresent() {
        assertEquals(
            "c",
            LocalModelDownload.fallbackAfterDelete(
                deletedId = "b",
                selectedId = "b",
                presentIds = listOf("b", "c"),
                defaultId = "a",
            ),
        )
    }

    @Test
    fun fallback_nothingLeftFallsBackToDefault_neverDead() {
        // Deleted id excluded even when stale lists still carry it.
        assertEquals(
            "a",
            LocalModelDownload.fallbackAfterDelete(
                deletedId = "b",
                selectedId = "b",
                presentIds = listOf("b"),
                defaultId = "a",
            ),
        )
        assertEquals(
            "a",
            LocalModelDownload.fallbackAfterDelete(
                deletedId = "b",
                selectedId = "b",
                presentIds = emptyList(),
                defaultId = "a",
            ),
        )
    }

    // -- STT coexistence ---

    @Test
    fun stt_coexist_deletingOnePackLeavesTheOther() {
        val dir = Files.createTempDirectory("stt-coexist").toFile()
        try {
            val tiny = LocalSttModels.findById(LocalSttModels.WHISPER_TINY_ID)
            val base = LocalSttModels.findById(LocalSttModels.WHISPER_BASE_ID)
            for (m in listOf(tiny, base)) {
                val pack = LocalSttModels.dirFor(dir, m)
                pack.mkdirs()
                for (f in m.files) {
                    File(pack, f.name).writeBytes("x".toByteArray())
                }
            }
            LocalSttModels.delete(dir, tiny)
            val basePack = LocalSttModels.dirFor(dir, base)
            for (f in base.files) {
                assertTrue(f.name, File(basePack, f.name).isFile)
            }
            assertEquals(
                LocalModelDownload.RowState.MISSING,
                LocalSttModels.stateFor(dir, tiny),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_fallback_bystanderKeepsSelection_missingFallsToDefault() {
        val dir = Files.createTempDirectory("stt-fallback").toFile()
        try {
            // Nothing verified: bystander delete keeps the selection,
            // active delete lands on the default (server chain covers).
            assertEquals(
                LocalSttModels.WHISPER_TINY_ID,
                LocalSttModels.fallbackSelection(
                    dir,
                    LocalSttModels.WHISPER_BASE_ID,
                    LocalSttModels.WHISPER_TINY_ID,
                ),
            )
            assertEquals(
                LocalSttModels.DEFAULT_ID,
                LocalSttModels.fallbackSelection(
                    dir,
                    LocalSttModels.WHISPER_TINY_ID,
                    LocalSttModels.WHISPER_TINY_ID,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- LLM per-asset slots ---

    @Test
    fun llm_slots_areDistinctPerAsset_underModels() {
        val dir = Files.createTempDirectory("llm-slots").toFile()
        try {
            val a = MainActivity.llmTaskFileFor(dir, "gemma3-1b-it-int4")
            val b = MainActivity.llmTaskFileFor(dir, "gemma3-1b-it-mpseq-q8")
            assertFalse(a == b)
            assertEquals(File(File(dir, "models"), "llm-gemma3-1b-it-int4.task"), a)
            assertEquals("models", a.parentFile?.name)
            // Unsafe ids sanitize (never path escape).
            val evil = MainActivity.llmTaskFileFor(dir, "../x")
            assertEquals(File(File(dir, "models"), "llm-___x.task"), evil)
            assertEquals(File(dir, "models"), evil.parentFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_slots_useRuntimeExtension() {
        val dir = Files.createTempDirectory("llm-ext").toFile()
        try {
            val lm = LocalLlmManager.ModelAsset(
                "gemma4-e2b-it",
                "https://example/g4.litertlm",
                "c".repeat(64),
                9,
                runtime = LocalLlmManager.RUNTIME_LITERTLM,
            )
            assertEquals(File(File(dir, "models"), "llm-gemma4-e2b-it.litertlm"), MainActivity.llmTaskFileFor(dir, lm))
            // Id default + explicit ext stay .task (existing callers/tests).
            assertEquals(File(File(dir, "models"), "llm-plain-id.task"), MainActivity.llmTaskFileFor(dir, "plain-id"))
            assertEquals(
                File(File(dir, "models"), "llm-plain-id.litertlm"),
                MainActivity.llmTaskFileFor(dir, "plain-id", "litertlm"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_selection_storedWins_unknownFallsToFirst_emptyIsNull() {
        val assets = listOf(
            LocalLlmManager.ModelAsset("a", "https://example/a.task", "0".repeat(64), 5),
            LocalLlmManager.ModelAsset("b", "https://example/b.task", "1".repeat(64), 5),
        )
        assertEquals("b", MainActivity.llmSelectedAsset(assets, "b")?.id)
        // Unknown / absent stored id never strands the UI on a dead id.
        assertEquals("a", MainActivity.llmSelectedAsset(assets, "nope")?.id)
        assertEquals("a", MainActivity.llmSelectedAsset(assets, null)?.id)
        assertNull(MainActivity.llmSelectedAsset(emptyList(), "a"))
        assertEquals("a", MainActivity.llmDefaultId(assets))
        assertEquals("", MainActivity.llmDefaultId(emptyList()))
    }

    @Test
    fun llm_coexist_twoVerifiedPacksAreBothPresent() {
        // "abc" -> ba7816bf... (FIPS 180-4 vector).
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val dir = Files.createTempDirectory("llm-coexist").toFile()
        try {
            val assets = listOf(
                LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3),
                LocalLlmManager.ModelAsset("b", "https://example/b.task", sha, 3),
            )
            for (a in assets) {
                MainActivity.llmTaskFileFor(dir, a.id).apply {
                    parentFile?.mkdirs()
                    writeBytes("abc".toByteArray())
                }
            }
            for (a in assets) {
                assertEquals(
                    a.id,
                    MainActivity.LocalPackState.PRESENT,
                    MainActivity.llmStateFor(dir, a),
                )
            }
            assertEquals(listOf("a", "b"), MainActivity.llmInstalledIds(dir, assets))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_legacySlotAdopted_placeholderNeverPresent() {
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val dir = Files.createTempDirectory("llm-legacy").toFile()
        try {
            val legacy = LocalLlmManager.defaultTaskFile(dir)
            legacy.parentFile?.mkdirs()
            legacy.writeBytes("abc".toByteArray())
            val asset = LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3)
            // Existing cleanup.task download stays live without re-download.
            assertEquals(
                MainActivity.LocalPackState.PRESENT,
                MainActivity.llmStateFor(dir, asset),
            )
            val ph = LocalLlmManager.ModelAsset("p", "https://example/p.task", "0".repeat(64), 3)
            assertEquals(
                MainActivity.LocalPackState.MISSING,
                MainActivity.llmStateFor(dir, ph),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_stateFor_garbageIsMismatch_emptyIsMissing() {
        val dir = Files.createTempDirectory("llm-state").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "c".repeat(64), 3,
            )
            assertEquals(
                MainActivity.LocalPackState.MISSING,
                MainActivity.llmStateFor(dir, asset),
            )
            MainActivity.llmTaskFileFor(dir, asset.id).apply {
                parentFile?.mkdirs()
                writeBytes("garbage".toByteArray())
            }
            assertEquals(
                MainActivity.LocalPackState.MISMATCH,
                MainActivity.llmStateFor(dir, asset),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_fallback_bystanderKeepsSelection_emptyFallsToDefault() {
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val dir = Files.createTempDirectory("llm-fallback").toFile()
        try {
            val assets = listOf(
                LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3),
                LocalLlmManager.ModelAsset("b", "https://example/b.task", sha, 3),
            )
            // Nothing downloaded: bystander keeps, active lands on default.
            assertEquals(
                "b",
                MainActivity.llmFallbackAfterDelete(dir, assets, "a", "b"),
            )
            assertEquals(
                "a",
                MainActivity.llmFallbackAfterDelete(dir, assets, "b", "b"),
            )
            // Verified "a" survives deleting active "b".
            MainActivity.llmTaskFileFor(dir, "a").apply {
                parentFile?.mkdirs()
                writeBytes("abc".toByteArray())
            }
            assertEquals(
                "a",
                MainActivity.llmFallbackAfterDelete(dir, assets, "b", "b"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- persistence (stable pref keys + fail-closed resolve) ---

    @Test
    fun persist_prefKeysStableAndDistinct() {
        // Renaming these orphans stored selections (restart loses them).
        assertEquals("local_stt_model", LocalSttModels.PREF_SELECTED_MODEL)
        assertEquals("local_llm_model", MainActivity.PREF_SELECTED_LLM)
    }

    @Test
    fun persist_unknownStoredIdsNeverDead() {
        assertEquals(
            LocalSttModels.DEFAULT_ID,
            LocalSttModels.findById("deleted-long-ago").id,
        )
        assertEquals(
            LocalSttModels.DEFAULT_ID,
            LocalSttModels.findById(null).id,
        )
    }
}
