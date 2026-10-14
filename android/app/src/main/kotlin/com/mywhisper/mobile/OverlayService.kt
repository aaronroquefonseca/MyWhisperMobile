package com.mywhisper.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Floating dictation bubble: an alternative to switching input
 * methods. Draggable, collapses to an edge tab, reuses the mic
 * [Dictation] modes (Smart default, configurable like the IME).
 * Results commit through the accessibility service onto the focused
 * field (the bubble has no InputConnection of its own).
 */
class OverlayService : Service(), Dictation.Listener {

    companion object {
        const val ACTION_START = "com.mywhisper.mobile.float.START"
        const val ACTION_STOP = "com.mywhisper.mobile.float.STOP"
        const val ACTION_REFRESH = "com.mywhisper.mobile.float.REFRESH_NOTIF"
        private const val CHANNEL = "mw-float"
        private const val NOTIF_ID = 42
        private const val PREFS = "mw_state"
        private const val KEY_X = "float_x"
        private const val KEY_Y = "float_y"

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
        }

        /** Rebuild the foreground notification from current state + prefs (no restart). */
        fun refreshNotif(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_REFRESH))
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var dictation: Dictation
    private var wm: WindowManager? = null
    private var holder: FrameLayout? = null
    private var micBtn: ImageButton? = null
    private var dragHandle: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var baseX = 0
    private var baseY = 0
    private var moved = false
    private var moveArmed = false
    private var micCancelled = false
    private var workPulse: android.animation.ObjectAnimator? = null

    /**
     * Long-press-to-move fallback (the drag handle is small): fires
     * only while the mic touch is still down and unmoved. Never touches
     * Dictation itself — arming move mode must not cost a recording.
     */
    private val longPressMove = Runnable {
        moveArmed = true
        tick(60)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Short haptic tick (press / release / toggle). */
    private fun tick(ms: Long = 25L) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vib?.vibrate(
                android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Visibility watchdog: accessibility events miss focus loss
     * (tapping dead space, keyboard dismiss, app switch), leaving
     * the bubble stuck. Re-check the live tree every 2.5s.
     */
    private val watchTick = object : Runnable {
        override fun run() {
            try {
                val focused = try {
                    MyWhisperAccessibility.live?.rootInActiveWindow
                        ?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                        ?.isEditable == true
                } catch (_: Exception) {
                    FloatState.hasTextFocus
                }
                if (!focused && FloatState.hasTextFocus) {
                    FloatState.hasTextFocus = false
                    FloatState.refresh()
                }
            } catch (_: Exception) {
            }
            mainHandler.postDelayed(this, 2500)
        }
    }

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics,
        ).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MwFloat", "service onCreate")
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.float_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        dictation = Dictation(
            smartTapMs = { readLong("hotkeys", "smart_tap_ms", 150L) },
            mode = { readMode() },
            releaseDelayMs = { readLong("stt", "release_delay_ms", 0L) },
        )
        dictation.listener = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun readMode(): String {
        return try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("preferences")?.optString("mic_mode", "Smart")
                ?.takeIf { it in setOf("Smart", "Push", "Tap") } ?: "Smart"
        } catch (_: Exception) {
            "Smart"
        }
    }

    private fun readLong(obj: String, key: String, def: Long): Long {
        return try {
            CoreBridge.getSettings().getOrThrow().optJSONObject(obj)?.optLong(key, def) ?: def
        } catch (_: Exception) {
            def
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Detach first: in-flight Dictation.finish set(IDLE)
                // would otherwise refreshNotif an ongoing notif (42)
                // with no live service behind it.
                try {
                    if (::dictation.isInitialized) dictation.listener = null
                } catch (_: Exception) {
                }
                removeBubble()
                clearFloatState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // Prefs toggle: rebuild from live state, no restart.
                try {
                    val s = if (::dictation.isInitialized) dictation.current else Dictation.State.IDLE
                    refreshNotif(s)
                } catch (_: Exception) {
                }
                return START_STICKY
            }
            else -> {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    // Permission recovery flow: latch the denial (Setup
                    // tells first-run apart from revoked), explain, and
                    // deep-link straight to the overlay grant page so one
                    // tap recovers. Repeat denials keep the revoked copy.
                    val revokedBefore = FloatState.overlayDenied
                    FloatState.recordOverlayDenial()
                    Toast.makeText(
                        this,
                        getString(
                            if (revokedBefore) R.string.float_overlay_recovery
                            else R.string.float_need_overlay,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    openOverlaySettings()
                    removeBubble()
                    clearFloatState()
                    stopSelf()
                    return START_NOT_STICKY
                }
                FloatState.clearOverlayDenied()
                try {
                    startForegroundWithType()
                } catch (e: Exception) {
                    // Background start denied (boot redelivery): never run
                    // un-foregrounded — the system kills us ~10s later.
                    android.util.Log.i("MwFloat", "fg denied, stopping: ${e.message}")
                    removeBubble()
                    clearFloatState()
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureCore()
                dictation.listener = this
                FloatState.onChange = { applyVisibility() }
                applyVisibility()
                mainHandler.removeCallbacks(watchTick)
                mainHandler.postDelayed(watchTick, 2500)
                return START_STICKY
            }
        }
    }

    private fun ensureCore() {
        try {
            val key = KeystoreKey.getOrCreateHex(this)
            CoreBridge.open(filesDir.absolutePath, key)
        } catch (_: Exception) {
        }
    }

    private fun startForegroundWithType() {
        val s = if (::dictation.isInitialized) dictation.current else Dictation.State.IDLE
        val notif = buildNotif(s)
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (Build.VERSION.SDK_INT >= 29) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notif, types)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * Groundwork for the STOP action + tap target: notification tap
     * opens Setup, STOP tears the service down. Per-mode copy driven
     * by [NotifPolicy]: idle = base text, RECORDING = red recording
     * text, WORKING = transcribing + indeterminate progress when the
     * pref allows. STOP + contentIntent on every mode; BigTextStyle
     * keeps the actions expanded.
     */
    internal fun buildNotif(state: Dictation.State): Notification {
        val prefs = NotifPolicy.floatPrefsJson(readPrefsObj())
        val spec = NotifPolicy.forFloatState(state, prefs)
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            Dictation.State.RECORDING -> getString(R.string.float_notif_rec_text)
            Dictation.State.WORKING -> getString(R.string.ime_working)
            Dictation.State.IDLE -> getString(R.string.float_notif_text)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.float_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent)
            .setOngoing(spec.ongoing)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
        if (spec.showProgress) b.setProgress(0, 0, true) else b.setProgress(0, 0, false)
        return b.build()
    }

    /** Legacy boolean entry (WORKING vs base); delegates to the state builder. */
    internal fun buildNotif(working: Boolean): Notification =
        buildNotif(if (working) Dictation.State.WORKING else Dictation.State.IDLE)

    private fun readPrefsObj(): org.json.JSONObject? {
        return try {
            CoreBridge.getSettings().getOrThrow().optJSONObject("preferences")
        } catch (_: Exception) {
            null
        }
    }

    /** Refresh the foreground notification for the live state (no stale mode). */
    private fun refreshNotif(state: Dictation.State) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(state))
        } catch (_: Exception) {
        }
    }

    /** Legacy boolean refresh; delegates to the state refresh. */
    private fun refreshNotif(working: Boolean) {
        refreshNotif(if (working) Dictation.State.WORKING else Dictation.State.IDLE)
    }

    /** Clear process-wide float state (no un-foregrounded run). */
    private fun clearFloatState() {
        try {
            mainHandler.removeCallbacks(watchTick)
        } catch (_: Exception) {
        }
        try {
            FloatState.onChange = null
        } catch (_: Exception) {
        }
    }

    /**
     * Deep-link to the overlay grant page (permission recovery flow).
     * Best-effort: locked-down profiles may refuse; the toast above
     * already explains, so a refused intent stays silent.
     */
    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Auto-show rule: floating enabled, our IME not active, and (when
     * the accessibility service is on) a text field focused. No
     * manual hide needed.
     */
    private fun applyVisibility() {
        val on = try {
            val s = CoreBridge.getSettings().getOrThrow()
            s.optJSONObject("preferences")?.optBoolean("float_enabled", false) == true
        } catch (_: Exception) {
            false
        }
        android.util.Log.i("MwFloat", "apply on=$on ime=${FloatState.imeActive} focus=${FloatState.hasTextFocus}")
        if (!on) {
            removeBubble()
            return
        }
        val show = FloatState.shouldShowBubble(
            floatEnabled = true,
            imeActive = FloatState.imeActive,
            a11yOn = MyWhisperAccessibility.isOn(this),
            hasTextFocus = FloatState.hasTextFocus,
        )
        android.util.Log.i("MwFloat", "apply show=$show holder=${holder != null}")
        if (show) {
            if (holder == null) showBubble()
        } else {
            removeBubble()
        }
    }

    /**
     * Bubble chrome: the mic button talks, the corner grip moves.
     * Split controls (ux P0): touching the mic never moves the bubble
     * and never cancels the utterance — only the grip, or a mic
     * long-press followed by a drag, enters move mode. The persisted
     * position is clamped into the live window on show, so a rotation
     * or fold never strands the bubble off-screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (holder != null) {
            refreshMic(false)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val size = dp(60)
        val gripTouch = dp(32)
        val rootW = size + gripTouch / 2
        val rootH = size + gripTouch / 2
        val lp = WindowManager.LayoutParams(
            rootW, rootH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            val b = safeBounds(rootW, rootH)
            val (cx, cy) = FloatState.clampBubble(
                prefs.getInt(KEY_X, 60), prefs.getInt(KEY_Y, 400),
                b.minX, b.minY, b.maxX, b.maxY,
            )
            it.x = cx
            it.y = cy
        }
        params = lp

        val root = FrameLayout(this)
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stat_sync)
            contentDescription = getString(R.string.ime_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6750A4.toInt())
            }
            // Big mark: padding trimmed so the glyph fills the button.
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
            setOnTouchListener { _, ev -> onMicTouch(ev) }
        }
        micBtn = mic
        root.addView(mic)
        val grip = FrameLayout(this).apply {
            contentDescription = getString(R.string.float_drag_handle)
            elevation = dp(7).toFloat()
            layoutParams = FrameLayout.LayoutParams(gripTouch, gripTouch, Gravity.BOTTOM or Gravity.END)
            setOnTouchListener { _, ev -> onHandleTouch(ev) }
        }
        val pill = View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(0xB3FFFFFF.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(5), Gravity.CENTER)
        }
        grip.addView(pill)
        dragHandle = grip
        root.addView(grip)
        holder = root
        try {
            wm?.addView(root, lp)
        } catch (e: Exception) {
            // Runtime revoke between the START check and addView lands
            // here as SecurityException: same recovery as a cold denial.
            FloatState.recordOverlayDenial()
            openOverlaySettings()
            Toast.makeText(this, getString(R.string.float_overlay_recovery), Toast.LENGTH_LONG).show()
            android.util.Log.i("MwFloat", "addView denied: ${e.message}")
            holder = null
            micBtn = null
            dragHandle = null
            stopSelf()
            return
        }
        refreshMic(false)
    }

    /** Live window bounds minus system insets (re-queried per move: fold/rotate-safe). */
    private data class SafeBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun safeBounds(bubbleW: Int, bubbleH: Int): SafeBounds {
        val w = wm ?: return SafeBounds(0, 0, 0, 0)
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val m = w.currentWindowMetrics
                val b = m.bounds
                val ins = m.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
                SafeBounds(
                    b.left + ins.left,
                    b.top + ins.top,
                    b.right - ins.right - bubbleW,
                    b.bottom - ins.bottom - bubbleH,
                )
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                SafeBounds(0, 0, dm.widthPixels - bubbleW, dm.heightPixels - bubbleH)
            }
        } catch (_: Exception) {
            SafeBounds(0, 0, 0, 0)
        }
    }

    /** Live-clamped drag step (bounds re-queried: fold/rotate-safe). */
    private fun moveBubbleBy(dx: Float, dy: Float) {
        params?.let { lp ->
            val b = safeBounds(lp.width, lp.height)
            val (cx, cy) = FloatState.clampBubble(
                baseX + dx.toInt(), baseY + dy.toInt(),
                b.minX, b.minY, b.maxX, b.maxY,
            )
            lp.x = cx
            lp.y = cy
            try {
                wm?.updateViewLayout(holder, lp)
            } catch (_: Exception) {
            }
        }
    }

    /** Dock to the nearest horizontal edge, clamp, persist. */
    private fun snapAndPersist() {
        params?.let { lp ->
            val b = safeBounds(lp.width, lp.height)
            val (cx, cy) = FloatState.clampBubble(lp.x, lp.y, b.minX, b.minY, b.maxX, b.maxY)
            lp.x = FloatState.snapToEdge(cx, b.minX, b.maxX)
            lp.y = cy
            try {
                wm?.updateViewLayout(holder, lp)
            } catch (_: Exception) {
            }
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Mic touch: talk only. Stray movement is ignored — it neither
     * moves the bubble nor cancels the utterance — except the
     * deliberate slide-down (same 90dp threshold as the IME, with its
     * toast). A tap therefore never destroys a recording (ux P0).
     */
    private fun onMicTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                moveArmed = false
                micCancelled = false
                mainHandler.removeCallbacks(longPressMove)
                mainHandler.postDelayed(
                    longPressMove,
                    android.view.ViewConfiguration.getLongPressTimeout().toLong(),
                )
                tick()
                dictation.pressDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moveArmed && !micCancelled && ev.rawY - downRawY > dp(90)) {
                    // Deliberate slide-down cancel (IME parity).
                    micCancelled = true
                    mainHandler.removeCallbacks(longPressMove)
                    dictation.cancel()
                    tick(60)
                    Toast.makeText(this, getString(R.string.ime_cancelled), Toast.LENGTH_SHORT).show()
                    return true
                }
                val dist = kotlin.math.hypot(
                    (ev.rawX - downRawX).toDouble(),
                    (ev.rawY - downRawY).toDouble(),
                ).toFloat()
                if (!micCancelled &&
                    FloatState.shouldArmMove(dist, dp(10).toFloat(), moveArmed, onHandle = false)
                ) {
                    // Long-press move: reposition under the finger, keep
                    // the utterance alive — moving never destroys it.
                    moved = true
                    moveBubbleBy(ev.rawX - downRawX, ev.rawY - downRawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressMove)
                if (moved) {
                    // Explicit move gesture: dock, leave Dictation state
                    // untouched (a recording keeps running; tap stops).
                    snapAndPersist()
                    moved = false
                    moveArmed = false
                    return true
                }
                moveArmed = false
                tick()
                dictation.pressUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressMove)
                moved = false
                moveArmed = false
                dictation.cancel()
                return true
            }
        }
        return false
    }

    /**
     * Grip touch: move only, never Dictation. A drag here can neither
     * start nor cancel a recording; release docks to the edge.
     */
    private fun onHandleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                tick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = kotlin.math.hypot(
                    (ev.rawX - downRawX).toDouble(),
                    (ev.rawY - downRawY).toDouble(),
                ).toFloat()
                if (FloatState.shouldArmMove(dist, dp(10).toFloat(), longPressFired = false, onHandle = true)) {
                    moved = true
                    moveBubbleBy(ev.rawX - downRawX, ev.rawY - downRawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    snapAndPersist()
                    moved = false
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                return true
            }
        }
        return false
    }

    private fun removeBubble() {
        // Any hide drops the utterance (focus lost mid-press included).
        try {
            mainHandler.removeCallbacks(longPressMove)
        } catch (_: Exception) {
        }
        moved = false
        moveArmed = false
        try {
            dictation.cancel()
        } catch (_: Exception) {
        }
        try {
            holder?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        holder = null
        micBtn = null
        dragHandle = null
    }

    private fun refreshMic(recording: Boolean) {
        micBtn?.setBackground(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (recording) 0xFFB3261E.toInt() else 0xFF6750A4.toInt())
            },
        )
    }

    // -- Dictation.Listener ----------------------------------------------

    override fun onState(s: Dictation.State, toggled: Boolean) {
        micBtn?.post {
            refreshMic(s == Dictation.State.RECORDING)
            // Processing feedback: pulse while transcribing.
            if (s == Dictation.State.WORKING) {
                startWorkPulse()
            } else {
                stopWorkPulse()
            }
        }
        refreshNotif(s)
    }

    /** Gentle alpha pulse while the server works. */
    private fun startWorkPulse() {
        if (workPulse != null) return
        val btn = micBtn ?: return
        try {
            workPulse = android.animation.ObjectAnimator
                .ofFloat(btn, View.ALPHA, 1f, 0.55f)
                .apply {
                    duration = 600
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    start()
                }
        } catch (_: Exception) {
        }
    }

    private fun stopWorkPulse() {
        try {
            workPulse?.cancel()
        } catch (_: Exception) {
        }
        workPulse = null
        try {
            micBtn?.alpha = 1f
        } catch (_: Exception) {
        }
    }

    override fun onResult(text: String) {
        micBtn?.post { refreshMic(false) }
        deliver(text)
    }

    private val bgWorkers = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onError(msg: String) {
        micBtn?.post { refreshMic(false) }
        // Surface errors where the user looks: the bubble has no
        // text row, so only failures that matter get a toast.
        // Listener fires off the main thread — post the toast.
        if (shouldSurfaceError(msg)) {
            mainHandler.post {
                try {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Commit runs here (not in onResult) to keep the result text. */
    fun deliver(text: String) {
        bgWorkers.execute {
            val t = text.trim()
            android.util.Log.i("MwFloat", "deliver len=${t.length}")
            if (t.isEmpty()) return@execute
            val ok = MyWhisperAccessibility.commit(t)
            android.util.Log.i("MwFloat", "commit ok=$ok")
            if (!ok) {
                // Toast must run on the main thread (this is a worker).
                mainHandler.post {
                    try {
                        Toast.makeText(
                            this,
                            getString(R.string.float_no_field),
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            if (::dictation.isInitialized) dictation.listener = null
        } catch (_: Exception) {
        }
        clearFloatState()
        removeBubble()
        bgWorkers.shutdownNow()
        super.onDestroy()
    }
}

/**
 * Pure toast filter (unit-testable): only failures that matter
 * surface as a toast; routine drops stay silent.
 */
internal fun shouldSurfaceError(msg: String): Boolean =
    !msg.contains("too short") && !msg.contains("silence")
