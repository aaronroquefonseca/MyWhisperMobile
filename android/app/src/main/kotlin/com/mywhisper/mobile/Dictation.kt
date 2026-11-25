package com.mywhisper.mobile

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Longest single local decode: 60s @ 16 kHz mono. Longer utterances decode in ordered windows. */
internal const val LOCAL_WINDOW_SAMPLES = 16_000 * 60

/**
 * Splits [pcm] into ordered, contiguous decode windows of at most
 * [maxLen] samples that together cover every sample. Each cut lands in
 * the middle of the longest VAD-silent run (the [LocalStt.chunkPcm]
 * rule) in the window's second half, so a window never ends on a stray
 * burst of speech; with no silent frame there, after the quietest one.
 */
internal fun localDecodeWindows(pcm: ShortArray, maxLen: Int = LOCAL_WINDOW_SAMPLES): List<IntRange> {
    val frame = LocalStt.VAD_FRAME_SAMPLES
    // RMS threshold as a per-frame sum of squares.
    val speechEnergy = (LocalStt.VAD_RMS_THRESHOLD * 32768.0).let { it * it * frame }
    val out = mutableListOf<IntRange>()
    var start = 0
    while (pcm.size - start > maxLen) {
        var quietCut = start + maxLen
        var quietEnergy = Double.MAX_VALUE
        var runStart = -1
        var bestRun = 0
        var runCut = 0
        // Whole VAD frames only, so a window edge never splits a frame chunkPcm scores.
        var f = start + maxLen / 2 / frame * frame
        while (f + frame <= start + maxLen) {
            var energy = 0.0
            for (i in f until f + frame) energy += pcm[i].toDouble() * pcm[i]
            if (energy <= quietEnergy) {
                quietEnergy = energy
                quietCut = f + frame
            }
            if (energy < speechEnergy) {
                if (runStart < 0) runStart = f
                if (f + frame - runStart > bestRun) {
                    bestRun = f + frame - runStart
                    runCut = runStart + bestRun / frame / 2 * frame
                }
            } else {
                runStart = -1
            }
            f += frame
        }
        val cut = if (bestRun > 0) runCut else quietCut
        out.add(start until cut)
        start = cut
    }
    out.add(start until pcm.size)
    return out
}

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
    /** Lets the default capture honor `preferences.mic_priority`. */
    private val context: android.content.Context? = null,
    private val smartTapMs: () -> Long = { 150L },
    private val mode: () -> String = { "Smart" },
    private val releaseDelayMs: () -> Long = { 0L },
    private val startCapture: (
        onPcm: (ShortArray, Int, Long) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ) -> Capture? = { onPcm, onError, onLevel ->
        val cap = AudioCapture(context, onPcm = onPcm, onError = onError, onLevel = onLevel)
        if (cap.start()) {
            object : Capture {
                override val utterance = cap.utterance
                override fun stop() = cap.stop()
            }
        } else {
            null
        }
    },
    /**
     * Blocking server pass for the core utterance token
     * ([Capture.utterance]), arg `afterLocal`: false = the enabled
     * server entries above the Local entry, true = those below it.
     */
    private val finishTranscription: (Long, Boolean) -> Pair<String, String> =
        { utterance, afterLocal -> CoreBridge.finishUtterance(utterance, afterLocal) },
    private val cancelTranscription: () -> Unit = { CoreBridge.cancelUtterance() },
    /**
     * Forwards capture PCM to the core recorder for an utterance token
     * (the core drops a stale one). Injected (default
     * `CoreBridge.pushPcm`) so JVM unit tests can fire the capture
     * path without the native lib.
     */
    private val pushPcm: (ShortArray, Int, Long) -> Unit = { buf, n, utterance -> CoreBridge.pushPcm(buf, n, utterance) },
    /**
     * Local-first hook (selected on-device model via LocalStt): receives
     * the utterance PCM (16 kHz i16) and returns (text, engine) on hit,
     * null on miss. Default null = server chain only. Any throw =
     * server fallback.
     */
    private val localTranscribe: ((ShortArray) -> Pair<String, String>?) = { null },
    /**
     * Local-hit dictionary post-process (default
     * `LocalStt.postProcessLocal`: the WORD word-boost near-miss
     * repair). Snippets are handled by the core in [finishLocal], the
     * same as on the server path. Injected so JVM unit tests run
     * without the native lib. Applied to LOCAL hits only, exactly
     * once. Any throw falls back to the server chain (applied inside
     * the guarded local block in [finish]).
     */
    private val localPostProcess: (String) -> String = { LocalStt.postProcessLocal(it) },
    /**
     * Chain order for the local hook (production
     * `LocalStt.remotesAroundLocal`): (any enabled server above the
     * Local entry, any below it). The default is Local first.
     */
    private val remotesAroundLocal: () -> Pair<Boolean, Boolean> = { false to true },
    /**
     * Finishes a local hit through the core (LLM cleanup chain +
     * history), args (raw, post-processed, engine, durationSecs,
     * utterance token) → (text, engine). Default
     * `CoreBridge.finishLocalUtterance`.
     */
    private val finishLocal: (String, String, String, Int, Long) -> Pair<String, String> =
        { raw, text, engine, secs, utterance -> CoreBridge.finishLocalUtterance(raw, text, engine, secs, utterance) },
    /**
     * Shutdown hook releasing the on-device recognizer
     * (`LocalStt.unload`). Injected (default no-op) so JVM unit tests
     * never touch the native runtime, which is absent off-device.
     * Production wires `LocalStt.unload` at every construction site.
     */
    private val releaseLocal: () -> Unit = {},
    private val log: (String) -> Unit = { msg -> android.util.Log.i("MwDict", msg) },
    /**
     * Finish workers. Not single-threaded: a cancelled finish may stay
     * blocked on its provider until the request times out, and the
     * next dictation must not queue behind it.
     */
    private val workers: ExecutorService = Executors.newCachedThreadPool(),
) {
    enum class State { IDLE, RECORDING, WORKING }

    /** On-device decode: [raw] decoder output, [text] after [localPostProcess]. */
    private data class LocalHit(val raw: String, val text: String, val engine: String)

    /** Stoppable mic handle (production: wraps [AudioCapture]; tests: fake). */
    interface Capture {
        /** Core utterance token from `CoreBridge.startUtterance`; finishes present it. */
        val utterance: Long get() = 0L
        fun stop()
    }

    interface Listener {
        fun onState(s: State, toggled: Boolean)
        fun onResult(text: String)
        fun onError(msg: String)
        /** A finished utterance could not be transcribed (raw core message). */
        fun onTranscriptionError(msg: String) = onError(msg)
    }

    var listener: Listener? = null
    /** Live mic peaks for the pill visualizer (capture thread). */
    var onLevel: ((Float) -> Unit)? = null
    private var maxPeak = 0f
    /**
     * Utterance PCM copy for the local-first hook: primitive buffer,
     * no boxing (was `ArrayList<Short>`: one object per sample plus a
     * second copy on snapshot). Grown by doubling with the utterance
     * (the core recorder is unbounded too); decoded in
     * [LOCAL_WINDOW_SAMPLES] windows. Guarded by [pcmLock]; the buffer
     * itself is retained across utterances to avoid realloc churn.
     *
     * [pcmLock] also orders every [session] transition (start, cancel,
     * shutdown) against the finish worker's capture handoff, PCM
     * snapshot and final delivery, so a stale finish can never take a
     * newer recording's mic or PCM, paste, or set the state.
     */
    private val pcmLock = Any()
    private var pcmBuf = ShortArray(16_000 * 10)
    private var pcmSize = 0
    /**
     * True once the PCM copy could not grow (heap exhausted). The
     * overflow is never silently truncated: the local hook is skipped
     * for the utterance (its PCM would be incomplete) and the full
     * audio goes to the server chain, with a log line. Volatile; set
     * under [pcmLock], read on the finish worker.
     */
    @Volatile private var pcmOverflowed = false

    private var state = State.IDLE
    private var toggled = false
    private var secondDown = false
    private var downAt = 0L
    @Volatile private var capture: Capture? = null
    /**
     * Session token: bumped on every new recording ([pressDown]),
     * every [cancel], and [shutdown]. The [finish] worker snapshots it
     * and rechecks after the tail sleep, after a local miss BEFORE the
     * server upload, AND after the blocking [finishTranscription]
     * return — a stale token means cancel won, so drop without
     * committing and without uploading dead audio. Nullness of
     * [capture] alone is racy (TOCTOU: snapshot-then-cancel still
     * transcribes).
     *
     * Cancelled/reset recordings never reach a fallback upload by
     * construction: the stale worker returns before [finishTranscription]
     * is consulted for commit, and the core chain rechecks its own
     * witness before every fallback entry, so a post-cancel result can
     * neither commit here nor upload there.
     *
     * Bounded admission: at most one live finish at a time — [pressDown]
     * drops while WORKING, [pressUp]/[finish] only leave RECORDING, and
     * the tail caps at 2 s. Cancelled finishes may still be draining.
     */
    private val session = AtomicInteger(0)

    /**
     * One on-device decode at a time (the recognizer is a single native
     * engine anyway). A decode queued behind a cancelled one rechecks
     * its session after the wait, so it never runs for a dead utterance.
     */
    private val localDecodeLock = Any()

    val current: State get() = state

    private fun set(s: State) {
        state = s
        listener?.onState(s, toggled)
    }

    /** Switch the live capture to hands-free mode without finishing or restarting it. */
    /** Remaining ambiguity window, measured from the same press as Smart release. */
    fun holdControlsDelayMs(): Long? {
        if (state != State.RECORDING) return null
        val selectedMode = try { mode() } catch (_: Exception) { "Smart" }
        return holdControlsDelayMs(selectedMode, toggled, System.currentTimeMillis() - downAt, smartTapMs())
    }

    fun lockRecording() {
        if (state != State.RECORDING || toggled) return
        toggled = true
        secondDown = false
        listener?.onState(state, true)
    }

    /** Stops [cap] once; a second caller waits for the first stop to finish. */
    private class StopOnce(private val cap: Capture) : Capture {
        override val utterance get() = cap.utterance
        private var stopped = false

        @Synchronized
        override fun stop() {
            if (stopped) return
            stopped = true
            try {
                cap.stop()
            } catch (_: Exception) {
            }
        }
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
        // New session before the mic opens: PCM from an older capture
        // that outlived its stop is dropped, here and in the core.
        val gen = synchronized(pcmLock) {
            pcmSize = 0
            pcmOverflowed = false
            session.incrementAndGet()
        }
        val cap = startCapture(
            pcm@{ buf, n, utterance ->
                // Copy: the buffer is reused by the read loop.
                val copy = buf.copyOf(n)
                synchronized(pcmLock) {
                    if (gen != session.get()) return@pcm
                    if (!pcmOverflowed) {
                        try {
                            if (pcmSize + n > pcmBuf.size) {
                                pcmBuf = pcmBuf.copyOf(maxOf(pcmBuf.size * 2, pcmSize + n))
                            }
                            System.arraycopy(copy, 0, pcmBuf, pcmSize, n)
                            pcmSize += n
                        } catch (_: OutOfMemoryError) {
                            pcmOverflowed = true
                            log("local PCM copy out of memory, overflow falls back to server")
                        }
                    }
                }
                // Outside pcmLock (the core may wait on its own lock); the
                // token keeps a chunk that lost a race out of a newer recording.
                try {
                    pushPcm(copy, n, utterance)
                } catch (_: Exception) {
                }
            },
            { listener?.onError(it); hardStop() },
            {
                if (it > maxPeak) maxPeak = it
                onLevel?.invoke(it)
            },
        )?.let(::StopOnce) ?: return
        synchronized(pcmLock) { capture = cap }
        toggled = false
        downAt = System.currentTimeMillis()
        maxPeak = 0f
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
        val was = state
        synchronized(pcmLock) {
            // The core first (its revoke is lock-free), then the session,
            // in one step: no window where Kotlin dropped the utterance but
            // a returning provider can still fall back or save to History.
            try {
                cancelTranscription()
            } catch (_: Exception) {
            }
            session.incrementAndGet()
        }
        log("cancel $was->IDLE session=${session.get()}")
        hardStop()
    }

    fun shutdown() {
        // Invalidate the finish worker FIRST, same as cancel(): a
        // transcription blocked at destroy time must drop, never commit
        // to a dead listener. State is left alone (terminal; no UI).
        synchronized(pcmLock) { session.incrementAndGet() }
        takeCapture()?.let(::stopCapture)
        // Release the on-device recognizer: without this the native ORT
        // session stays resident across IME enable/disable cycles in a
        // surviving process. Never throws (hook is guarded).
        try {
            releaseLocal()
        } catch (_: Throwable) {
        }
        workers.shutdownNow()
    }

    private fun takeCapture(): Capture? = synchronized(pcmLock) { capture.also { capture = null } }

    /**
     * Captures are [StopOnce]: a cancel racing the finish worker's stop
     * waits for it, and a late second stop is a no-op, so the old mic
     * (and its foreground service downgrade) never outlives the cancel.
     */
    private fun stopCapture(cap: Capture) = cap.stop()

    private fun hardStop() {
        takeCapture()?.let(::stopCapture)
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
        log("finish ->WORKING gen=$gen")
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
            // Session checks take pcmLock: cancel holds it from the core
            // revoke through the session bump, so a check never sees a
            // revoked utterance as current.
            fun stale() = synchronized(pcmLock) { gen != session.get() }
            if (stale()) {
                log("finish drop stale-tail gen=$gen session=${session.get()}")
                return@execute
            }
            val cap = synchronized(pcmLock) { if (gen == session.get()) capture else null }
            if (cap == null) {
                log("finish drop stale-capture gen=$gen session=${session.get()}")
                return@execute
            }
            // Outside pcmLock: stop() joins the capture thread, which takes it.
            // [capture] stays set until the stop is done, so a cancel waits for it.
            stopCapture(cap)
            val utterance = cap.utterance
            // Final delivery: atomic with cancel/start, so a stale worker never reports or sets state.
            fun deliverIfCurrent(what: String, report: () -> Unit) = synchronized(pcmLock) {
                if (gen != session.get()) {
                    log("finish drop stale-$what gen=$gen session=${session.get()}")
                } else {
                    report()
                    set(State.IDLE)
                }
            }
            val text: String
            try {
                // Settings order: the servers above the Local entry,
                // then the on-device decode (LocalStt) on the utterance
                // PCM, then the servers below it; first success wins.
                // A local hit finishes through the core ([finishLocal])
                // so it gets the same LLM cleanup + history as server
                // text.
                // Only the LOCAL hook is guarded: a server or core
                // throw must reach the outer catch (session-gated
                // drop) so cancel/shutdown during a blocked call still
                // wins instead of retrying into a dead worker.
                //
                // Over [LOCAL_WINDOW_SAMPLES] the utterance decodes in
                // ordered windows joined into ONE hit (one cleanup, one
                // history row). An overflowed PCM copy is incomplete by
                // construction, so the local hook is skipped outright
                // (server owns the full audio) — truncation never
                // commits silently.
                // An overflowed copy is never snapshotted, and a snapshot that
                // cannot be allocated overflows too: both go server-only.
                val (pcm, overflow) = synchronized(pcmLock) {
                    if (gen != session.get()) return@execute
                    if (capture === cap) capture = null
                    if (pcmOverflowed) {
                        ShortArray(0) to true
                    } else {
                        try {
                            pcmBuf.copyOf(pcmSize) to false
                        } catch (_: OutOfMemoryError) {
                            ShortArray(0) to true
                        }
                    }
                }
                val secs = maxOf(1, pcm.size / 16_000)
                fun decodeLocal(): LocalHit? {
                    // Cancel may have landed during localFirst() or the server call.
                    if (stale()) return null
                    if (overflow) {
                        log("local PCM overflowed, server only")
                        return null
                    }
                    return synchronized(localDecodeLock) {
                        if (stale()) return null
                        try {
                            val windows = localDecodeWindows(pcm)
                            val decoded = if (windows.size == 1) {
                                localTranscribe(pcm)
                            } else {
                                log("local decode in ${windows.size} windows")
                                val parts = mutableListOf<String>()
                                var engine: String? = null
                                for (w in windows) {
                                    // Cancelled between windows: stop decoding dead audio.
                                    if (stale()) return null
                                    val slice = pcm.copyOfRange(w.first, w.last + 1)
                                    val hit = localTranscribe(slice)
                                    if (hit == null) {
                                        // A speech-free window decodes to nothing; any
                                        // other miss misses the whole utterance so a
                                        // gap never commits.
                                        if (LocalStt.chunkPcm(slice).isEmpty()) continue
                                        return null
                                    }
                                    hit.first.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                                    if (engine == null) engine = hit.second
                                }
                                if (engine == null || parts.isEmpty()) null else parts.joinToString(" ") to engine
                            }
                            // Local hits get the WORD word-boost here
                            // ([localPostProcess]); the core applies snippets
                            // in [finishLocal], as on the server path.
                            decoded?.let { (raw, engine) -> LocalHit(raw, localPostProcess(raw), engine) }
                        } catch (_: Throwable) {
                            // Local hook miss on ANY throw (incl. OOM Error).
                            null
                        }
                    }
                }
                fun commitLocal(hit: LocalHit): String =
                    try {
                        finishLocal(hit.raw, hit.text, hit.engine, secs, utterance).first
                    } catch (e: Exception) {
                        // Never lose a decoded utterance to a core
                        // failure: deliver it uncleaned (a cancel still
                        // drops via the session check below).
                        log("core finish for local hit failed: ${e.message}")
                        hit.text
                    }
                val (above, below) = try { remotesAroundLocal() } catch (_: Throwable) { true to false }
                // Cancelled during the settings read: no attempt may start.
                if (stale()) return@execute
                text = run chain@{
                    var aboveError: Exception? = null
                    if (above) {
                        try {
                            return@chain finishTranscription(utterance, false).first
                        } catch (e: Exception) {
                            // Under 0.3 s the server already said "too short"
                            // (an overflowed copy is empty but the audio is not).
                            if ((!overflow && pcm.size < 4_800) || stale()) throw e
                            aboveError = e
                        }
                    }
                    val local = decodeLocal()
                    // Cancelled during the (blocking) local decode: the
                    // result below already drops post-call, but neither a
                    // server upload nor cleanup may run for a dead utterance.
                    if (stale()) return@execute
                    if (local != null) {
                        if (aboveError != null) log("servers above Local failed, local hit")
                        return@chain commitLocal(local)
                    }
                    // No server above and none below still asks the core,
                    // which reports that no server is configured.
                    if (!below && aboveError != null) throw aboveError
                    try {
                        finishTranscription(utterance, true).first
                    } catch (e: Exception) {
                        throw if (aboveError == null) e else IllegalStateException("${aboveError.message}; ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                deliverIfCurrent("error") { listener?.onTranscriptionError(e.message ?: "transcription failed") }
                return@execute
            }
            // Cancelled while blocked in transcription: the call above
            // returned after cancel() — drop, never commit to the IME.
            deliverIfCurrent("result") {
                log("utterance max peak=%.2f".format(maxPeak))
                log("utterance text len=${text.length} hasListener=${listener != null}")
                if (text.isBlank()) {
                    // Silence is never committed and never surfaced: the
                    // "silence" tag maps to the empty state on every
                    // surface (IME shows its idle hint, the bubble toast
                    // filter already drops it). Only a real stop-with-
                    // silence reaches here — a cancelled session drops
                    // above, keeping its "Cancelled" note.
                    log("finish blank gen=$gen: empty state, no commit")
                    listener?.onError("silence")
                } else {
                    listener?.onResult(text)
                }
            }
        }
    }
}

/** Null means this recording is toggle-only; zero means hold controls are ready. */
internal fun holdControlsDelayMs(mode: String, toggled: Boolean, heldMs: Long, ceilingMs: Long): Long? =
    when {
        toggled || mode == "Tap" -> null
        mode == "Push" -> 0L
        else -> (ceilingMs - heldMs).coerceAtLeast(0L)
    }
