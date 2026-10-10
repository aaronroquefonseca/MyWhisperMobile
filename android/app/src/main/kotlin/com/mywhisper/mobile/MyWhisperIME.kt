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

    private var lastState = Dictation.State.IDLE
    private var lastToggled = false
    private var lastNote = ""

    private var micDownRawY = 0f
    private var micCancelled = false

    companion object {
        /** Slide-down distance that cancels a hold (dp). */
        private const val SLIDE_CANCEL_DP = 90
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MyWhisperIME", "ime.onCreate")
        ensureCore()
        dictation = Dictation(
            smartTapMs = { readSmartTapMs() },
            mode = { readMicMode() },
        )
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

    private fun readMicMode(): String {
        return try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("preferences")?.optString("mic_mode", "Smart")
                ?.takeIf { it in setOf("Smart", "Push", "Tap") } ?: "Smart"
        } catch (_: Exception) {
            "Smart"
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

        val night = (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Solid, opaque bar: readable over any app behind it.
        val barBg = if (night) 0xFF1E1E22.toInt() else 0xFFF2F0F7.toInt()
        val barFg = if (night) 0xFFE8E4F0.toInt() else 0xFF1D1B20.toInt()
        val barDim = if (night) 0xFFA8A4B5.toInt() else 0xFF5F5B66.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Opaque bar, rounded top corners only, flush with the
            // screen bottom.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(barBg)
                cornerRadii = floatArrayOf(
                    dp(22).toFloat(), dp(22).toFloat(),
                    dp(22).toFloat(), dp(22).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
            setPadding(dp(12), dp(10), dp(12), dp(14))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also {
                it.setMargins(dp(8), dp(4), dp(8), 0)
            }
        }
        // Hairline divider on top.
        root.addView(View(this).apply {
            setBackgroundColor(if (night) 0xFF3A3A40.toInt() else 0xFFD9D4E5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        fun iconButton(
            iconRes: Int,
            description: String,
            onClick: () -> Unit,
        ) = ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setColorFilter(barFg)
            setBackground(sideBackground(barBg, night))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
        val switchBtn = iconButton(
            R.drawable.ic_ime_keyboard, getString(R.string.ime_keyboard),
        ) { showKeyboardPicker() }
        // Backspace: tap deletes one char, hold repeats (no full
        // keyboard while dictating).
        val backBtn = iconButton(
            R.drawable.ic_ime_backspace, getString(R.string.ime_backspace),
        ) { backspaceOnce() }
        backBtn.setOnTouchListener { _, ev ->
            // DOWN deletes once and arms repeat; UP before the first
            // tick means exactly one delete, holding repeats.
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopBackspaceRepeat()
                    true
                }
                else -> false
            }
        }
        val settingsBtn = iconButton(
            R.drawable.ic_ime_settings, getString(R.string.ime_settings),
        ) { openConfig() }
        val mic = ImageButton(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            contentDescription = getString(R.string.ime_mic)
            setBackground(micBackground(false))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(4).toFloat()
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        micDownRawY = ev.rawY
                        micCancelled = false
                        dictation.pressDown()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Slide down to cancel (WhatsApp-style): once
                        // past the threshold the utterance is dropped.
                        if (!micCancelled && lastState == Dictation.State.RECORDING &&
                            ev.rawY - micDownRawY > dp(SLIDE_CANCEL_DP)
                        ) {
                            micCancelled = true
                            dictation.cancel()
                            showCancelled()
                        }
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
        row.addView(switchBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(spacerL)
        row.addView(mic, LinearLayout.LayoutParams(dp(84), dp(84)))
        row.addView(spacerR)
        row.addView(backBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(settingsBtn, LinearLayout.LayoutParams(dp(52), dp(52)).also {
            it.leftMargin = dp(8)
        })
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val spin = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also {
                it.marginEnd = dp(6)
            }
        }
        spinner = spin
        val hint = TextView(this).apply {
            text = idleHint()
            setTextColor(barDim)
            textSize = 13f
        }
        hintText = hint
        status.addView(spin)
        status.addView(hint)
        root.addView(status)

        refresh()
        return root
    }

    /** "Cancelled" flash after a slide-down cancel. */
    private fun showCancelled() {
        lastNote = getString(R.string.ime_cancelled)
        postRefresh()
    }

    /** Delete one char before the cursor through the input connection. */
    private fun backspaceOnce() {
        try {
            currentInputConnection?.deleteSurroundingText(1, 0)
        } catch (_: Exception) {
        }
    }

    /** Hold-to-repeat backspace while long-pressed. */
    private var backRepeat: Runnable? = null
    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceOnce()
        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                backspaceOnce()
                handler.postDelayed(this, 60)
            }
        }
        backRepeat = tick
        handler.postDelayed(tick, 400)
    }

    private fun stopBackspaceRepeat() {
        backRepeat?.let {
            try {
                android.os.Handler(mainLooper).removeCallbacks(it)
            } catch (_: Exception) {
            }
        }
        backRepeat = null
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

    private fun sideBackground(barBg: Int, night: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (night) 0xFF2C2C31.toInt() else 0xFFE4E0EC.toInt())
            setStroke(dp(1), if (night) 0xFF45454C.toInt() else 0xFFC9C4D6.toInt())
        }

    private fun idleHint(): String = when (readMicMode()) {
        "Push" -> getString(R.string.ime_hint_push)
        "Tap" -> getString(R.string.ime_hint_tap)
        else -> getString(R.string.ime_hint_smart)
    }

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
        hintText?.text = when (lastState) {
            // While recording the escape hatch is sliding down, not a
            // button you cannot reach mid-hold.
            Dictation.State.RECORDING -> getString(R.string.ime_slide_cancel)
            Dictation.State.WORKING -> getString(R.string.ime_working)
            Dictation.State.IDLE -> lastNote.ifEmpty { idleHint() }
        }
        // Errors in red, status in the theme dim color.
        val night = (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        hintText?.setTextColor(
            if (lastState == Dictation.State.IDLE && lastNote.isNotEmpty()) 0xFFD92D20.toInt()
            else if (night) 0xFFA8A4B5.toInt() else 0xFF5F5B66.toInt(),
        )
    }

    /**
     * No system globe key (see method.xml
     * supportsSwitchingToNextInputMethod): it can strand the user
     * with no visible input method. Our ABC button opens the picker
     * instead.
     */

    /**
     * Keyboard switch: open the system picker (always works) instead
     * of guessing prev/next — the old chain could leave no input
     * method visible at all.
     */
    private fun showKeyboardPicker() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {
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
