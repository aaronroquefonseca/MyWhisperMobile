package com.mywhisper.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The dictation IME: a mic bar instead of a keyboard. The mic button is
 * Smart by default — hold and release stops, a quick tap toggles — using
 * the desktop `smart_tap_ms` setting.
 *
 * Plain Android views (no Compose): an IME window has no Activity, so
 * Compose would need a hand-driven lifecycle; a static bar does not.
 *
 * Results commit through the current InputConnection, the Android
 * equivalent of desktop auto-paste.
 */
class MyWhisperIME : InputMethodService(), Dictation.Listener {

    private lateinit var dictation: Dictation

    private var micButton: ImageButton? = null
    private var hintText: TextView? = null
    private var spinner: ProgressBar? = null
    private var cancelButton: Button? = null

    private var lastState = Dictation.State.IDLE
    private var lastToggled = false
    private var lastNote = ""

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MyWhisperIME", "ime.onCreate")
        ensureCore()
        dictation = Dictation(smartTapMs = { readSmartTapMs() })
        dictation.listener = this
    }

    override fun onDestroy() {
        try {
            dictation.shutdown()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun ensureCore() {
        try {
            val key = KeystoreKey.getOrCreateHex(this)
            CoreBridge.open(filesDir.absolutePath, key)
        } catch (_: Exception) {
        }
    }

    private fun readSmartTapMs(): Long {
        return try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("hotkeys")?.optLong("smart_tap_ms", 150L) ?: 150L
        } catch (_: Exception) {
            150L
        }
    }

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics,
        ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        android.util.Log.i("MyWhisperIME", "ime.onCreateInputView")
        // Usage flag: the config app cannot read the current IME on
        // targetSdk 34+, so the IME reports itself.
        try {
            getSharedPreferences("mw_state", MODE_PRIVATE)
                .edit().putLong("ime_seen_ts", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchBtn = Button(this).apply {
            text = "ABC"
            setOnClickListener { switchToPrevious() }
        }
        val settingsBtn = Button(this).apply {
            text = "SET"
            setOnClickListener { openConfig() }
        }
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = "Dictate"
            setBackground(micBackground(false))
            setColorFilter(Color.WHITE)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dictation.pressDown()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        dictation.pressUp()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dictation.cancel()
                        true
                    }
                    else -> false
                }
            }
        }
        micButton = mic

        val spacerL = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val spacerR = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        row.addView(switchBtn)
        row.addView(spacerL)
        row.addView(mic, LinearLayout.LayoutParams(dp(72), dp(72)))
        row.addView(spacerR)
        row.addView(settingsBtn)
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val spin = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also {
                it.marginEnd = dp(6)
            }
        }
        spinner = spin
        val hint = TextView(this).apply { text = idleHint() }
        hintText = hint
        val cancel = Button(this).apply {
            text = "Cancel"
            visibility = View.GONE
            setOnClickListener { dictation.cancel() }
        }
        cancelButton = cancel
        status.addView(spin)
        status.addView(hint)
        status.addView(cancel)
        root.addView(status)

        refresh()
        return root
    }

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean,
    ) {
        android.util.Log.i("MyWhisperIME", "ime.onStartInputView")
        super.onStartInputView(info, restarting)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        android.util.Log.i("MyWhisperIME", "ime.onEvaluateInputViewShown")
        return true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.i("MyWhisperIME", "ime.onUnbind")
        return super.onUnbind(intent)
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        android.util.Log.i("MyWhisperIME", "ime.onShowInputRequested")
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onStartInput(
        attribute: android.view.inputmethod.EditorInfo?,
        restarting: Boolean,
    ) {
        android.util.Log.i("MyWhisperIME", "ime.onStartInput type=${attribute?.inputType}")
        super.onStartInput(attribute, restarting)
    }

    private fun micBackground(recording: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (recording) 0xFFB3261E.toInt() else 0xFF6750A4.toInt())
        }

    private fun idleHint(): String = "hold to talk · tap to toggle"

    // -- Dictation.Listener (may arrive on a worker thread) ------------

    override fun onState(s: Dictation.State, toggled: Boolean) {
        lastState = s
        lastToggled = toggled
        postRefresh()
    }

    override fun onResult(text: String) {
        lastNote = ""
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (_: Exception) {
        }
        postRefresh()
    }

    override fun onError(msg: String) {
        lastNote = msg
        postRefresh()
    }

    private fun postRefresh() {
        try {
            micButton?.post { refresh() }
        } catch (_: Exception) {
        }
    }

    private fun refresh() {
        micButton?.setBackground(micBackground(lastState == Dictation.State.RECORDING))
        spinner?.visibility =
            if (lastState == Dictation.State.WORKING) View.VISIBLE else View.GONE
        cancelButton?.visibility =
            if (lastState == Dictation.State.RECORDING) View.VISIBLE else View.GONE
        hintText?.text = when (lastState) {
            Dictation.State.RECORDING ->
                if (lastToggled) "recording — tap mic to finish"
                else "recording — release to stop, quick tap toggles"
            Dictation.State.WORKING -> "transcribing…"
            Dictation.State.IDLE -> lastNote.ifEmpty { idleHint() }
        }
    }

    private fun switchToPrevious() {
        try {
            if (!switchToPreviousInputMethod()) switchToNextInputMethod(false)
        } catch (_: Exception) {
            try {
                switchToNextInputMethod(false)
            } catch (_: Exception) {
            }
        }
    }

    private fun openConfig() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
        }
    }
}
