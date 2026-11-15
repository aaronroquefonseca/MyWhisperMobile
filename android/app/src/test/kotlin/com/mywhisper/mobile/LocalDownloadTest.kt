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
    fun needsWaiting_onlyZeroBytesOnMeteredOrOffline() {        val metered = LocalModelDownload.NetState.METERED
        val offline = LocalModelDownload.NetState.OFFLINE
        val unmetered = LocalModelDownload.NetState.UNMETERED
        // Zero progress + blocked posture → waiting UI.
        assertTrue(LocalModelDownload.needsWaiting(null, metered))
        assertTrue(LocalModelDownload.needsWaiting(0L, metered))
        assertTrue(LocalModelDownload.needsWaiting(null, offline))
        assertTrue(LocalModelDownload.needsWaiting(0L, offline))
        // Any bytes flowing → genuinely downloading, never waiting.
        assertFalse(LocalModelDownload.needsWaiting(1L, metered))
        assertFalse(LocalModelDownload.needsWaiting(1L, offline))
        // Unmetered/unknown → zero bytes just means starting up.
        assertFalse(LocalModelDownload.needsWaiting(null, unmetered))
        assertFalse(LocalModelDownload.needsWaiting(0L, unmetered))
        assertFalse(LocalModelDownload.needsWaiting(null, null))
        assertFalse(LocalModelDownload.needsWaiting(0L, null))
    }

    @Test
    fun resolve_checkingBeatsStaleMissing_yieldsToDownloadAndError() {
        // Checking (verify running) reads CHECKING, never stale MISSING.
        assertEquals(
            LocalModelDownload.RowState.CHECKING,
            LocalModelDownload.resolve(false, false, false, null, checking = true),
        )
        // A live download still wins (progress owns the row).
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            LocalModelDownload.resolve(false, false, true, null, checking = true),
        )
        // A pinned error still wins (explicit failure beats background probe).
        assertEquals(
            LocalModelDownload.RowState.FAILED,
            LocalModelDownload.resolve(false, false, false, "boom", checking = true),
        )
        // A known verdict with a stale fingerprint still reads
        // CHECKING: the cached PRESENT/MISMATCH describes changed
        // bytes, so re-verify first, display after.
        assertEquals(
            LocalModelDownload.RowState.CHECKING,
            LocalModelDownload.resolve(true, false, false, null, checking = true),
        )
        // Default stays backward compatible (no checking).
        assertEquals(
            LocalModelDownload.RowState.MISSING,
            LocalModelDownload.resolve(false, false, false, null),
        )
    }

    @Test
    fun cacheReadDoesNotWaitForVerifierAndDeduplicates() {
        val cache = LocalModelDownload.PackVerifyCache()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> {
                cache.getOrPut("model", "fingerprint") {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    "verified"
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<String> {
                cache.getOrPut("model", "fingerprint") { calls.incrementAndGet(); "duplicate" }
            }
            assertFalse(cache.has("model", "fingerprint"))
            release.countDown()
            assertEquals("verified", first.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("verified", second.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun changedPackCannotPublishHeldVerification() {
        for (replace in listOf(false, true)) {
            val dir = Files.createTempDirectory("held-pack").toFile()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            try {
                val file = File(dir, "model").apply { writeText("old") }
                fun fingerprint() = LocalModelDownload.packFingerprint(listOf(file))
                val before = fingerprint()
                val cache = LocalModelDownload.PackVerifyCache()
                val result = executor.submit<Boolean> {
                    try {
                        cache.getStable("model", before, { fingerprint() }) {
                            entered.countDown()
                            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            true
                        } ?: false
                    } catch (_: Exception) { false }
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (replace) {
                    val next = File(dir, "next").apply { writeText("new"); setLastModified(file.lastModified()) }
                    Files.move(next.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else assertTrue(file.delete())
                release.countDown()
                assertFalse(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(cache.has("model", before))
            } finally {
                release.countDown()
                executor.shutdownNow()
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun selectionChangeRejectsHeldVerdict() {
        val cache = LocalModelDownload.PackVerifyCache()
        val selection = java.util.concurrent.atomic.AtomicReference("first")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean?> {
                cache.getStable("first", "fp", { "fp" }, { selection.get() == "first" }) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            selection.set("second")
            release.countDown()
            assertEquals(null, result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun waitingConsumerRetriesWhenVerifierOwnerIsCancelled() {
        val cache = LocalModelDownload.PackVerifyCache()
        val entered = java.util.concurrent.CountDownLatch(1)
        val waiting = java.util.concurrent.CountDownLatch(1)
        val waiterThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val owner = Thread {
            try {
                cache.getOrPut("model", "fp") {
                    entered.countDown()
                    java.util.concurrent.CountDownLatch(1).await()
                    false
                }
            } catch (_: Exception) { }
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            owner.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val survivor = pool.submit<Boolean> {
                waiterThread.set(Thread.currentThread())
                waiting.countDown()
                cache.getOrPut("model", "fp") { true }
            }
            assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (waiterThread.get().state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, waiterThread.get().state)
            owner.interrupt()
            assertTrue(survivor.get(5, java.util.concurrent.TimeUnit.SECONDS))
            owner.join(5000)
            assertFalse(owner.isAlive)
            assertTrue(cache.has("model", "fp"))
        } finally {
            owner.interrupt()
            pool.shutdownNow()
        }
    }

    @Test
    fun cancelledVerifierDoesNotPoisonCache() {
        val cache = LocalModelDownload.PackVerifyCache()
        val entered = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            try {
                cache.getOrPut("model", "fp") {
                    entered.countDown()
                    java.util.concurrent.CountDownLatch(1).await()
                    true
                }
            } catch (_: Exception) { }
        }
        thread.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertFalse(cache.has("model", "fp"))
        assertTrue(cache.getOrPut("model", "fp") { true })
    }

    @Test
    fun fingerprintChangesWhenFileReplacedWithSameSizeAndTimestamp() {
        val dir = Files.createTempDirectory("replacement").toFile()
        try {
            val file = File(dir, "model").apply { writeText("one") }
            val timestamp = file.lastModified()
            val before = LocalModelDownload.packFingerprint(listOf(file))
            val replacement = File(dir, "replacement").apply { writeText("two"); setLastModified(timestamp) }
            Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertTrue(before != LocalModelDownload.packFingerprint(listOf(file)))
            assertTrue(file.delete())
            assertTrue(before != LocalModelDownload.packFingerprint(listOf(file)))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun packVerifyCache_hitMissAndStale() {
        val cache = LocalModelDownload.PackVerifyCache()
        var calls = 0
        // Miss computes and stores.
        assertEquals("v1", cache.getOrPut("a", "fp1") { calls++; "v1" })
        assertEquals(1, calls)
        // Same fingerprint hits without recompute.
        assertEquals("v1", cache.getOrPut("a", "fp1") { calls++; "v2" })
        assertEquals(1, calls)
        assertTrue(cache.has("a", "fp1"))
        // Changed fingerprint recomputes (file downloaded/modified/deleted).
        assertEquals("v2", cache.getOrPut("a", "fp2") { calls++; "v2" })
        assertEquals(2, calls)
        assertTrue(cache.has("a", "fp2"))
        assertFalse(cache.has("a", "fp1"))
        assertFalse(cache.has("missing", "fp1"))
        assertFalse(cache.has("a", ""))
    }

    @Test
    fun packFingerprint_stableSortedAndSensitive() {
        val dir = Files.createTempDirectory("fp").toFile()
        try {
            val f1 = File(dir, "b.task").apply { writeBytes(ByteArray(10)) }
            val f2 = File(dir, "a.task").apply { writeBytes(ByteArray(5)) }
            val base = LocalModelDownload.packFingerprint(listOf(f1, f2))
            // Order-independent (sorted by name).
            assertEquals(base, LocalModelDownload.packFingerprint(listOf(f2, f1)))
            // Absent files pin as missing (stable across calls).
            val ghost = File(dir, "ghost.task")
            assertEquals(
                LocalModelDownload.packFingerprint(listOf(f1, ghost)),
                LocalModelDownload.packFingerprint(listOf(f1, ghost)),
            )
            // Content growth changes the fingerprint (re-verify).
            f1.appendBytes(ByteArray(3))
            assertTrue(LocalModelDownload.packFingerprint(listOf(f1, f2)) != base)
            // Deletion changes the fingerprint.
            assertTrue(f2.delete())
            assertTrue(LocalModelDownload.packFingerprint(listOf(f1, f2)) != base)
        } finally {
            dir.deleteRecursively()
        }
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
