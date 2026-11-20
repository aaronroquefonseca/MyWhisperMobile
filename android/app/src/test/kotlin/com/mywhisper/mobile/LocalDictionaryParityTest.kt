package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dictionary parity for the on-device path: [Dictation] applies only
 * the word boost ([LocalStt.postProcessLocal]) to local hits and hands
 * the unexpanded text to the core, which recognizes and protects
 * snippets exactly as on the server path (`finalize_transcript`).
 *
 * Pure JVM: no Android calls, no native code (pairs are injected, the
 * CoreBridge loader is never touched).
 */
class LocalDictionaryParityTest {

    @Test
    fun localHit_leavesSnippetsForTheCore() {
        // The core expands snippets (protected through cleanup); the
        // platform must not expand them first, or cleanup could rewrite
        // the replacement.
        val toCore: MutableList<String> = Collections.synchronizedList(mutableListOf())
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
                    "server" to "test-engine"
                },
                cancelTranscription = {},
                finishLocal = { _, text, engine, _ ->
                    toCore.add(text)
                    text to engine
                },
                localTranscribe = { "the investor ask now" to "local moonshine-v2-tiny.en" },
                localPostProcess = { LocalStt.postProcessLocal(it, emptyList()) },
                pushPcm = { _, _ -> },
                log = {},
                workers = localExecutor,
            )
            local.pressDown()
            local.pressUp()
            localExecutor.submit {}.get(10, TimeUnit.SECONDS)
        } finally {
            localExecutor.shutdownNow()
        }

        assertEquals(listOf("the investor ask now"), toCore)
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
                finishLocal = { _, text, engine, _ -> text to engine },
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
