package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-first hook tests for [Dictation]: a local hit commits without
 * touching the server chain; a miss (null) or throw falls back to the
 * server; fallback stays intact in all cases.
 *
 * Pure JVM: transcription hooks are injected fakes, LocalStt native is
 * never loaded.
 */
class LocalFallbackTest {

    private class Harness(
        local: ((ShortArray) -> Pair<String, String>?)? = null,
        serverText: String = "server text",
        serverError: String? = null,
        localFirst: Boolean = true,
        coreCleanup: (String) -> String = { it },
    ) {
        /** (raw, text, engine) per core finish of a local hit. */
        val coreFinishes: MutableList<Triple<String, String, String>> =
            Collections.synchronizedList(mutableListOf())
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val executor = Executors.newSingleThreadExecutor()
        val serverHit = AtomicBoolean(false)
        val localCalls = AtomicInteger(0)
        val releases = AtomicInteger(0)
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val pcmSinks: MutableList<(ShortArray, Int) -> Unit> =
            Collections.synchronizedList(mutableListOf())
        val dictation = Dictation(
            mode = { "Push" },
            startCapture = { onPcm, _, _ ->
                pcmSinks.add(onPcm)
                object : Dictation.Capture {
                    override fun stop() = Unit
                }
            },
            finishTranscription = {
                serverHit.set(true)
                if (serverError != null) throw IllegalStateException(serverError)
                serverText to "test-engine"
            },
            cancelTranscription = {},
            localFirst = { localFirst },
            finishLocal = { raw, text, engine, _ ->
                coreFinishes.add(Triple(raw, text, engine))
                coreCleanup(text) to engine
            },
            localTranscribe = { pcm ->
                localCalls.incrementAndGet()
                (local ?: { null })(pcm)
            },
            pushPcm = { _, _ -> },
            releaseLocal = { releases.incrementAndGet() },
            log = { logs.add(it) },
            workers = executor,
        ).also {
            it.listener = object : Dictation.Listener {
                override fun onState(s: Dictation.State, toggled: Boolean) = Unit
                override fun onResult(text: String) {
                    results.add(text)
                }
                override fun onError(msg: String) {
                    errors.add(msg)
                }
            }
        }

        fun finish() {
            dictation.pressDown()
            dictation.pressUp()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun localHit_skipsServer() {
        val h = Harness(local = { "local text" to "local moonshine-v2-tiny.en" })
        h.finish()
        assertEquals(listOf("local text"), h.results)
        assertFalse("server must not run on local hit", h.serverHit.get())
    }

    @Test
    fun localHit_finishesThroughCoreCleanup() {
        // The old shortcut skipped the core: no LLM cleanup, no history.
        val h = Harness(
            local = { "local text" to "local moonshine-v2-tiny.en" },
            coreCleanup = { "Local text." },
        )
        h.finish()
        assertEquals(listOf("Local text."), h.results)
        assertEquals(
            listOf(Triple("local text", "local text", "local moonshine-v2-tiny.en")),
            h.coreFinishes,
        )
    }

    @Test
    fun localHit_coreFailureStillDeliversLocalText() {
        val h = Harness(
            local = { "local text" to "local moonshine-v2-tiny.en" },
            coreCleanup = { throw IllegalStateException("engine not open") },
        )
        h.finish()
        assertEquals(listOf("local text"), h.results)
    }

    @Test
    fun localBelowServer_serverRunsFirst() {
        // Chain order: a Local entry under a working server must not
        // preempt it (it used to win whenever its model was installed).
        val h = Harness(local = { "local text" to "local" }, localFirst = false)
        h.finish()
        assertEquals(listOf("server text"), h.results)
        assertEquals("local must not decode before the server", 0, h.localCalls.get())
        assertTrue(h.coreFinishes.isEmpty())
    }

    @Test
    fun localBelowServer_serverFailureFallsBackToLocal() {
        val h = Harness(
            local = { "local text" to "local" },
            localFirst = false,
            serverError = "llm post: connection refused",
        )
        h.dictation.pressDown()
        h.pcmSinks.single()(ShortArray(16_000) { 1000 }, 16_000)
        h.dictation.pressUp()
        h.executor.submit {}.get(10, TimeUnit.SECONDS)
        h.executor.shutdownNow()
        assertTrue(h.serverHit.get())
        assertEquals(listOf("local text"), h.results)
        assertEquals(1, h.coreFinishes.size)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun localBelowServer_bothFailSurfacesServerError() {
        val h = Harness(local = { null }, localFirst = false, serverError = "server down")
        h.dictation.pressDown()
        h.pcmSinks.single()(ShortArray(16_000) { 1000 }, 16_000)
        h.dictation.pressUp()
        h.executor.submit {}.get(10, TimeUnit.SECONDS)
        h.executor.shutdownNow()
        assertTrue(h.results.isEmpty())
        assertEquals(listOf("server down"), h.errors)
    }

    @Test
    fun localMiss_fallsBackToServer() {
        val h = Harness(local = { null })
        h.finish()
        assertEquals(listOf("server text"), h.results)
        assertTrue(h.serverHit.get())
    }

    @Test
    fun localThrow_fallsBackToServer() {
        val h = Harness(local = { throw RuntimeException("nope") })
        h.finish()
        assertEquals(listOf("server text"), h.results)
        assertTrue(h.serverHit.get())
    }

    @Test
    fun localOom_fallsBackToServer() {
        // OutOfMemoryError is an Error, not an Exception: the local
        // hook must still miss to server instead of killing the worker.
        val h = Harness(local = { throw OutOfMemoryError("fake OOM") })
        h.finish()
        assertEquals(listOf("server text"), h.results)
        assertTrue(h.serverHit.get())
    }

    @Test
    fun cancelDuringLocalDecode_skipsServerUpload() {
        // Cancel lands while blocked in the local decode; the miss
        // must settle WITHOUT the server upload (the post-call drop
        // alone still pays a dead upload).
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val h = Harness(local = {
            entered.countDown()
            assertTrue("local release timeout", proceed.await(10, TimeUnit.SECONDS))
            null
        })
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // Push -> WORKING, worker blocks in local
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            h.dictation.cancel() // cancel wins mid-decode
            proceed.countDown() // local misses after cancel
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(h.results.isEmpty())
            assertFalse("cancelled decode must not upload to server", h.serverHit.get())
        } finally {
            proceed.countDown()
            h.executor.shutdownNow()
        }
    }

    @Test
    fun pcmOverflow_skipsLocalAndFallsBackToServer() {
        // 61s of audio overflows the 60s local copy: the incomplete
        // local PCM must never decode silently — local is skipped, the
        // full utterance falls back to server, and the cap surfaces.
        val h = Harness(local = { "local text" to "local moonshine-v2-tiny.en" })
        try {
            h.dictation.pressDown()
            val feed = h.pcmSinks.single()
            repeat(61) { feed(ShortArray(16_000) { 1000 }, 16_000) }
            h.dictation.pressUp() // Push -> WORKING
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals("overflowed PCM must skip local decode", 0, h.localCalls.get())
            assertTrue(h.serverHit.get())
            assertEquals(listOf("server text"), h.results)
            assertTrue("cap must surface, got: ${h.logs}", h.logs.any { it.contains("60s") })
        } finally {
            h.executor.shutdownNow()
        }
    }

    @Test
    fun shutdown_releasesLocalRecognizer() {
        val h = Harness()
        h.dictation.shutdown()
        assertEquals(1, h.releases.get())
        h.executor.shutdownNow()
    }
}
