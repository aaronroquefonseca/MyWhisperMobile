package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Provider-rows UX rework: INCOMPLETE (resumable) vs MISMATCH
 * (complete-but-bad) download states, one test-button pattern with
 * explicit disabled reasons, notification deep-link routing, and the
 * gated LLM self-test. Pure JVM.
 */
class RowsUxReworkTest {

    // -- RowState.INCOMPLETE ------------------------------------------

    @Test
    fun resolve_incompleteBelowPackAndError_neverMismatch() {
        // Interrupted set reads INCOMPLETE, never "SHA mismatch".
        assertEquals(
            LocalModelDownload.RowState.INCOMPLETE,
            LocalModelDownload.resolve(false, false, false, null, true),
        )
        // Downloading still wins (live progress).
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            LocalModelDownload.resolve(false, false, true, null, true),
        )
        // Terminal error still pins FAILED.
        assertEquals(
            LocalModelDownload.RowState.FAILED,
            LocalModelDownload.resolve(false, false, false, "boom", true),
        )
        // Verified pack / complete-but-bad keep precedence.
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, false, false, null, true),
        )
        assertEquals(
            LocalModelDownload.RowState.MISMATCH,
            LocalModelDownload.resolve(false, true, false, null, true),
        )
        assertEquals(
            LocalModelDownload.RowState.MISMATCH,
            LocalModelDownload.resolve(false, true, false, null, false),
        )
    }

    @Test
    fun incomplete_failClosed_resumable() {
        assertFalse(LocalModelDownload.toggleAllowed(LocalModelDownload.RowState.INCOMPLETE))
        // Anything but PRESENT needs Download/Resume; the server chain
        // covers until the pack verifies.
        assertTrue(LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.INCOMPLETE))
        assertFalse(LocalModelDownload.selectNeedsDownload(LocalModelDownload.RowState.PRESENT))
    }

    @Test
    fun gatedRow_incompleteShowsDownloading_liteStillGated() {
        // Full flavor: an interrupted set paints as DOWNLOADING
        // (progress + Resume), never MISMATCH.
        assertEquals(
            MainActivity.GatedRowState.DOWNLOADING,
            MainActivity.gatedRowState(false, false, false, null, true, true),
        )
        // Lite flavor: no runner still forces the full-APK pointer.
        assertEquals(
            MainActivity.GatedRowState.NEED_FULL_FLAVOR,
            MainActivity.gatedRowState(false, false, false, null, false, true),
        )
    }

    // -- STT stateFor: subset/short/partial vs complete ---------------

    @Test
    fun stt_stateFor_subsetIsIncomplete_neverMismatch() {
        val dir = Files.createTempDirectory("stt-incomplete").toFile()
        try {
            val m = LocalSttModels.MODELS.first()
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            // Only the first file of the set lands (kill mid-flight):
            // INCOMPLETE, even though the lone file cannot verify.
            File(pack, m.files.first().name).writeBytes("garbage".toByteArray())
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.stateFor(dir, m),
            )
            // Stale .part with no finals at all: still INCOMPLETE.
            for (f in m.files) File(pack, f.name).delete()
            File(pack, "${m.files.first().name}.part").writeBytes("part".toByteArray())
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.stateFor(dir, m),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_stateFor_truncatedFullCountIsIncomplete() {
        // Review finding: a full file count that is truncated short of
        // the pinned sizes must read INCOMPLETE per the stateFor KDoc,
        // never MISMATCH.
        val dir = Files.createTempDirectory("stt-trunc").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                File(pack, f.name).writeBytes("x".toByteArray())
            }
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.stateFor(dir, m),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_stateFor_completeGarbageIsMismatch() {
        val dir = Files.createTempDirectory("stt-mismatch").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            // Full-size-but-corrupt set (sparse, instant): MISMATCH
            // only here.
            for (f in m.files) {
                val file = File(pack, f.name)
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
    fun stt_presentBytes_zeroUntilVerified() {
        val dir = Files.createTempDirectory("stt-bytes").toFile()
        try {
            val m = LocalSttModels.MODELS.first()
            assertEquals(0L, LocalSttModels.presentBytes(dir, m))
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            File(pack, m.files.first().name).writeBytes("garbage".toByteArray())
            assertEquals(0L, LocalSttModels.presentBytes(dir, m))
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- LLM llmStateFor: short/part vs complete -----------------------

    @Test
    fun llm_stateFor_shortFileIsIncomplete_fullGarbageIsMismatch() {
        val dir = Files.createTempDirectory("llm-incomplete").toFile()
        try {
            // Expected 100 bytes, only 3 unverified bytes land:
            // interrupted, resumable — never MISMATCH.
            val short = LocalLlmManager.ModelAsset("s", "https://example/s.task", "d".repeat(64), 100)
            MainActivity.llmTaskFileFor(dir, short.id).apply {
                parentFile?.mkdirs()
                writeBytes("abc".toByteArray())
            }
            assertEquals(
                MainActivity.LocalPackState.INCOMPLETE,
                MainActivity.llmStateFor(dir, short),
            )
            // Full-size garbage: complete but corrupt -> MISMATCH.
            val bad = LocalLlmManager.ModelAsset("b", "https://example/b.task", "c".repeat(64), 3)
            MainActivity.llmTaskFileFor(dir, bad.id).apply {
                parentFile?.mkdirs()
                writeBytes("garbage".toByteArray())
            }
            assertEquals(
                MainActivity.LocalPackState.MISMATCH,
                MainActivity.llmStateFor(dir, bad),
            )
            // INCOMPLETE never enables, never counts as installed.
            assertFalse(
                MainActivity.localToggleAllowed(MainActivity.LocalPackState.INCOMPLETE),
            )
            assertFalse(
                ProviderTest.localTestAllowed(
                    true,
                    MainActivity.llmStateFor(dir, short) == MainActivity.LocalPackState.PRESENT,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_stateFor_lonePartIsIncomplete() {
        val dir = Files.createTempDirectory("llm-part").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "c".repeat(64), 100,
            )
            val slot = MainActivity.llmTaskFileFor(dir, asset.id)
            slot.parentFile?.mkdirs()
            File(slot.parent, "${slot.name}.part.${asset.id}")
                .writeBytes("part".toByteArray())
            assertEquals(
                MainActivity.LocalPackState.INCOMPLETE,
                MainActivity.llmStateFor(dir, asset),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- Test-button disabled reasons: one pattern ---------------------

    @Test
    fun localTestBlocked_runnerOutranksPack() {
        assertEquals(
            ProviderTest.LocalBlocked.NONE,
            ProviderTest.localTestBlocked(true, true),
        )
        assertEquals(
            ProviderTest.LocalBlocked.NEED_PACK,
            ProviderTest.localTestBlocked(true, false),
        )
        assertEquals(
            ProviderTest.LocalBlocked.NEED_RUNNER,
            ProviderTest.localTestBlocked(false, true),
        )
        assertEquals(
            ProviderTest.LocalBlocked.NEED_RUNNER,
            ProviderTest.localTestBlocked(false, false),
        )
    }

    // -- LLM self-test gating: no bridge, no button -------------------

    @Test
    fun llmSelfTest_offeredOnlyWithRunnerAndBridge() {
        // No bridge, no button — even with runner + verified pack.
        assertFalse(MainActivity.llmSelfTestOffered(false, false))
        assertFalse(MainActivity.llmSelfTestOffered(true, false))
        assertFalse(MainActivity.llmSelfTestOffered(false, true))
        assertTrue(MainActivity.llmSelfTestOffered(true, true))
        // Allowed needs all three: runner + bridge + verified pack.
        assertTrue(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.PRESENT, true),
        )
        assertFalse(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.INCOMPLETE, true),
        )
        assertFalse(
            MainActivity.llmSelfTestAllowed(false, MainActivity.LocalPackState.PRESENT, true),
        )
    }

    @Test
    fun llmSelfTest_presentPackNeverOffersUnrunnableTest() {
        // Owner-phone defect: no JVM inference bridge exists (dead
        // runner deleted), so the default generateFn always throws
        // and every run rendered FAIL. A present pack must never
        // offer the test while the bridge is absent.
        assertFalse(LocalLlmManager.isBridgeSupported())
        assertFalse(MainActivity.llmBridgeSupported())
        assertFalse(MainActivity.llmSelfTestOffered(true, false))
        assertFalse(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.PRESENT, false),
        )
    }

    // -- Notification deep-link routing --------------------------------

    @Test
    fun targetForKind_llmVsStt() {
        assertEquals(
            ModelDownloadSchedule.TARGET_LLM,
            ModelDownloadSchedule.targetForKind(ModelDownloadSchedule.KIND_LLM),
        )
        assertEquals(
            ModelDownloadSchedule.TARGET_STT,
            ModelDownloadSchedule.targetForKind(ModelDownloadSchedule.KIND_STT),
        )
        assertEquals(
            ModelDownloadSchedule.TARGET_STT,
            ModelDownloadSchedule.targetForKind(null),
        )
        assertEquals(
            ModelDownloadSchedule.TARGET_STT,
            ModelDownloadSchedule.targetForKind("bogus"),
        )
    }

    @Test
    fun tabForDownloadTarget_llmOpensCleanup_sttIsDefault() {
        // Root tab order: 0 Setup, 1 Transcription, 2 Text cleanup.
        assertEquals(2, MainActivity.tabForDownloadTarget("llm"))
        assertEquals(1, MainActivity.tabForDownloadTarget("stt"))
        assertEquals(1, MainActivity.tabForDownloadTarget(null))
        assertEquals(1, MainActivity.tabForDownloadTarget("bogus"))
    }

    @Test
    fun deepLinkTargetTab_pendingWins_consumedKeepsCurrent() {
        // A pending tap routes to its tab; a re-tap on the same target
        // re-routes identically (the caller consumes the pending value
        // to null after routing so the key change is never swallowed).
        assertEquals(2, MainActivity.deepLinkTargetTab(2, 0))
        assertEquals(2, MainActivity.deepLinkTargetTab(2, 2))
        assertEquals(1, MainActivity.deepLinkTargetTab(1, 2))
        // No pending tap: the user's current tab stands.
        assertEquals(0, MainActivity.deepLinkTargetTab(null, 0))
        assertEquals(2, MainActivity.deepLinkTargetTab(null, 2))
    }

    @Test
    fun seedDeepLinkTab_freshRoutes_recreateRestores() {
        // Fresh start honors the notification target; a recreation
        // (rotation / process-death restore, saved state present) seeds
        // null so rememberSaveable restores the user's tab instead of
        // yanking it back to the tap target.
        assertEquals(2, MainActivity.seedDeepLinkTab(false, "llm"))
        assertEquals(1, MainActivity.seedDeepLinkTab(false, "stt"))
        assertEquals(null, MainActivity.seedDeepLinkTab(false, null))
        assertEquals(null, MainActivity.seedDeepLinkTab(true, "llm"))
        assertEquals(null, MainActivity.seedDeepLinkTab(true, "stt"))
    }

    // -- Single-pass inspect: one SHA verify per file ------------------

    @Test
    fun stt_inspect_verifiesEachFileAtMostOnce() {
        val dir = Files.createTempDirectory("stt-inspect").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            // Full-size sparse files (instant), stub verifies all.
            for (f in m.files) {
                val file = File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
                file.appendBytes("x".toByteArray())
            }
            val calls = mutableListOf<String>()
            val r = LocalSttModels.inspectWithVerifier(dir, m) { f, sha ->
                calls.add(f.name)
                LocalLlmManager.verifySha256(f, sha)
            }
            assertEquals(LocalModelDownload.RowState.MISMATCH, r.state)
            // One verify per file — never two passes.
            assertEquals(m.files.map { it.name }.sorted(), calls.sorted())
            assertEquals(0L, r.verifiedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_inspect_skipsHashOnTruncated_subsetHashesPresentOnly() {
        val dir = Files.createTempDirectory("stt-inspect2").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            // All files truncated to 1 byte: no hash may run at all.
            for (f in m.files) {
                File(pack, f.name).writeBytes("x".toByteArray())
            }
            var calls = 0
            val r = LocalSttModels.inspectWithVerifier(dir, m) { _, _ ->
                calls++
                false
            }
            assertEquals(LocalModelDownload.RowState.INCOMPLETE, r.state)
            assertEquals(0, calls)
            assertEquals(0L, r.verifiedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_inspect_verifiedBytesSumsVerifiedOnly() {
        val dir = Files.createTempDirectory("stt-inspect3").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                val file = File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
                file.appendBytes("x".toByteArray())
            }
            // Only the first file verifies: MISMATCH overall, bytes =
            // that file alone, each file still hashed exactly once.
            val calls = mutableListOf<String>()
            val first = m.files.first().name
            val r = LocalSttModels.inspectWithVerifier(dir, m) { f, _ ->
                calls.add(f.name)
                f.name == first
            }
            assertEquals(LocalModelDownload.RowState.MISMATCH, r.state)
            assertEquals(m.files.map { it.name }.sorted(), calls.sorted())
            assertEquals(m.files.first().sizeBytes + 1, r.verifiedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_inspect_verifiesSlotAndLegacyAtMostOnce() {
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val dir = Files.createTempDirectory("llm-inspect").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3)
            val slot = MainActivity.llmTaskFileFor(dir, asset.id)
            slot.parentFile?.mkdirs()
            slot.writeBytes("abc".toByteArray())
            val calls = mutableListOf<String>()
            val r = MainActivity.llmInspectWithVerifier(dir, asset) { f, s ->
                calls.add(f.name)
                LocalLlmManager.verifySha256(f, s)
            }
            assertEquals(MainActivity.LocalPackState.PRESENT, r.state)
            assertEquals(3L, r.verifiedBytes)
            // Slot verifies: legacy short-circuits, slot hashed once.
            assertEquals(listOf(slot.name), calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_inspect_shortFileIncomplete_legacyStillCountedOnce() {
        val dir = Files.createTempDirectory("llm-inspect2").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "d".repeat(64), 100,
            )
            val slot = MainActivity.llmTaskFileFor(dir, asset.id)
            slot.parentFile?.mkdirs()
            slot.writeBytes("abc".toByteArray())
            val calls = mutableListOf<String>()
            val r = MainActivity.llmInspectWithVerifier(dir, asset) { f, s ->
                calls.add(f.name)
                LocalLlmManager.verifySha256(f, s)
            }
            assertEquals(MainActivity.LocalPackState.INCOMPLETE, r.state)
            assertEquals(0L, r.verifiedBytes)
            // Slot once + legacy probe once — never a second sweep.
            assertEquals(
                listOf(slot.name, LocalLlmManager.DEFAULT_MODEL_NAME).sorted(),
                calls.sorted(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- New copy in en + es -------------------------------------------

    private fun stringMap(resFile: File): Map<String, String> {
        val text = resFile.readText()
        return Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun newStrings_presentEnAndEs() {
        val base = File(System.getProperty("user.dir"), "src/main/res")
        val en = File(base, "values/strings.xml")
        val es = File(base, "values-es/strings.xml")
        assumeTrue(en.isFile)
        assumeTrue(es.isFile)
        for (f in listOf(en, es)) {
            val strings = stringMap(f)
            for (key in listOf("local_resume", "provider_test_need_pack", "llm_selftest_unavailable")) {
                assertTrue("$key present in ${f.parentFile?.name}", strings[key].orEmpty().isNotBlank())
            }
        }
    }
}
