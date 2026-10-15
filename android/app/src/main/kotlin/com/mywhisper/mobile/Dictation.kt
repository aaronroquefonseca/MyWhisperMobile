package com.mywhisper.mobile

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mic-button controller: hold-to-talk / toggle / smart, mirroring the
 * desktop activation modes. Mode comes from settings
 * (`preferences.mic_mode`): Push = hold talks, Tap = press toggles,
 * Smart (default) = release before `smartTapMs` toggles, release
 * after stops; a second press while toggled-on finishes.
 *
 * All state transitions post to [listener] — the IME renders, the core
 * does the work. [finish] transcribes on a worker thread.
 */
class Dictation(
    private val smartTapMs: () -> Long = { 150L },
    private val mode: () -> String = { "Smart" },
    private val releaseDelayMs: () -> Long = { 0L },
    private val startCapture: (
        onPcm: (ShortArray, Int) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ) -> Capture? = { onPcm, onError, onLevel ->
        val cap = AudioCapture(onPcm = onPcm, onError = onError, onLevel = onLevel)
        if (cap.start()) {
            object : Capture {
                override fun stop() = cap.stop()
            }
        } else {
            null
        }
    },
    private val finishTranscription: () -> Pair<String, String> = { CoreBridge.finishUtterance() },
    private val cancelTranscription: () -> Unit = { CoreBridge.cancelUtterance() },
    /**
     * Local-first hook (sherpa-onnx moonshine-v2 via LocalStt): receives
     * the utterance PCM (16 kHz i16) and returns (text, engine) on hit,
     * null on miss. Default null = server chain only. Any throw =
     * server fallback.
     */
    private val localTranscribe: ((ShortArray) -> Pair<String, String>?) = { null },
    private val log: (String) -> Unit = { msg -> android.util.Log.i("MwDict", msg) },
    private val workers: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    enum class State { IDLE, RECORDING, WORKING }

    /** Stoppable mic handle (production: wraps [AudioCapture]; tests: fake). */
    interface Capture {
        fun stop()
    }

    interface Listener {
        fun onState(s: State, toggled: Boolean)
        fun onResult(text: String)
        fun onError(msg: String)
    }

    var listener: Listener? = null
    /** Live mic peaks for the pill visualizer (capture thread). */
    var onLevel: ((Float) -> Unit)? = null
    private var maxPeak = 0f
    /**
     * Utterance PCM copy for the local-first hook (cap ~60s @16kHz =
     * ~2MB). Cleared on every pressDown.
     */
    private val localPcm = ArrayList<Short>(16_000 * 10)

    private var state = State.IDLE
    private var toggled = false
    private var secondDown = false
    private var downAt = 0L
    @Volatile private var capture: Capture? = null
    /**
     * Session token: bumped on every new recording ([pressDown]),
     * every [cancel], and [shutdown]. The [finish] worker snapshots it
     * and rechecks after the tail sleep AND after the blocking
     * [finishTranscription] return — a stale token means cancel won, so
     * drop without committing. Nullness of [capture] alone is racy
     * (TOCTOU: snapshot-then-cancel still transcribes).
     *
     * Cancelled/reset recordings never reach a fallback upload by
     * construction: the stale worker returns before [finishTranscription]
     * is consulted for commit, and the core chain rechecks its own
     * witness before every fallback entry, so a post-cancel result can
     * neither commit here nor upload there.
     *
     * Bounded admission: [workers] is a single thread, and at most one
     * finish is ever queued — [pressDown] drops while WORKING,
     * [pressUp]/[finish] only leave RECORDING, and the tail caps at 2 s.
     */
    private val session = AtomicInteger(0)

    val current: State get() = state

    private fun set(s: State) {
        state = s
        listener?.onState(s, toggled)
    }

    fun pressDown() {
        when (state) {
            State.WORKING -> return
            State.RECORDING -> {
                if (toggled) {
                    // Second press while toggled-on: arm the stop,
                    // decided on release (quick tap finishes, slide
                    // cancels, hold+release finishes). Never finish
                    // on DOWN so slide-to-cancel always gets a chance.
                    secondDown = true
                    downAt = System.currentTimeMillis()
                }
                return
            }
            State.IDLE -> Unit
        }
        val cap = startCapture(
            { buf, n ->
                // Copy: the buffer is reused by the read loop.
                val copy = buf.copyOf(n)
                synchronized(localPcm) {
                    if (localPcm.size < 16_000 * 60) {
                        for (i in 0 until n) localPcm.add(copy[i])
                    }
                }
                try {
                    CoreBridge.pushPcm(copy, n)
                } catch (_: Exception) {
                }
            },
            { listener?.onError(it); hardStop() },
            {
                if (it > maxPeak) maxPeak = it
                onLevel?.invoke(it)
            },
        ) ?: return
        // bump before publish so workers that see the capture see the token.
        session.incrementAndGet()
        capture = cap
        toggled = false
        downAt = System.currentTimeMillis()
        maxPeak = 0f
        synchronized(localPcm) { localPcm.clear() }
        set(State.RECORDING)
    }

    fun pressUp() {
        if (state != State.RECORDING) return
        if (secondDown) {
            // Stop press released (slide-cancel preempts via cancel()
            // flipping to IDLE above).
            secondDown = false
            finish()
            return
        }
        if (toggled) return
        val m = try {
            mode()
        } catch (_: Exception) {
            "Smart"
        }
        if (m == "Push") {
            finish()
        } else if (m == "Tap") {
            // Toggle: keep recording; the next press finishes.
            toggled = true
            listener?.onState(state, true)
        } else {
            // Smart: short press toggles, hold talks.
            val held = System.currentTimeMillis() - downAt
            if (held < smartTapMs()) {
                toggled = true
                listener?.onState(state, true)
            } else {
                finish()
            }
        }
    }

    fun cancel() {
        if (state == State.IDLE) return
        // Invalidate the finish worker FIRST so a blocked
        // finishTranscription that returns later drops, never commits.
        session.incrementAndGet()
        hardStop()
        try {
            cancelTranscription()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        // Invalidate the finish worker FIRST, same as cancel(): a
        // transcription blocked at destroy time must drop, never commit
        // to a dead listener. State is left alone (terminal; no UI).
        session.incrementAndGet()
        try {
            capture?.stop()
        } catch (_: Exception) {
        }
        capture = null
        workers.shutdownNow()
    }

    private fun hardStop() {
        try {
            capture?.stop()
        } catch (_: Exception) {
        }
        capture = null
        toggled = false
        secondDown = false
        set(State.IDLE)
    }

    private fun finish() {
        if (state != State.RECORDING) return
        // Extra listening delay (desktop release tail). Runs on the
        // worker so release stays responsive; the mic keeps capturing
        // meanwhile and a slide-cancel during the tail still wins.
        val tail = try {
            releaseDelayMs().coerceIn(0L, 2000L)
        } catch (_: Exception) {
            0L
        }
        toggled = false
        secondDown = false
        set(State.WORKING)
        val gen = session.get()
        workers.execute {
            if (tail > 0) {
                try {
                    Thread.sleep(tail)
                } catch (_: Exception) {
                }
            }
            // Cancelled while tailing: cancel() bumps session, so the
            // token rules — capture nullness alone races (worker may
            // have snapshotted non-null just before cancel). Return
            // silently: state already belongs to cancel()/a new press.
            if (gen != session.get()) return@execute
            val cap = capture
            if (cap == null || gen != session.get()) return@execute
            try {
                cap.stop()
            } catch (_: Exception) {
            }
            capture = null
            val text: String
            try {
                // Local-first: sherpa-onnx moonshine-v2 on the utterance
                // PCM; miss or throw → server chain (fallback intact).
                // Only the LOCAL hook is guarded here: a server throw
                // must reach the outer catch (session-gated drop) so
                // cancel/shutdown during a blocked transcribe still
                // wins instead of retrying into a dead worker.
                val pcm: ShortArray = synchronized(localPcm) { localPcm.toShortArray() }
                val local: String? = try {
                    localTranscribe(pcm)?.first
                } catch (_: Throwable) {
                    // Local hook miss on ANY throw (incl. OOM Error) →
                    // server fallback. Only the local hook is guarded:
                    // a server throw must reach the outer catch below.
                    null
                }
                text = local ?: finishTranscription().first
            } catch (e: Exception) {
                if (gen == session.get()) {
                    listener?.onError(e.message ?: "transcription failed")
                    set(State.IDLE)
                }
                return@execute
            }
            // Cancelled while blocked in transcription: the call above
            // returned after cancel() — drop, never commit to the IME.
            if (gen != session.get()) return@execute
            log("utterance max peak=%.2f".format(maxPeak))
            log("utterance text len=${text.length} hasListener=${listener != null}")
            if (text.isBlank()) {
                listener?.onError("empty transcript")
            } else {
                listener?.onResult(text)
            }
            set(State.IDLE)
        }
    }
}
