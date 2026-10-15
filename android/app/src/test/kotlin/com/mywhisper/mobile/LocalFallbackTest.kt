package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val serverHit = AtomicBoolean(false)
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dictation = Dictation(
            mode = { "Push" },
            startCapture = { _, _, _ ->
                object : Dictation.Capture {
                    override fun stop() = Unit
                }
            },
            finishTranscription = {
                serverHit.set(true)
                serverText to "test-engine"
            },
            cancelTranscription = {},
            localTranscribe = local ?: { null },
            log = {},
            workers = executor,
        ).also {
            it.listener = object : Dictation.Listener {
                override fun onState(s: Dictation.State, toggled: Boolean) = Unit
                override fun onResult(text: String) {
                    results.add(text)
                }
                override fun onError(msg: String) = Unit
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
}
