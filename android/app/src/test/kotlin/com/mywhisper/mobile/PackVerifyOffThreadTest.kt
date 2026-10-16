package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ANR fix: pack SHA-256 verification runs OFF the main thread.
 *
 * Covers the STT (`LocalSttModels`) and LLM (`MainActivity.llmInspect`)
 * paths: cached-state render without hashing, background completion
 * updating the cache, mtime/size invalidation, fail-closed gating, and
 * the no-main-thread guard via the [LocalLlmManager.PackVerify]
 * scheduler seam.
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
            val fresh = LocalSttModels.verifyAllOffThread(dir) { _, _ ->
                calls++
                false
            }
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
            LocalSttModels.verifyAllOffThread(dir) { _, _ -> false }
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
}
