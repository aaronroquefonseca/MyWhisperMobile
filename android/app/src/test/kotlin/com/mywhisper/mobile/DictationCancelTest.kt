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
}
