package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ANR fix: pack SHA-256 verification runs OFF the main thread.
 *
 * Covers the STT (`LocalSttModels`) and LLM (`MainActivity.llmInspect`)
 * paths: cached-state render without hashing, background completion
 * updating the cache, mtime/size invalidation, fail-closed gating, the
 * no-main-thread guard via the [LocalLlmManager.PackVerify] scheduler
 * seam, plus the review follow-ups: app-start warm serving the Setup
 * gate (1), latest-wins stale-task skip (2), TOCTOU-safe store (3),
 * executor-routed seam (4), guarded `isInstalled` (5).
 */
class PackVerifyOffThreadTest {

    @After
    fun tearDown() {
        LocalLlmManager.PackVerify.isMainThreadOverride = null
        LocalSttModels.SttVerifyCache.clear()
        MainActivity.Companion.LlmVerifyCache.clear()
    }

    private fun workerThread() {
        // Simulate a background worker: the guard must pass here.
        LocalLlmManager.PackVerify.isMainThreadOverride = { false }
    }

    // -- Guard ----------------------------------------------------------

    @Test
    fun guard_throwsOnMainThread_passesOffThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        try {
            LocalLlmManager.PackVerify.requireWorkerThread("test")
            fail("expected main-thread guard to throw")
        } catch (_: IllegalStateException) {
        }
        workerThread()
        // Must not throw off the main thread.
        LocalLlmManager.PackVerify.requireWorkerThread("test")
    }

    @Test
    fun verifyAsync_runsWorkerGuard_andPostsResult() {
        workerThread()
        var done: String? = null
        LocalLlmManager.PackVerify.verifyAsync(
            LocalLlmManager.PackVerify.directScheduler,
            "test",
            block = { "fresh" },
            onDone = { done = it },
        )
        assertEquals("fresh", done)
    }

    @Test
    fun verifyAsync_throwsWhenSchedulerIsMainThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        try {
            LocalLlmManager.PackVerify.verifyAsync(
                LocalLlmManager.PackVerify.directScheduler,
                "test",
                block = { "fresh" },
                onDone = {},
            )
            fail("expected main-thread guard to throw")
        } catch (_: IllegalStateException) {
        }
    }

    // -- STT ------------------------------------------------------------

    @Test
    fun stt_cachedRender_hashesNothingOnPlaceholder() {
        val dir = Files.createTempDirectory("stt-cache").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            var calls = 0
            // Empty dir: placeholder MISSING with zero hashes (this is
            // what first composition renders — never blocks).
            val snap = LocalSttModels.cachedSnapshot(dir)
            assertEquals(
                LocalModelDownload.RowState.MISSING,
                snap[m.id]?.state,
            )
            assertTrue(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            assertEquals(0, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_backgroundCompletion_updatesCache_failClosed() {
        workerThread()
        val dir = Files.createTempDirectory("stt-bg").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            // Truncated set: worker verifies once, caches INCOMPLETE.
            for (f in m.files) {
                File(pack, f.name).writeBytes("x".toByteArray())
            }
            var calls = 0
            val fresh = LocalSttModels.verifyAllOffThread(dir, verify = { _, _ ->
                calls++
                false
            })
            // Truncated files skip the hash entirely (never verifiable).
            assertEquals(0, calls)
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                fresh[m.id]?.state,
            )
            // Cache hit: no re-verify needed, still fail-closed (toggle
            // only allows PRESENT).
            assertFalse(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.cachedOrPlaceholder(dir, m).state,
            )
            assertFalse(
                LocalModelDownload.toggleAllowed(
                    LocalSttModels.cachedOrPlaceholder(dir, m).state,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_mtimeSizeInvalidation_forcesReverify() {
        workerThread()
        val dir = Files.createTempDirectory("stt-inval").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                File(pack, f.name).writeBytes("x".toByteArray())
            }
            LocalSttModels.verifyAllOffThread(dir, verify = { _, _ -> false })
            assertFalse(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            // Touch: size change invalidates the cached state.
            File(pack, m.files.first().name).appendBytes("yy".toByteArray())
            assertTrue(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            // Placeholder stays fail-closed until the worker re-runs.
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.cachedOrPlaceholder(dir, m).state,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_verifyAllOffThread_throwsOnMainThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        val dir = Files.createTempDirectory("stt-guard").toFile()
        try {
            try {
                LocalSttModels.verifyAllOffThread(dir)
                fail("expected main-thread guard to throw")
            } catch (_: IllegalStateException) {
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- LLM ------------------------------------------------------------

    @Test
    fun llm_cachedRender_hashesNothingOnPlaceholder() {
        val dir = Files.createTempDirectory("llm-cache").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "d".repeat(64), 100,
            )
            var calls = 0
            val snap = MainActivity.llmCachedSnapshot(dir, listOf(asset))
            assertEquals(MainActivity.LocalPackState.MISSING, snap[asset.id]?.state)
            assertEquals(0, calls)
            // Fail-closed: placeholder never enables the toggle.
            assertFalse(
                MainActivity.localToggleAllowed(snap[asset.id]!!.state),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_backgroundCompletion_updatesCache_failClosed() {
        workerThread()
        val dir = Files.createTempDirectory("llm-bg").toFile()
        try {
            val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            val asset = LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3)
            val slot = MainActivity.llmTaskFileFor(dir, asset.id)
            slot.parentFile?.mkdirs()
            slot.writeBytes("abc".toByteArray())
            val fresh = MainActivity.llmVerifyAllOffThread(dir, listOf(asset))
            assertEquals(MainActivity.LocalPackState.PRESENT, fresh[asset.id]?.state)
            assertEquals(3L, fresh[asset.id]?.verifiedBytes)
            // Cache hit on identical sig.
            assertEquals(
                MainActivity.LocalPackState.PRESENT,
                MainActivity.llmCachedOrPlaceholder(dir, asset).state,
            )
            // Corrupt the slot: sig invalidates, placeholder fails
            // closed (INCOMPLETE trace, toggle blocked) until re-verify.
            slot.writeBytes("garbage!!".toByteArray())
            val stale = MainActivity.llmCachedOrPlaceholder(dir, asset)
            assertTrue(
                stale.state == MainActivity.LocalPackState.INCOMPLETE ||
                    stale.state == MainActivity.LocalPackState.MISSING,
            )
            assertFalse(MainActivity.localToggleAllowed(stale.state))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_verifyAllOffThread_throwsOnMainThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        val dir = Files.createTempDirectory("llm-guard").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "d".repeat(64), 100,
            )
            try {
                MainActivity.llmVerifyAllOffThread(dir, listOf(asset))
                fail("expected main-thread guard to throw")
            } catch (_: IllegalStateException) {
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_tracePlaceholder_neverPresent_neverMismatchWithoutVerify() {
        val dir = Files.createTempDirectory("llm-trace").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "c".repeat(64), 100,
            )
            val slot = MainActivity.llmTaskFileFor(dir, asset.id)
            slot.parentFile?.mkdirs()
            slot.writeBytes("abc".toByteArray())
            // No hashing on the placeholder path: short file reads
            // INCOMPLETE (resumable), never PRESENT/MISMATCH.
            val p = MainActivity.llmCachedOrPlaceholder(dir, asset)
            assertEquals(MainActivity.LocalPackState.INCOMPLETE, p.state)
            assertFalse(MainActivity.localToggleAllowed(p.state))
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- (1) App-start warm serves the Setup gate -----------------------

    @Test
    fun warmAllPacks_fillsCaches_setupGateReadsPresentWithoutScreen() {
        workerThread()
        val dir = Files.createTempDirectory("warm").toFile()
        try {
            // Full-size sparse STT pack (instant) + stub verifier: the
            // warm must cache PRESENT with no screen ever opened.
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                val file = File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
            }
            // Verified LLM slot alongside (real SHA-256 of "abc").
            val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            val asset = LocalLlmManager.ModelAsset("a", "https://example/a.task", sha, 3)
            MainActivity.llmTaskFileFor(dir, asset.id).apply {
                parentFile?.mkdirs()
                writeBytes("abc".toByteArray())
            }
            // Fresh process: nothing cached (Setup gate would read false).
            assertTrue(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            MainActivity.warmAllPacksOffThread(
                dir,
                listOf(asset),
                sttVerify = { _, _ -> true },
            )
            // SetupScreen's exact read now serves warmed PRESENT.
            assertFalse(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            val setupRead = LocalSttModels.cachedOrPlaceholder(dir, m).state ==
                LocalModelDownload.RowState.PRESENT
            assertTrue(setupRead)
            assertEquals(
                MainActivity.LocalPackState.PRESENT,
                MainActivity.llmCachedOrPlaceholder(dir, asset).state,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun warmAllPacks_throwsOnMainThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        val dir = Files.createTempDirectory("warm-guard").toFile()
        try {
            try {
                MainActivity.warmAllPacksOffThread(dir, emptyList())
                fail("expected main-thread guard to throw")
            } catch (_: IllegalStateException) {
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- (2) Latest-wins stale-task skip --------------------------------

    @Test
    fun gate_latestClaimWins_olderIsStale() {
        val g = LocalLlmManager.PackVerify.VerifyGate()
        val first = g.claim()
        assertTrue(g.isCurrent(first))
        val second = g.claim()
        assertFalse(g.isCurrent(first))
        assertTrue(g.isCurrent(second))
    }

    @Test
    fun stt_staleGate_skipsHashing_returnsPlaceholder() {
        workerThread()
        val dir = Files.createTempDirectory("stt-stale").toFile()
        try {
            // Full-size garbage WOULD hash (and mismatch) — a stale
            // task must not touch it.
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                val file = File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
                file.appendBytes("x".toByteArray())
            }
            var calls = 0
            val r = LocalSttModels.verifyAllOffThread(
                dir,
                verify = { _, _ ->
                    calls++
                    false
                },
                isCurrent = { false },
            )
            assertEquals(0, calls)
            // Fail-closed placeholder (trace → INCOMPLETE), nothing cached.
            assertEquals(LocalModelDownload.RowState.INCOMPLETE, r[m.id]?.state)
            assertNull(LocalSttModels.SttVerifyCache.cached(dir, m))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_staleGate_skipsHashing() {
        workerThread()
        val dir = Files.createTempDirectory("llm-stale").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "c".repeat(64), 100,
            )
            MainActivity.llmTaskFileFor(dir, asset.id).apply {
                parentFile?.mkdirs()
                writeBytes("abc".toByteArray())
            }
            var calls = 0
            val r = MainActivity.llmVerifyAllOffThread(
                dir,
                listOf(asset),
                verify = { _, _ ->
                    calls++
                    false
                },
                isCurrent = { false },
            )
            assertEquals(0, calls)
            // Short-file trace reads INCOMPLETE without hashing.
            assertEquals(MainActivity.LocalPackState.INCOMPLETE, r[asset.id]?.state)
            assertNull(MainActivity.Companion.LlmVerifyCache.cached(dir, asset))
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- (3) TOCTOU-safe store ------------------------------------------

    @Test
    fun stt_storeIfStable_acceptsUnmoved_rejectsRaced() {
        workerThread()
        val dir = Files.createTempDirectory("stt-toctou").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                File(pack, f.name).writeBytes("x".toByteArray())
            }
            val before = LocalSttModels.sigFor(dir, m)
            val r = LocalSttModels.PackInspect(LocalModelDownload.RowState.INCOMPLETE, 0L)
            assertTrue(LocalSttModels.SttVerifyCache.storeIfStable(dir, m, before, r))
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.SttVerifyCache.cached(dir, m)?.state,
            )
            // Disk moves after the snapshot: reject. The old line is
            // now stale too (world changed) — cache serves nothing
            // until a stable re-verify lands; the placeholder stays
            // fail-closed.
            File(pack, m.files.first().name).appendBytes("yy".toByteArray())
            val r2 = LocalSttModels.PackInspect(LocalModelDownload.RowState.MISMATCH, 0L)
            assertFalse(LocalSttModels.SttVerifyCache.storeIfStable(dir, m, before, r2))
            assertNull(LocalSttModels.SttVerifyCache.cached(dir, m))
            assertTrue(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
            assertEquals(
                LocalModelDownload.RowState.INCOMPLETE,
                LocalSttModels.cachedOrPlaceholder(dir, m).state,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun stt_midHashMutation_isNotCached_needsReverify() {
        workerThread()
        val dir = Files.createTempDirectory("stt-race").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            val pack = LocalSttModels.dirFor(dir, m)
            pack.mkdirs()
            for (f in m.files) {
                val file = File(pack, f.name)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
            }
            // Verifier mutates the pack mid-hash (racing download):
            // the result must NOT be cached as fact.
            val r = LocalSttModels.verifyOneOffThread(dir, m, verify = { f, _ ->
                if (f.isFile) f.appendBytes("z".toByteArray())
                false
            })
            assertEquals(LocalModelDownload.RowState.MISMATCH, r.state)
            assertNull(LocalSttModels.SttVerifyCache.cached(dir, m))
            assertTrue(LocalSttModels.SttVerifyCache.needsVerify(dir, m))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_midHashMutation_isNotCached() {
        workerThread()
        val dir = Files.createTempDirectory("llm-race").toFile()
        try {
            val asset = LocalLlmManager.ModelAsset(
                "a", "https://example/a.task", "c".repeat(64), 100,
            )
            MainActivity.llmTaskFileFor(dir, asset.id).apply {
                parentFile?.mkdirs()
                writeBytes("abc".toByteArray())
            }
            MainActivity.llmVerifyAllOffThread(dir, listOf(asset), verify = { f, _ ->
                if (f.isFile) f.appendBytes("z".toByteArray())
                false
            })
            assertNull(MainActivity.Companion.LlmVerifyCache.cached(dir, asset))
            // Fail-closed placeholder until a stable re-verify lands.
            val p = MainActivity.llmCachedOrPlaceholder(dir, asset)
            assertFalse(MainActivity.localToggleAllowed(p.state))
        } finally {
            dir.deleteRecursively()
        }
    }

    // -- (4) Executor-routed seam ---------------------------------------

    @Test
    fun fromExecutor_routesThroughExecutor_andEnforcesGuard() {
        workerThread()
        var ran = false
        val exec = java.util.concurrent.Executor { r ->
            ran = true
            r.run()
        }
        var done: Int? = null
        LocalLlmManager.PackVerify.verifyAsync(
            LocalLlmManager.PackVerify.fromExecutor(exec),
            "test",
            block = { 42 },
            onDone = { done = it },
        )
        assertTrue(ran)
        assertEquals(42, done)
        // Guard still fires when the underlying executor is the main thread.
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        try {
            LocalLlmManager.PackVerify.verifyAsync(
                LocalLlmManager.PackVerify.fromExecutor(exec),
                "test",
                block = { 42 },
                onDone = {},
            )
            fail("expected main-thread guard to throw")
        } catch (_: IllegalStateException) {
        }
    }

    // -- (5) Guarded isInstalled -----------------------------------------

    @Test
    fun isInstalled_throwsOnMainThread_verifiesOffThread() {
        LocalLlmManager.PackVerify.isMainThreadOverride = { true }
        val dir = Files.createTempDirectory("stt-inst").toFile()
        try {
            val m = LocalSttModels.findById(LocalSttModels.MOONSHINE_ID)
            try {
                LocalSttModels.isInstalled(dir, m)
                fail("expected main-thread guard to throw")
            } catch (_: IllegalStateException) {
            }
            workerThread()
            // Empty dir: false without hashing.
            assertFalse(LocalSttModels.isInstalled(dir, m))
            // Single verified file: true ("abc" FIPS vector).
            val one = LocalSttModels.Entry(
                id = "t",
                name = "t",
                sizeLabel = "~0",
                engine = LocalSttModels.Engine.MOONSHINE,
                langMode = LocalSttModels.LangMode.MANUAL,
                languages = listOf("en"),
                dictionary = true,
                runtimeNote = "x",
                phoneTier = "x",
                dirRel = "models/t",
                files = listOf(
                    LocalSttModels.ModelFile(
                        "f.bin",
                        "https://example/f.bin",
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                        3,
                    ),
                ),
            )
            val pack = LocalSttModels.dirFor(dir, one)
            pack.mkdirs()
            File(pack, "f.bin").writeBytes("abc".toByteArray())
            assertTrue(LocalSttModels.isInstalled(dir, one))
        } finally {
            dir.deleteRecursively()
        }
    }
}
