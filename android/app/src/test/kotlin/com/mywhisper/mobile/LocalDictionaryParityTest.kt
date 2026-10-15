package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dictionary parity for the on-device path: raw local decode bypasses
 * Rust `finish_inner`, so [Dictation] applies [LocalStt.postProcessLocal]
 * (same snippet expansion the server path gets) to local hits.
 *
 * Pure JVM: no Android calls, no native code (pairs are injected, the
 * CoreBridge loader is never touched).
 */
class LocalDictionaryParityTest {

    @Test
    fun snippetPairs_splitCommaTriggers() {
        val pairs = LocalStt.snippetPairs(listOf("omw, on my way" to "On my way!"))
        assertEquals(
            listOf("omw" to "On my way!", "on my way" to "On my way!"),
            pairs,
        )
        assertTrue(LocalStt.snippetPairs(listOf(" , " to "x")).isEmpty())
    }

    @Test
    fun expandSnippets_matchesServerVectors() {
        // Vectors mirror core textnorm.rs tests: any Rust change here
        // must land in LocalStt.expandSnippets too.
        val snips = listOf(
            "ask" to "ASKED",
            "investor ask" to "INVESTOR ASKED",
        )
        assertEquals(
            "the INVESTOR ASKED now",
            LocalStt.expandSnippets("the investor ask now", snips),
        )
        assertEquals("basket task", LocalStt.expandSnippets("basket task", snips))
        assertEquals("ASKED me", LocalStt.expandSnippets("ASK me", snips))
        assertEquals("ASKED, me!", LocalStt.expandSnippets("ask, me!", snips))
        assertEquals(
            "SIGN here",
            LocalStt.expandSnippets("İmza here", listOf("imza" to "SIGN")),
        )
        assertEquals("hello world", LocalStt.expandSnippets("hello world", emptyList()))
    }

    @Test
    fun localHit_transformsIdenticallyToServer() {
        // Same dictionary entry, same raw decode: a local hit must
        // commit exactly what the server path commits (Rust already
        // expanded: simulated by returning expanded server text).
        val rows = listOf("investor ask" to "INVESTOR ASKED")
        val postProcess = { s: String -> LocalStt.postProcessLocal(s, rows) }

        val localResults: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val localServerHit = AtomicBoolean(false)
        val localExecutor = Executors.newSingleThreadExecutor()
        try {
            val local = Dictation(
                mode = { "Push" },
                startCapture = { _, _, _ ->
                    object : Dictation.Capture {
                        override fun stop() = Unit
                    }
                },
                finishTranscription = {
                    localServerHit.set(true)
                    "the INVESTOR ASKED now" to "test-engine"
                },
                cancelTranscription = {},
                localTranscribe = { "the investor ask now" to "local moonshine-v2-tiny.en" },
                localPostProcess = postProcess,
                pushPcm = { _, _ -> },
                log = {},
                workers = localExecutor,
            ).also {
                it.listener = object : Dictation.Listener {
                    override fun onState(s: Dictation.State, toggled: Boolean) = Unit
                    override fun onResult(text: String) {
                        localResults.add(text)
                    }
                    override fun onError(msg: String) = Unit
                }
            }
            local.pressDown()
            local.pressUp()
            localExecutor.submit {}.get(10, TimeUnit.SECONDS)
        } finally {
            localExecutor.shutdownNow()
        }

        assertEquals(listOf("the INVESTOR ASKED now"), localResults)
        assertTrue("server must not run on local hit", !localServerHit.get())
    }

    @Test
    fun localPostProcessThrow_fallsBackToServer() {
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dictation = Dictation(
                mode = { "Push" },
                startCapture = { _, _, _ ->
                    object : Dictation.Capture {
                        override fun stop() = Unit
                    }
                },
                finishTranscription = { "server text" to "test-engine" },
                cancelTranscription = {},
                localTranscribe = { "local text" to "local moonshine-v2-tiny.en" },
                localPostProcess = { throw RuntimeException("nope") },
                pushPcm = { _, _ -> },
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
            dictation.pressDown()
            dictation.pressUp()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("server text"), results)
        } finally {
            executor.shutdownNow()
        }
    }
}
