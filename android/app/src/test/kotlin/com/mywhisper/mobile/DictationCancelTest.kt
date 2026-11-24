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
 * Cancel-wins tests for [Dictation]: a cancel issued while the finish
 * worker is tail-sleeping OR blocked in transcription must never reach
 * [Dictation.Listener.onResult], and a stale worker must not clobber a
 * newer recording's state.
 *
 * Pure JVM (no Robolectric): capture/transcription/Log are injected
 * fakes, so no Android framework or native core is touched.
 */
class DictationCancelTest {

    private class FakeCapture : Dictation.Capture {
        var stops = 0
        override fun stop() {
            stops++
        }
    }

    private class Harness(
        tailMs: Long = 0L,
        val transcribeBlock: CountDownLatch? = null,
        transcribeText: String = "hello world",
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val enteredTranscribe = CountDownLatch(1)
        val transcribed = AtomicBoolean(false)
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<Dictation.State> = Collections.synchronizedList(mutableListOf())
        var text = transcribeText
        val dictation = Dictation(
            mode = { "Push" },
            releaseDelayMs = { tailMs },
            startCapture = { _, _, _ -> FakeCapture() },
            finishTranscription = {
                transcribed.set(true)
                enteredTranscribe.countDown()
                transcribeBlock?.let {
                    assertTrue("transcribe release timeout", it.await(10, TimeUnit.SECONDS))
                }
                text to "test-engine"
            },
            cancelTranscription = {},
            log = {},
            workers = executor,
        ).also {
            it.listener = object : Dictation.Listener {
                override fun onState(s: Dictation.State, toggled: Boolean) {
                    states.add(s)
                }
                override fun onResult(text: String) {
                    results.add(text)
                }
                override fun onError(msg: String) {
                    errors.add(msg)
                }
            }
        }

        /** Barrier: single-thread FIFO => runs after the queued worker. */
        fun settleWorker() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelDuringBlockedTranscribe_dropsResult() {
        val h = Harness(transcribeBlock = CountDownLatch(1))
        try {
            h.dictation.pressDown()
            assertEquals(Dictation.State.RECORDING, h.dictation.current)
            h.dictation.pressUp() // Push -> finish -> WORKING, worker blocks
            assertEquals(Dictation.State.WORKING, h.dictation.current)
            assertTrue(h.enteredTranscribe.await(10, TimeUnit.SECONDS))

            h.dictation.cancel() // cancel while blocked in finishUtterance
            assertEquals(Dictation.State.IDLE, h.dictation.current)
            h.transcribeBlock!!.countDown() // let the stale call return text
            h.settleWorker()

            assertTrue("stale transcribe must not commit", h.results.isEmpty())
            assertTrue("stale transcribe must not error either", h.errors.isEmpty())
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun cancelDuringTail_neverTranscribes() {
        val h = Harness(tailMs = 500L)
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // WORKING, worker tail-sleeping
            assertEquals(Dictation.State.WORKING, h.dictation.current)

            h.dictation.cancel() // cancel wins the tail
            h.settleWorker()

            assertFalse("cancelled tail must not transcribe", h.transcribed.get())
            assertTrue(h.results.isEmpty())
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun staleWorker_doesNotClobberNewRecording() {
        val h = Harness(transcribeBlock = CountDownLatch(1))
        try {
            h.dictation.pressDown()
            h.dictation.pressUp()
            assertTrue(h.enteredTranscribe.await(10, TimeUnit.SECONDS))

            h.dictation.cancel()
            h.dictation.pressDown() // new session while old worker blocked
            assertEquals(Dictation.State.RECORDING, h.dictation.current)

            h.transcribeBlock!!.countDown() // stale call returns
            h.settleWorker()

            assertTrue("stale worker must not commit", h.results.isEmpty())
            assertEquals(
                "stale worker must not clobber new RECORDING",
                Dictation.State.RECORDING,
                h.dictation.current,
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun finishWithoutCancel_commitsResult() {
        val h = Harness()
        try {
            h.dictation.pressDown()
            h.dictation.pressUp()
            h.settleWorker()

            assertEquals(listOf("hello world"), h.results)
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun shutdownDuringBlockedTranscribe_dropsResult() {
        val h = Harness(transcribeBlock = CountDownLatch(1))
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // WORKING, worker blocks
            assertEquals(Dictation.State.WORKING, h.dictation.current)
            assertTrue(h.enteredTranscribe.await(10, TimeUnit.SECONDS))

            h.dictation.shutdown() // destroy invalidates the worker first
            assertTrue(h.executor.awaitTermination(10, TimeUnit.SECONDS))

            assertTrue("post-shutdown transcribe must not commit", h.results.isEmpty())
            assertTrue("post-shutdown transcribe must not error either", h.errors.isEmpty())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun newSessionAfterCancel_commitsWhileStaleDrops() {
        val h = Harness(transcribeBlock = CountDownLatch(1))
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // worker1 blocks in transcription
            assertTrue(h.enteredTranscribe.await(10, TimeUnit.SECONDS))

            h.dictation.cancel()
            h.dictation.pressDown() // new session (reset bumps again)
            assertEquals(Dictation.State.RECORDING, h.dictation.current)
            h.dictation.pressUp() // worker2 queues behind worker1
            assertEquals(Dictation.State.WORKING, h.dictation.current)

            h.transcribeBlock!!.countDown() // worker1 returns stale, worker2 runs fresh
            h.settleWorker()

            assertEquals("stale drops, fresh commits exactly once", listOf("hello world"), h.results)
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun blankTranscription_neverCommits_routesToSilence() {
        val h = Harness(transcribeText = "   ")
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // genuine stop with silence
            h.settleWorker()

            assertTrue("blank must never commit", h.results.isEmpty())
            assertEquals("silence routes to the empty state", listOf("silence"), h.errors)
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun cancelDuringWorking_neverShowsWorkingAfter() {
        val h = Harness(transcribeBlock = CountDownLatch(1))
        try {
            h.dictation.pressDown()
            h.dictation.pressUp() // WORKING, worker blocks in transcription
            assertTrue(h.enteredTranscribe.await(10, TimeUnit.SECONDS))

            h.states.clear()
            h.dictation.cancel() // must land IDLE and stay there
            h.transcribeBlock!!.countDown() // stale call returns text
            h.settleWorker()

            assertTrue("cancelled session must not commit", h.results.isEmpty())
            assertTrue("cancelled session must not report either", h.errors.isEmpty())
            assertFalse(
                "WORKING must never reappear after cancel (saw ${h.states})",
                h.states.contains(Dictation.State.WORKING),
            )
            assertEquals(Dictation.State.IDLE, h.dictation.current)
        } finally {
            h.shutdown()
        }
    }

    /**
     * Delayed provider, production executor: the first finish blocks in
     * the provider. Cancel must return at once, the stale finish must
     * neither paste nor save, and the next dictation must complete
     * while the stale request is still in flight.
     *
     * The fake core models the native witness: [cancelTranscription]
     * bumps a generation and a finish saves History only if the
     * generation it started under is still current.
     */
    @Test
    fun cancelDuringDelayedProvider_isPrompt_savesNothing_nextDictationWorks() {
        val generation = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val staleDropped = CountDownLatch(1)
        val history: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val pastes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val nextPasted = CountDownLatch(1)
        val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dictation = Dictation(
            mode = { "Push" },
            startCapture = { _, _, _ -> FakeCapture() },
            finishTranscription = {
                val witness = generation.get()
                val text = if (calls.incrementAndGet() == 1) "stale" else "next"
                if (text == "stale") {
                    providerEntered.countDown()
                    assertTrue("provider release timeout", providerRelease.await(10, TimeUnit.SECONDS))
                }
                if (generation.get() != witness) throw IllegalStateException("superseded")
                history.add(text)
                text to "slow-provider"
            },
            cancelTranscription = {
                order.add("core-cancel")
                generation.incrementAndGet()
            },
            log = { if (it.startsWith("finish drop stale-error")) staleDropped.countDown() },
        )
        dictation.listener = object : Dictation.Listener {
            override fun onState(s: Dictation.State, toggled: Boolean) {
                order.add("state-$s")
            }
            override fun onResult(text: String) {
                pastes.add(text)
                nextPasted.countDown()
            }
            override fun onError(msg: String) {
                errors.add(msg)
            }
        }
        try {
            dictation.pressDown()
            dictation.pressUp()
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS))
            assertEquals(Dictation.State.WORKING, dictation.current)

            order.clear()
            val began = System.nanoTime()
            dictation.cancel()
            val cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("cancel took ${cancelMs}ms", cancelMs < 200)
            assertEquals(Dictation.State.IDLE, dictation.current)
            assertEquals("core revoked before the UI shows IDLE", listOf("core-cancel", "state-IDLE"), order)

            // Next dictation while the cancelled request is still blocked.
            dictation.pressDown()
            dictation.pressUp()
            assertTrue("next dictation queued behind the stale one", nextPasted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("next"), pastes)

            providerRelease.countDown()
            assertTrue("stale finish never settled", staleDropped.await(10, TimeUnit.SECONDS))

            assertEquals("cancelled utterance must not paste", listOf("next"), pastes)
            assertEquals("cancelled utterance must not reach History", listOf("next"), history)
            assertTrue("stale finish must stay silent (saw $errors)", errors.isEmpty())
            assertEquals(Dictation.State.IDLE, dictation.current)
        } finally {
            providerRelease.countDown()
            dictation.shutdown()
        }
    }

    /**
     * A cancel racing the finish worker's mic stop must wait for that
     * stop, so the old capture is fully torn down before a new
     * recording can start.
     */
    @Test
    fun cancelDuringWorkerStop_waitsForTheOldMic() {
        val stopEntered = CountDownLatch(1)
        val stopRelease = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val stops = AtomicInteger(0)
        val slowCapture = object : Dictation.Capture {
            override fun stop() {
                stops.incrementAndGet()
                stopEntered.countDown()
                assertTrue(stopRelease.await(10, TimeUnit.SECONDS))
                stopped.set(true)
            }
        }
        val captures = mutableListOf<Dictation.Capture>(slowCapture, FakeCapture())
        val dictation = Dictation(
            mode = { "Push" },
            startCapture = { _, _, _ -> captures.removeAt(0) },
            finishTranscription = { "text" to "engine" },
            cancelTranscription = {},
            log = {},
        )
        try {
            dictation.pressDown()
            dictation.pressUp() // worker starts stopping the mic
            assertTrue(stopEntered.await(10, TimeUnit.SECONDS))

            val cancelled = CountDownLatch(1)
            Thread { dictation.cancel(); cancelled.countDown() }.start()
            assertFalse("cancel returned while the old mic was still stopping", cancelled.await(200, TimeUnit.MILLISECONDS))

            stopRelease.countDown()
            assertTrue(cancelled.await(10, TimeUnit.SECONDS))
            assertTrue("old mic fully stopped before cancel returned", stopped.get())
            assertEquals("old mic torn down exactly once", 1, stops.get())
            dictation.pressDown()
            assertEquals(Dictation.State.RECORDING, dictation.current)
        } finally {
            stopRelease.countDown()
            dictation.shutdown()
        }
    }

    /**
     * Cancel paused after the core revoke (e.g. writing its discarded
     * row): a server finish that fails because of that revoke must not
     * start the Local fallback for the cancelled utterance.
     */
    @Test
    fun cancelPausedAfterCoreRevoke_neverStartsLocalFallback() {
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val bookkeepingRelease = CountDownLatch(1)
        val localStarted = CountDownLatch(1)
        val dictation = Dictation(
            mode = { "Push" },
            startCapture = { onPcm, _, _ ->
                onPcm(ShortArray(8_000), 8_000) // enough audio for the fallback path
                FakeCapture()
            },
            pushPcm = { _, _, _ -> },
            localFirst = { false },
            localTranscribe = {
                localStarted.countDown()
                "stale local" to "local"
            },
            localPostProcess = { it },
            finishLocal = { _, text, engine, _, _ -> text to engine },
            finishTranscription = {
                providerEntered.countDown()
                assertTrue(providerRelease.await(10, TimeUnit.SECONDS))
                throw IllegalStateException("cancelled (chain superseded)")
            },
            cancelTranscription = {
                revoked.countDown()
                assertTrue(bookkeepingRelease.await(10, TimeUnit.SECONDS))
            },
            log = {},
        )
        val pastes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        dictation.listener = object : Dictation.Listener {
            override fun onState(s: Dictation.State, toggled: Boolean) {}
            override fun onResult(text: String) {
                pastes.add(text)
            }
            override fun onError(msg: String) {}
        }
        try {
            dictation.pressDown()
            dictation.pressUp()
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS))

            val cancelled = CountDownLatch(1)
            Thread { dictation.cancel(); cancelled.countDown() }.start()
            assertTrue(revoked.await(10, TimeUnit.SECONDS))
            providerRelease.countDown() // provider fails because of the revoke
            assertFalse("fallback started for a cancelled utterance", localStarted.await(300, TimeUnit.MILLISECONDS))

            bookkeepingRelease.countDown()
            assertTrue(cancelled.await(10, TimeUnit.SECONDS))
            assertFalse("fallback started after cancel", localStarted.await(300, TimeUnit.MILLISECONDS))
            assertTrue(pastes.isEmpty())
        } finally {
            providerRelease.countDown()
            bookkeepingRelease.countDown()
            dictation.shutdown()
        }
    }
}
