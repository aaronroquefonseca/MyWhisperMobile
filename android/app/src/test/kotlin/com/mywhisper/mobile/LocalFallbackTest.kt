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
        val finishSecs = AtomicInteger(-1)
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
            finishLocal = { raw, text, engine, secs ->
                finishSecs.set(secs)
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

    /** Fake decoder: one word per distinct sample level, in order ("before" = 1000, "after" = 3000). */
    private fun wordsFor(pcm: ShortArray): String =
        pcm.distinct().joinToString(" ") { if (it == 3000.toShort()) "after" else "before" }

    /** Records 60s at level 1000 then 1s at 3000 (speech past the 60s boundary). */
    private fun recordLong(h: Harness) {
        h.dictation.pressDown()
        val feed = h.pcmSinks.single()
        repeat(60) { feed(ShortArray(16_000) { 1000 }, 16_000) }
        feed(ShortArray(16_000) { 3000 }, 16_000)
        h.dictation.pressUp() // Push -> WORKING
    }

    @Test
    fun localOnly_over60s_decodesEveryWindowIntoOneResult() {
        // Local-only: no server configured, so any server call fails.
        val h = Harness(
            local = { pcm -> wordsFor(pcm) to "local parakeet" },
            serverError = "no STT provider",
            coreCleanup = { it.uppercase() },
        )
        try {
            recordLong(h)
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertFalse("long local audio must not reach the server", h.serverHit.get())
            assertTrue("decoded in windows, got ${h.localCalls.get()}", h.localCalls.get() >= 2)
            assertEquals("one cleanup + history finish", 1, h.coreFinishes.size)
            val raw = h.coreFinishes.single().first
            assertTrue("speech after 60s must survive, got: $raw", raw.endsWith("after"))
            assertTrue("window order kept, got: $raw", raw.startsWith("before"))
            assertEquals(61, h.finishSecs.get())
            assertEquals(listOf(raw.uppercase()), h.results)
            assertTrue(h.errors.isEmpty())
        } finally {
            h.executor.shutdownNow()
        }
    }

    /**
     * Local-only recording of [lead] then 7s of speech (level 3000); the
     * fake decodes under 1s of speech as blank (production: null), so a
     * window cut that strands a speech burst aborts the utterance.
     */
    private fun assertLeadThenSpeechDecodesLocally(lead: ShortArray) {
        val h = Harness(
            local = { pcm -> if (pcm.count { it == 3000.toShort() } < 16_000) null else "after" to "local parakeet" },
            serverError = "no STT provider",
        )
        try {
            h.dictation.pressDown()
            val feed = h.pcmSinks.single()
            feed(lead, lead.size)
            repeat(7) { feed(ShortArray(16_000) { 3000 }, 16_000) }
            h.dictation.pressUp()
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertFalse(h.serverHit.get())
            assertEquals(listOf("after"), h.results)
        } finally {
            h.executor.shutdownNow()
        }
    }

    @Test
    fun localOnly_longSilenceThenSpeech_windowCutStaysInThePause() {
        // A cut on the speech onset left a few ms of speech in window one.
        assertLeadThenSpeechDecodesLocally(ShortArray(16_000 * 55))
    }

    @Test
    fun localOnly_noiseThenSpeechBurst_windowCutStaysInThePause() {
        // 55s of background noise under the VAD threshold, a 20ms speech
        // burst, then a 20ms gap quieter than the noise: the quietest
        // frame alone would cut right after the burst.
        val noise = ShortArray(16_000 * 55) { if (it % 2 == 0) 300 else -300 }
        val burst = ShortArray(320) { 3000 }
        val gap = ShortArray(320) { if (it % 2 == 0) 100 else -100 }
        assertLeadThenSpeechDecodesLocally(noise + burst + gap)
    }

    @Test
    fun localOnly_oddLengthPause_windowCutStaysOnAFrameEdge() {
        // A 1251-frame pause put the midpoint inside a 20ms frame, so a
        // 10ms rise in the noise became a lone "speech" frame tail.
        val noise = ShortArray(16_000 * 55 + 320) { i ->
            val amp = if (i in 680_000 until 680_160) 800 else 300
            (if (i % 2 == 0) amp else -amp).toShort()
        }
        assertLeadThenSpeechDecodesLocally(noise)
    }

    @Test
    fun over60s_windowMiss_neverCommitsPartialText() {
        // The window holding the post-60s speech fails: committing the
        // first window alone would silently drop the tail.
        val h = Harness(local = { pcm -> if (3000.toShort() in pcm) null else wordsFor(pcm) to "local parakeet" })
        try {
            recordLong(h)
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(h.coreFinishes.isEmpty())
            assertTrue(h.serverHit.get())
            assertEquals(listOf("server text"), h.results)
        } finally {
            h.executor.shutdownNow()
        }
    }

    @Test
    fun over60s_cancelBetweenWindows_stopsDecoding() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val h = Harness(local = { pcm ->
            entered.countDown()
            proceed.await(10, TimeUnit.SECONDS)
            wordsFor(pcm) to "local parakeet"
        })
        try {
            recordLong(h)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            h.dictation.cancel() // cancel wins during the first window
            proceed.countDown()
            h.executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals("no window decodes after cancel", 1, h.localCalls.get())
            assertTrue(h.results.isEmpty())
            assertTrue(h.coreFinishes.isEmpty())
            assertFalse(h.serverHit.get())
        } finally {
            proceed.countDown()
            h.executor.shutdownNow()
        }
    }

    @Test
    fun localDecodeWindows_coverAllSamplesInOrderWithinCap() {
        val maxLen = 16_000 * 20
        // 50s speech, 45s silence, 10s speech: silent stretch longer than a window.
        val pcm = ShortArray(16_000 * 50) { 1000 } + ShortArray(16_000 * 45) + ShortArray(16_000 * 10) { 1000 }
        val windows = localDecodeWindows(pcm, maxLen)
        assertEquals(0, windows.first().first)
        assertEquals(pcm.lastIndex, windows.last().last)
        for (i in 1 until windows.size) assertEquals(windows[i - 1].last + 1, windows[i].first)
        assertTrue(windows.all { it.last - it.first + 1 <= maxLen })
        val short = ShortArray(maxLen)
        assertEquals(listOf(short.indices), localDecodeWindows(short, maxLen))
    }

    @Test
    fun shutdown_releasesLocalRecognizer() {
        val h = Harness()
        h.dictation.shutdown()
        assertEquals(1, h.releases.get())
        h.executor.shutdownNow()
    }
}
