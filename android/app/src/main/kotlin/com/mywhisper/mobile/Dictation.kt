package com.mywhisper.mobile

import java.util.concurrent.Executors

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
) {
    enum class State { IDLE, RECORDING, WORKING }

    interface Listener {
        fun onState(s: State, toggled: Boolean)
        fun onResult(text: String)
        fun onError(msg: String)
    }

    var listener: Listener? = null
    private val workers = Executors.newSingleThreadExecutor()

    private var state = State.IDLE
    private var toggled = false
    private var downAt = 0L
    private var capture: AudioCapture? = null

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
                    // Second press while toggled-on: finish.
                    finish()
                }
                return
            }
            State.IDLE -> Unit
        }
        val cap = AudioCapture(
            onPcm = { buf, n ->
                // Copy: the buffer is reused by the read loop.
                val copy = buf.copyOf(n)
                try {
                    CoreBridge.pushPcm(copy, n)
                } catch (_: Exception) {
                }
            },
            onError = { listener?.onError(it); hardStop() },
        )
        if (!cap.start()) {
            return
        }
        capture = cap
        toggled = false
        downAt = System.currentTimeMillis()
        set(State.RECORDING)
    }

    fun pressUp() {
        if (state != State.RECORDING || toggled) return
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
        hardStop()
        try {
            CoreBridge.cancelUtterance()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
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
        set(State.IDLE)
    }

    private fun finish() {
        if (state != State.RECORDING) return
        try {
            capture?.stop()
        } catch (_: Exception) {
        }
        capture = null
        toggled = false
        set(State.WORKING)
        workers.execute {
            try {
                val (text, _) = CoreBridge.finishUtterance()
                if (text.isBlank()) {
                    listener?.onError("empty transcript")
                } else {
                    listener?.onResult(text)
                }
            } catch (e: Exception) {
                listener?.onError(e.message ?: "transcription failed")
            }
            set(State.IDLE)
        }
    }
}
