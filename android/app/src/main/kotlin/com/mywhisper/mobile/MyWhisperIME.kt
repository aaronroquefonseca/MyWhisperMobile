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
    private var cancelButton: Button? = null
    private var hintText: TextView? = null
    private var spinner: ProgressBar? = null
    private var pillBox: LinearLayout? = null
    private var barsView: BarsView? = null

    private var lastState = Dictation.State.IDLE
    private var lastToggled = false
    private var lastNote = ""
    private var lastNoteIsError = false
    private var lastAnnounced = ""

    private var micDownRawY = 0f
    private var micCancelled = false

    companion object {
        /** Slide-down distance that cancels a hold (dp). */
        private const val SLIDE_CANCEL_DP = 90

        /** Mic button box (dp): fixed, shared by layout and tests. */
        const val MIC_DP = 84
        /** Mic glyph padding (dp): small so the mark fills the button. */
        const val MIC_PAD_DP = 12
        /**
         * Record-row height (dp): fixed so the row never grows or
         * clips. Fits the mic at max press scale
         * ([MIC_DP] * [MIC_MAX_SCALE] = 94.1 < 100), centered.
         */
        const val ROW_HEIGHT_DP = 100
        /** Status-line height (dp): one line, never wraps or grows. */
        const val STATUS_DP = 26
        /** Largest mic press scale (RECORDING visual). */
        const val MIC_MAX_SCALE = 1.12f

        /** Mic press/release visual transition (ms): owner wants ~150-250. */
        const val MIC_ANIM_MS = 180L
        /** Pill strip bottom gap (dp): pill floats above the bar. */
        const val PILL_BOTTOM_MARGIN_DP = 10
        /** Pill strip side margins (dp): zero bias, centered. */
        const val PILL_SIDE_MARGIN_DP = 0
        /** Strip shell side margins (dp): equal start/end. */
        const val STRIP_SIDE_MARGIN_DP = 8

        /**
         * Pill strip placement: centered with equal start/end margins,
         * LTR/RTL-safe. CENTER_HORIZONTAL is direction-agnostic; side
         * margins are pinned equal (0) so no neighbor weight/padding
         * can bias the pill off-center. Pure (no views): unit-tested.
         */
        fun pillLayoutGravity(): Int = Gravity.CENTER_HORIZONTAL

        /** Symmetry gate: start/end margins must match exactly. */
        fun sideMarginsSymmetric(startPx: Int, endPx: Int): Boolean =
            startPx == endPx
        /** Slide-hint nudge distance (dp): gentle, never blocking. */
        const val SLIDE_HINT_DP = 10
        /**
         * First-hold slide hint latch: companion-scoped = once per process.
         * An instance field would replay after IME-switch service rebirth
         * in a live process; the comment contract promises per-process.
         */
        @Volatile var slideHintShown = false

        /**
         * State-driven mic visuals. Pure (no views): unit-tested, and
         * [Dictation.State] stays the single source of truth — callers
         * only render what this returns.
         */
        data class MicVisual(val color: Int, val scale: Float, val elevationDp: Int)

        fun micVisualFor(s: Dictation.State): MicVisual = when (s) {
            Dictation.State.IDLE -> MicVisual(0xFF6750A4.toInt(), 1.0f, 4)
            Dictation.State.RECORDING -> MicVisual(0xFFB3261E.toInt(), 1.12f, 8)
            Dictation.State.WORKING -> MicVisual(0xFF7C4D00.toInt(), 0.94f, 2)
        }

        /** TalkBack label per state (R.string id). */
        fun micDescResFor(s: Dictation.State): Int = when (s) {
            Dictation.State.IDLE -> R.string.ime_mic
            Dictation.State.RECORDING -> R.string.ime_listening
            Dictation.State.WORKING -> R.string.ime_working
        }

        /**
         * Explicit cancel affordance: visible while RECORDING/WORKING so
         * toggle mode (finger off the mic) can cancel without committing.
         * Tap = [Dictation.cancel], never commit.
         */
        fun cancelVisible(s: Dictation.State): Boolean = s != Dictation.State.IDLE

        /**
         * Cancel slot: reserved (INVISIBLE while idle) so the bar height
         * never changes when Cancel appears mid-session.
         */
        fun cancelVisibility(s: Dictation.State): Int =
            if (cancelVisible(s)) View.VISIBLE else View.INVISIBLE

        /**
         * Visualizer pill: exists only while active. GONE while idle so
         * no dead transparent strip is reserved; appearing grows the
         * window upward with the buttons staying put.
         */
        fun pillVisibility(s: Dictation.State, enabled: Boolean): Int =
            if (s != Dictation.State.IDLE && enabled) View.VISIBLE else View.GONE

        /**
         * Commit gate: blank results never reach the input connection.
         * Defense in depth — [Dictation] already routes silence to the
         * empty state instead of [Dictation.Listener.onResult].
         */
        fun shouldCommitResult(text: String): Boolean = text.isNotBlank()

        /**
         * Routine drops (silence / too-short audio): the empty state,
         * never an error. Mirrors the bubble toast filter, which already
         * drops messages containing these tags.
         */
        fun isRoutineDrop(msg: String): Boolean =
            msg.contains("silence") || msg.contains("too short")

        /**
         * First-hold discovery hint: RECORDING + still held (not toggled)
         * + never shown this process. Toggle sessions (toggled=true) and
         * repeats skip it — subtle, never blocking.
         */
        fun shouldShowSlideHint(
            s: Dictation.State,
            toggled: Boolean,
            alreadyShown: Boolean,
        ): Boolean = s == Dictation.State.RECORDING && !toggled && !alreadyShown
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MyWhisperIME", "ime.onCreate")
        // Re-sync bubble state after process death: memory defaults
        // to imeActive=false; prefs hold the last published value.
        try {
            FloatState.restoreImeActive(this)
        } catch (_: Exception) {
        }
        ensureCore()
        dictation = Dictation(
            smartTapMs = { readSmartTapMs() },
            mode = { readMicMode() },
            releaseDelayMs = { readReleaseDelayMs() },
            // On-device first (moonshine-v2 pack, VAD-chunked); miss or
            // throw → server chain. Pack-gated: null without the pack.
            localTranscribe = { pcm -> LocalStt.transcribeIfEnabled(this, pcm) },
            // IME destroy releases the native recognizer (no resident
            // ORT session across enable/disable cycles in one process).
            releaseLocal = { try { LocalStt.unload() } catch (_: Throwable) {} },
        )
        dictation.listener = this
        dictation.onLevel = { peak ->
            try {
                barsView?.post { barsView?.pushLevel(peak) }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
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
    private fun readReleaseDelayMs(): Long {
        return try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("stt")?.optLong("release_delay_ms", 0L) ?: 0L
        } catch (_: Exception) {
            0L
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

    private fun isNight(): Boolean =
        (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Paint the navigation bar like the bar so no white strip shows. */
    private fun applySystemBars() {
        try {
            val night = isNight()
            window?.window?.navigationBarColor =
                if (night) 0xFF1E1E22.toInt() else 0xFFF2F0F7.toInt()
        } catch (_: Exception) {
        }
    }

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics,
        ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        android.util.Log.i("MyWhisperIME", "ime.onCreateInputView")
        // A fade-out from updatePill may still hold the previous pill:
        // the old view is discarded on rebuild, so cancel it and drop
        // the refs before inflating the new ones.
        try {
            pillBox?.animate()?.cancel()
        } catch (_: Exception) {
        }
        pillBox = null
        barsView = null
        cancelButton = null
        applySystemBars()
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
            // Transparent shell: only the pill and the bar below are
            // opaque, so the app shows through around them.
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also {
                it.setMargins(dp(STRIP_SIDE_MARGIN_DP), dp(4), dp(STRIP_SIDE_MARGIN_DP), 0)
                // RTL-safe: physical left/right above are equal, pin
                // start/end explicitly so mirroring never biases.
                it.marginStart = dp(STRIP_SIDE_MARGIN_DP)
                it.marginEnd = dp(STRIP_SIDE_MARGIN_DP)
            }
        }
        // No reserved slot: the pill is GONE while idle so the window
        // carries no dead transparent strip. Appearing grows the window
        // upward only (buttons stay put); collapsing after.
        // Floating pill (desktop compact/Listening look): dark
        // rounded box with bars only — no buttons, no status dot.
        // The container stays so future states (live text, etc.)
        // can extend it.
        val pillBg = 0xEB1C1C22.toInt()
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(pillBg)
                setStroke(dp(1), 0x1FFFFFFF)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val bars = BarsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(40))
        }
        pill.addView(bars)
        pillBox = pill
        barsView = bars
        root.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also {
            // Explicit center + equal start/end: do not rely on parent
            // gravity alone — that left the pill's placement implicit
            // and off-center in the owner photo (more margin right).
            it.gravity = pillLayoutGravity()
            it.bottomMargin = dp(PILL_BOTTOM_MARGIN_DP)
            it.marginStart = dp(PILL_SIDE_MARGIN_DP)
            it.marginEnd = dp(PILL_SIDE_MARGIN_DP)
        })
        // Opaque bar, rounded top corners only, flush with the
        // screen bottom.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        }
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // Hairline divider on top.
        bar.addView(View(this).apply {
            setBackgroundColor(if (night) 0xFF3A3A40.toInt() else 0xFFD9D4E5.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // Fixed-height record row: the mic at max press scale
        // (84dp * 1.12 = 94.1dp) fits inside 100dp centered, so the
        // circle is never cut top/bottom and the bar never grows.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun iconButton(
            iconRes: Int,
            description: String,
            onClick: () -> Unit,
        ) = ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            // Transparent with ripple: reads as keyboard chrome, not
            // muddy pills.
            setColorFilter(barDim)
            val ripple = android.util.TypedValue()
            if (theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true,
                )
            ) {
                setBackgroundResource(ripple.resourceId)
            } else {
                setBackgroundColor(Color.TRANSPARENT)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
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
            // White mark on brand purple: the gradient mark on purple
            // clashed.
            setImageResource(R.drawable.ic_stat_sync)
            contentDescription = getString(R.string.ime_mic)
            setBackground(micBackground(Dictation.State.IDLE))
            // Small padding + explicit fit: the glyph fills the button
            // (60dp mark). Without FIT_CENTER the raster renders at
            // intrinsic size and padding alone never enlarges it.
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(MIC_PAD_DP), dp(MIC_PAD_DP), dp(MIC_PAD_DP), dp(MIC_PAD_DP))
            elevation = dp(4).toFloat()
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        micDownRawY = ev.rawY
                        micCancelled = false
                        android.util.Log.i("MyWhisperIME", "ime.mic down")
                        tick()
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
                            android.util.Log.i("MyWhisperIME", "ime.mic slide-cancel")
                            dictation.cancel()
                            showCancelled()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        android.util.Log.i("MyWhisperIME", "ime.mic up cancelled=$micCancelled")
                        tick()
                        dictation.pressUp()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        android.util.Log.i("MyWhisperIME", "ime.mic action-cancel")
                        dictation.cancel()
                        true
                    }
                    else -> false
                }
            }
        }
        micButton = mic

        // Balanced sides so the mic stays centered: one flexible box
        // per side, mic fixed in the middle.
        val leftBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sideParams = LinearLayout.LayoutParams(dp(48), dp(48))
        leftBox.addView(switchBtn, sideParams)
        val rightInner = LinearLayout.LayoutParams(dp(48), dp(48)).also {
            it.leftMargin = dp(4)
        }
        rightBox.addView(backBtn, sideParams)
        rightBox.addView(settingsBtn, rightInner)
        row.addView(leftBox)
        row.addView(mic, LinearLayout.LayoutParams(dp(MIC_DP), dp(MIC_DP)))
        row.addView(rightBox)
        bar.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)))

        // Explicit cancel below the mic: toggle mode's finger is off the
        // button, so slide-down alone is unreachable. Present while
        // RECORDING/WORKING; tap = Dictation.cancel(), never commits.
        // Slot reserved (INVISIBLE idle) so the bar height never changes;
        // error-colored text so the destructive action reads prominent.
        val cancel = Button(this).apply {
            text = getString(R.string.ime_cancel)
            contentDescription = getString(R.string.ime_cancel)
            setTextColor(errorColor())
            visibility = View.INVISIBLE
            setOnClickListener {
                micCancelled = true
                android.util.Log.i("MyWhisperIME", "ime.cancel tap")
                dictation.cancel()
                showCancelled()
            }
        }
        cancelButton = cancel
        bar.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) })

        // Fixed-height status line: one ellipsized line, so long errors
        // wrap nowhere and the bar height never changes.
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
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hintText = hint
        status.addView(spin)
        status.addView(hint, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(STATUS_DP)))

        refresh()
        return root
    }

    /** "Cancelled" flash + haptic tick after a slide-down cancel. */
    private fun showCancelled() {
        lastNote = getString(R.string.ime_cancelled)
        lastNoteIsError = false
        android.util.Log.i("MyWhisperIME", "ime.cancelled confirm")
        tick(60)
        postRefresh()
    }

    /**
     * First-hold discovery hint: the mic gently nudges down and the
     * "slide down to cancel" label fades in/out. Subtle, never blocking:
     * view animations only, no touch interception, no session change.
     */
    private fun playSlideHint() {
        try {
            val mic = micButton ?: return
            val label = hintText
            val dip = dp(SLIDE_HINT_DP).toFloat()
            mic.animate()?.translationYBy(dip)?.setDuration(MIC_ANIM_MS)
                ?.withEndAction {
                    try {
                        mic.animate()?.translationYBy(-dip)
                            ?.setDuration(MIC_ANIM_MS)?.start()
                    } catch (_: Exception) {
                    }
                }?.start()
            label?.let {
                it.animate()?.alpha(0.35f)?.setDuration(MIC_ANIM_MS)
                    ?.withEndAction {
                        try {
                            it.animate()?.alpha(1f)?.setDuration(MIC_ANIM_MS)?.start()
                        } catch (_: Exception) {
                        }
                    }?.start()
            }
        } catch (_: Exception) {
        }
    }

    private fun tick(ms: Long = 25L) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vib?.vibrate(
                android.os.VibrationEffect.createOneShot(
                    ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        } catch (_: Exception) {
        }
    }

    /** Delete one char before the cursor through the input connection. */
    private fun backspaceOnce() {
        try {
            currentInputConnection?.deleteSurroundingText(1, 0)
        } catch (_: Exception) {
        }
    }

    /** Hold-to-repeat backspace while long-pressed. Single shared
     * Handler: removeCallbacks only cancels runnables posted through
     * the SAME instance, a fresh Handler per call never stops. */
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backRepeat: Runnable? = null
    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceOnce()
        val tick = object : Runnable {
            override fun run() {
                backspaceOnce()
                repeatHandler.postDelayed(this, 60)
            }
        }
        backRepeat = tick
        repeatHandler.postDelayed(tick, 400)
    }

    private fun stopBackspaceRepeat() {
        backRepeat?.let {
            try {
                repeatHandler.removeCallbacks(it)
            } catch (_: Exception) {
            }
        }
        backRepeat = null
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopBackspaceRepeat()
        FloatState.setImeActive(this, false)
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean,
    ) {
        android.util.Log.i("MyWhisperIME", "ime.onStartInputView")
        super.onStartInputView(info, restarting)
        applySystemBars()
        // Our bar is up: the floating bubble stands down. Persisted
        // so the bubble re-syncs after process death.
        FloatState.setImeActive(this, true)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        android.util.Log.i("MyWhisperIME", "ime.onEvaluateInputViewShown")
        super.onEvaluateInputViewShown()
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

    private fun micBackground(s: Dictation.State): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(micVisualFor(s).color)
        }

    /**
     * Theme error color (Compose colorScheme.error equivalent for
     * Views): ?android:attr/colorError on API 31+, else the night-aware
     * mw_error resource. No hardcoded hue.
     */
    private fun errorColor(): Int {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val tv = TypedValue()
                if (theme.resolveAttribute(android.R.attr.colorError, tv, true)) {
                    if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data
                    }
                    if (tv.resourceId != 0) return getColor(tv.resourceId)
                }
            } catch (_: Exception) {
            }
        }
        return try {
            getColor(R.color.mw_error)
        } catch (_: Exception) {
            0xFFB3261E.toInt()
        }
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
        android.util.Log.i("MyWhisperIME", "ime.onState $s toggled=$toggled")
        postRefresh()
    }

    override fun onResult(text: String) {
        // Blank guard: silence never commits. Only a genuine stop can
        // reach here with text (cancelled sessions drop in Dictation),
        // and even then blanks show the empty state, never commitText.
        if (!shouldCommitResult(text)) {
            android.util.Log.i("MyWhisperIME", "ime.onResult drop blank len=${text.length}")
            lastNote = ""
            lastNoteIsError = false
            postRefresh()
            return
        }
        lastNote = ""
        lastNoteIsError = false
        android.util.Log.i("MyWhisperIME", "ime.onResult commit len=${text.length}")
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (_: Exception) {
        }
        postRefresh()
    }

    override fun onError(msg: String) {
        if (isRoutineDrop(msg)) {
            // Silence / too-short audio: the empty state, never red,
            // never announced. Only a real stop reaches here — a
            // cancelled session drops in Dictation first.
            android.util.Log.i("MyWhisperIME", "ime.onError routine-drop: empty state")
            lastNote = ""
            lastNoteIsError = false
        } else {
            android.util.Log.i("MyWhisperIME", "ime.onError msg=$msg")
            lastNote = msg
            lastNoteIsError = true
        }
        postRefresh()
    }

    /** Floating pill: present only while active, GONE while idle. */
    private fun updatePill(s: Dictation.State) {
        val pill = pillBox ?: return
        val enabled = try {
            CoreBridge.getSettings().getOrThrow()
                .optJSONObject("preferences")?.optBoolean("show_pill", true) != false
        } catch (_: Exception) {
            true
        }
        if (pillVisibility(s, enabled) == View.VISIBLE) {
            if (pill.visibility != View.VISIBLE) {
                pill.alpha = 0f
                pill.visibility = View.VISIBLE
                pill.animate().alpha(1f).setDuration(220).start()
            }
        } else {
            barsView?.clear()
            if (pill.visibility == View.VISIBLE) {
                // onCreateInputView rebuilds pillBox: a stale fade-out
                // must not mutate the replacement view.
                val target = pill
                target.animate().alpha(0f).setDuration(120).withEndAction {
                    if (!HardeningGuards.pillEndActionApplies(pillBox, target)) return@withEndAction
                    target.visibility = View.GONE
                    target.alpha = 1f
                }.start()
            } else if (pill.visibility != View.GONE) {
                // No reserved slot while idle: collapse fully so the
                // window carries no dead transparent strip.
                pill.visibility = View.GONE
            }
        }
    }

    private fun postRefresh() {
        try {
            micButton?.post { refresh() }
        } catch (_: Exception) {
        }
    }

    private fun refresh() {
        // State-driven mic: Dictation.State is the single source of truth.
        // Idle/record/working each distinct (color/scale/glow), animated
        // ~180ms; TalkBack label follows the state.
        val visual = micVisualFor(lastState)
        micButton?.setBackground(micBackground(lastState))
        try {
            micButton?.animate()?.scaleX(visual.scale)?.scaleY(visual.scale)
                ?.setDuration(MIC_ANIM_MS)?.start()
            micButton?.elevation = dp(visual.elevationDp).toFloat()
        } catch (_: Exception) {
        }
        try {
            micButton?.contentDescription = getString(micDescResFor(lastState))
        } catch (_: Exception) {
        }
        // Toggle-mode escape hatch: explicit cancel while RECORDING or
        // WORKING. Tap cancels via Dictation.cancel() (no commit); the
        // slide gesture stays for hold mode. Slot reserved while idle
        // (INVISIBLE) so the bar height never changes.
        cancelButton?.visibility = cancelVisibility(lastState)
        // First-hold discovery: gentle nudge + label pulse, once per
        // process, never blocking (pure view animation, no input eaten).
        if (shouldShowSlideHint(lastState, lastToggled, slideHintShown)) {
            slideHintShown = true
            playSlideHint()
        }
        spinner?.visibility =
            if (lastState == Dictation.State.WORKING) View.VISIBLE else View.GONE
        updatePill(lastState)
        val hint = hintText
        hint?.text = when (lastState) {
            // Recording: slide-down cancels mid-hold; the explicit Cancel
            // button below covers toggle mode (finger off the mic).
            Dictation.State.RECORDING -> getString(R.string.ime_slide_cancel)
            Dictation.State.WORKING -> getString(R.string.ime_working)
            Dictation.State.IDLE -> lastNote.ifEmpty { idleHint() }
        }
        // Errors in the theme error color, status in the theme dim color.
        // A cancel confirmation is not an error: dim status color, silent.
        val night = (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isError = lastNoteIsError && lastState == Dictation.State.IDLE && lastNote.isNotEmpty()
        hint?.setTextColor(
            if (isError) errorColor()
            else if (night) 0xFFA8A4B5.toInt() else 0xFF5F5B66.toInt(),
        )
        // TalkBack: error text is otherwise silent on a status line.
        if (isError && HardeningGuards.shouldAnnounceError(lastNote, lastAnnounced)) {
            lastAnnounced = lastNote
            try {
                hint?.announceForAccessibility(lastNote)
            } catch (_: Exception) {
            }
        } else if (!isError) {
            lastAnnounced = ""
        }
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
