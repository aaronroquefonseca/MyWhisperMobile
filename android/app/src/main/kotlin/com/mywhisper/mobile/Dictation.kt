package com.mywhisper.mobile

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Local-hook PCM cap: 60s @ 16 kHz mono (utterance copy only). */
internal const val MAX_LOCAL_PCM_SAMPLES = 16_000 * 60

/**
 * User-facing rewrite for a failed [Dictation.finish]: when on-device
 * missed and no server chain exists, the committed core copy still
 * claims on-device is unsupported ("Local/on-device is not supported
 * yet") — stale on Android, where the Kotlin local-first hook above
 * runs before the server chain. iOS keeps the raw copy (server-only
 * there). Pure for unit tests.
 */
internal fun userFacingFinishError(msg: String): String =
    if ("no server STT configured" in msg) {
        "On-device found nothing and no server is configured — " +
            "check the Local entry and its model, or add a server."
    } else {
        msg
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
        onPcm: (ShortArray, Int) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ) -> Capture? = { onPcm, onError, onLevel ->
        val cap = AudioCapture(context, onPcm = onPcm, onError = onError, onLevel = onLevel)
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
     * Forwards capture PCM to the core recorder. Injected (default
     * `CoreBridge.pushPcm`) so JVM unit tests can fire the capture
     * path without the native lib.
     */
    private val pushPcm: (ShortArray, Int) -> Unit = { buf, n -> CoreBridge.pushPcm(buf, n) },
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
     * `LocalStt.isLocalFirst`): true = decode on-device before the
     * server chain; false = the Local entry sits below a server, so
     * it only runs after the server chain fails.
     */
    private val localFirst: () -> Boolean = { true },
    /**
     * Finishes a local hit through the core (LLM cleanup chain +
     * history), args (raw, post-processed, engine, durationSecs) →
     * (text, engine). Default `CoreBridge.finishLocalUtterance`.
     */
    private val finishLocal: (String, String, String, Int) -> Pair<String, String> =
        { raw, text, engine, secs -> CoreBridge.finishLocalUtterance(raw, text, engine, secs) },
    /**
     * Shutdown hook releasing the on-device recognizer
     * (`LocalStt.unload`). Injected (default no-op) so JVM unit tests
     * never touch the native runtime, which is absent off-device.
     * Production wires `LocalStt.unload` at every construction site.
     */
    private val releaseLocal: () -> Unit = {},
    private val log: (String) -> Unit = { msg -> android.util.Log.i("MwDict", msg) },
    private val workers: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    enum class State { IDLE, RECORDING, WORKING }

    /** On-device decode: [raw] decoder output, [text] after [localPostProcess]. */
    private data class LocalHit(val raw: String, val text: String, val engine: String)

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
     * Utterance PCM copy for the local-first hook: primitive buffer,
     * no boxing (was `ArrayList<Short>`: one object per sample plus a
     * second copy on snapshot). Grown by doubling, hard-capped at
     * [MAX_LOCAL_PCM_SAMPLES]. Guarded by [pcmLock]; the buffer itself
     * is retained across utterances to avoid realloc churn.
     */
    private val pcmLock = Any()
    private var pcmBuf = ShortArray(16_000 * 10)
    private var pcmSize = 0
    /**
     * True once the utterance outgrew [MAX_LOCAL_PCM_SAMPLES]. The
     * overflow is never silently truncated: the local hook is skipped
     * for the utterance (its PCM would be incomplete) and the full
     * audio falls back to the server chain, with a log line. Volatile;
     * set under [pcmLock], read on the finish worker.
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
                synchronized(pcmLock) {
                    val take = minOf(n, MAX_LOCAL_PCM_SAMPLES - pcmSize).coerceAtLeast(0)
                    if (pcmSize + take > pcmBuf.size) {
                        pcmBuf = pcmBuf.copyOf(
                            maxOf(pcmBuf.size * 2, pcmSize + take)
                                .coerceAtMost(MAX_LOCAL_PCM_SAMPLES),
                        )
                    }
                    if (take > 0) {
                        System.arraycopy(copy, 0, pcmBuf, pcmSize, take)
                        pcmSize += take
                    }
                    if (take < n && !pcmOverflowed) {
                        pcmOverflowed = true
                        log("local PCM capped at 60s, overflow falls back to server")
                    }
                }
                try {
                    pushPcm(copy, n)
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
        synchronized(pcmLock) {
            pcmSize = 0
            pcmOverflowed = false
        }
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
        session.incrementAndGet()
        log("cancel $was->IDLE session=${session.get()}")
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
        // Release the on-device recognizer: without this the native ORT
        // session stays resident across IME enable/disable cycles in a
        // surviving process. Never throws (hook is guarded).
        try {
            releaseLocal()
        } catch (_: Throwable) {
        }
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
            if (gen != session.get()) {
                log("finish drop stale-tail gen=$gen session=${session.get()}")
                return@execute
            }
            val cap = capture
            if (cap == null || gen != session.get()) {
                log("finish drop stale-capture gen=$gen session=${session.get()}")
                return@execute
            }
            try {
                cap.stop()
            } catch (_: Exception) {
            }
            capture = null
            val text: String
            try {
                // On-device decode via LocalStt on the utterance PCM,
                // honoring chain order: first enabled entry → decode
                // before the server; lower in the chain → only after
                // the server chain throws. Either way a hit finishes
                // through the core ([finishLocal]) so it gets the same
                // LLM cleanup + history as server text.
                // Only the LOCAL hook is guarded: a server or core
                // throw must reach the outer catch (session-gated
                // drop) so cancel/shutdown during a blocked call still
                // wins instead of retrying into a dead worker.
                //
                // An overflowed PCM copy is incomplete by construction,
                // so the local hook is skipped outright (server owns the
                // full audio) — truncation never commits silently.
                val (pcm, overflow) = synchronized(pcmLock) {
                    pcmBuf.copyOf(pcmSize) to pcmOverflowed
                }
                val secs = maxOf(1, pcm.size / 16_000)
                fun decodeLocal(): LocalHit? {
                    if (overflow) {
                        log("local PCM overflowed 60s cap, server only")
                        return null
                    }
                    return try {
                        // Local hits get the WORD word-boost here
                        // ([localPostProcess]); the core applies snippets
                        // in [finishLocal], as on the server path.
                        localTranscribe(pcm)?.let { (raw, engine) -> LocalHit(raw, localPostProcess(raw), engine) }
                    } catch (_: Throwable) {
                        // Local hook miss on ANY throw (incl. OOM Error).
                        null
                    }
                }
                fun commitLocal(hit: LocalHit): String =
                    try {
                        finishLocal(hit.raw, hit.text, hit.engine, secs).first
                    } catch (e: Exception) {
                        // Never lose a decoded utterance to a core
                        // failure: deliver it uncleaned (a cancel still
                        // drops via the session check below).
                        log("core finish for local hit failed: ${e.message}")
                        hit.text
                    }
                val first = try { localFirst() } catch (_: Throwable) { true }
                val local = if (first) decodeLocal() else null
                // Cancelled during the (blocking) local decode: the
                // result below already drops post-call, but neither the
                // server upload nor cleanup may run for a dead utterance.
                if (gen != session.get()) return@execute
                text = if (local != null) {
                    commitLocal(local)
                } else {
                    try {
                        finishTranscription().first
                    } catch (e: Exception) {
                        // Local entry below the servers: fallback.
                        // Under 0.3 s the server already said "too short".
                        if (first || pcm.size < 4_800 || gen != session.get()) throw e
                        val fallback = decodeLocal() ?: throw e
                        if (gen != session.get()) return@execute
                        log("server chain failed, local fallback")
                        commitLocal(fallback)
                    }
                }
            } catch (e: Exception) {
                if (gen == session.get()) {
                    listener?.onError(userFacingFinishError(e.message ?: "transcription failed"))
                    set(State.IDLE)
                } else {
                    log("finish drop stale-error gen=$gen session=${session.get()}")
                }
                return@execute
            }
            // Cancelled while blocked in transcription: the call above
            // returned after cancel() — drop, never commit to the IME.
            if (gen != session.get()) {
                log("finish drop stale-result gen=$gen session=${session.get()}")
                return@execute
            }
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
            set(State.IDLE)
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
