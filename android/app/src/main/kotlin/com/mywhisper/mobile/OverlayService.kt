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
        private const val KEY_MOVE_HINT = "float_move_hint_seen"

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
    /**
     * True once [startForegroundWithType] succeeds, false on STOP /
     * destroy. REFRESH must never recreate a stopped service
     * un-foregrounded (PR #2 pattern): an unguarded notify() would
     * leave an orphan ongoing notification STOP cannot remove.
     */
    @Volatile private var foregrounded = false
    private var wm: WindowManager? = null
    private var holder: FrameLayout? = null
    private var micBtn: ImageButton? = null
    private var dragHandle: View? = null
    private var stopBtn: ImageButton? = null
    private var spinner: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var baseX = 0
    private var baseY = 0
    private var moved = false

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
                foregrounded = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // Prefs toggle: rebuild from live state, no restart —
                // but never promote a stopped service. Self-terminate
                // instead of refreshing un-foregrounded.
                if (!foregrounded) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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
                foregrounded = true
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
     * Bubble chrome: the mic button talks, the grip moves, the stop
     * button stops. Split controls (owner UX): the grip is the ONLY
     * move affordance — always visible, 48dp touch target, first-run
     * hint. Touching the mic never moves the bubble and never cancels:
     * tap / long-press / drag on the mic is pure Dictation input. The
     * persisted position is clamped into the live window on show, so a
     * rotation or fold never strands the bubble off-screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (holder != null) {
            refreshChrome(Dictation.State.IDLE)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val size = dp(60)
        val gripTouch = dp(48)
        val stopSize = dp(STOP_VISUAL_DP)
        val rootW = size + stopSize + dp(8)
        val rootH = size + gripTouch / 2 + dp(8)
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
            setImageResource(R.drawable.ic_mic)
            contentDescription = getString(R.string.ime_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6750A4.toInt())
            }
            // Big mark: padding trimmed so the glyph fills the button.
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = dp(BUBBLE_MIC_ELEVATION_DP).toFloat()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
            setOnTouchListener { _, ev -> onMicTouch(ev) }
        }
        micBtn = mic
        root.addView(mic)
        // Calm processing indicator: small indeterminate spinner
        // centered on the mic, visible only while WORKING. Static —
        // no scale/alpha pulsing on the mic itself.
        val spin = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.float_processing)
            isClickable = false
            isFocusable = false
            // Z-order: drawn above the mic glyph (mic sits at 6dp).
            elevation = dp(BUBBLE_SPINNER_ELEVATION_DP).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.START).apply {
                // Center over the mic glyph.
                leftMargin = (size - dp(28)) / 2
                topMargin = (size - dp(28)) / 2
            }
            visibility = View.GONE
        }
        spinner = spin
        root.addView(spin)
        // Explicit stop affordance: always visible while RECORDING or
        // WORKING, hidden while IDLE. Tap stops (finish/commit from
        // RECORDING, cancel/discard from WORKING).
        val stop = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = getString(R.string.float_stop)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFB3261E.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(7).toFloat()
            layoutParams = FrameLayout.LayoutParams(stopSize, stopSize, Gravity.TOP or Gravity.END)
            visibility = View.GONE
            setOnClickListener { onStopTap() }
        }
        stopBtn = stop
        root.addView(stop)
        // 48dp touch target on a 32dp visual (a11y parity with the
        // 48dp grip): expand the hit rect via TouchDelegate on the
        // parent after layout; the drawn size never changes.
        stop.post {
            try {
                val hit = android.graphics.Rect()
                stop.getHitRect(hit)
                val pad = (dp(STOP_TARGET_DP) - dp(STOP_VISUAL_DP)) / 2
                hit.inset(-pad, -pad)
                (stop.parent as? View)?.touchDelegate = android.view.TouchDelegate(hit, stop)
            } catch (_: Exception) {
            }
        }
        // Grip: the ONLY move affordance. Always visible (opaque
        // rounded tab + white pill), 48dp touch target, bottom-end.
        val grip = FrameLayout(this).apply {
            contentDescription = getString(R.string.float_drag_handle)
            elevation = dp(7).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xCC1D1B20.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(gripTouch, dp(24), Gravity.BOTTOM or Gravity.END)
            setOnTouchListener { _, ev -> onHandleTouch(ev) }
        }
        val pill = View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(5), Gravity.CENTER)
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
            stopBtn = null
            spinner = null
            stopSelf()
            return
        }
        // First-run move hint: once, until the user drags the grip.
        try {
            if (FloatState.shouldShowMoveHint(prefs.getBoolean(KEY_MOVE_HINT, false))) {
                Toast.makeText(this, getString(R.string.float_move_hint), Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
        }
        refreshChrome(Dictation.State.IDLE)
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
     * Mic touch: talk only. Movement is ignored — it neither moves the
     * bubble nor cancels the utterance. The grip is the only way to
     * move; the stop button is the only explicit stop. A tap therefore
     * never destroys a recording (owner UX).
     */
    private fun onMicTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                tick()
                dictation.pressDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Deliberately ignored: mic drag never moves, never cancels.
                return true
            }
            MotionEvent.ACTION_UP -> {
                moved = false
                tick()
                dictation.pressUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                dictation.cancel()
                return true
            }
        }
        return false
    }

    /**
     * Grip touch: move only, never Dictation. A drag here can neither
     * start nor cancel a recording; release docks to the edge and marks
     * the first-run hint seen.
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
                if (FloatState.shouldArmMove(dist, dp(10).toFloat(), onHandle = true)) {
                    moved = true
                    moveBubbleBy(ev.rawX - downRawX, ev.rawY - downRawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    snapAndPersist()
                    moved = false
                    markMoveHintSeen()
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

    private fun markMoveHintSeen() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_MOVE_HINT, true).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Explicit stop tap (owner UX): visible while RECORDING or
     * WORKING. RECORDING finishes (commit — two press pairs so a Tap
     * first-hold that only toggled still stops; the second pair
     * no-ops once WORKING/IDLE). WORKING cancels (discard). IDLE is
     * unreachable (button hidden) and no-ops.
     */
    private fun onStopTap() {
        tick()
        when (FloatState.stopAction(dictation.current)) {
            FloatState.StopAction.FINISH -> {
                dictation.pressDown()
                dictation.pressUp()
                // Tap-mode first-hold only toggled above: still
                // RECORDING, so the second press finishes for real.
                if (dictation.current == Dictation.State.RECORDING) {
                    dictation.pressDown()
                    dictation.pressUp()
                }
            }
            FloatState.StopAction.CANCEL -> dictation.cancel()
            FloatState.StopAction.NONE -> Unit
        }
    }

    private fun removeBubble() {
        // Any hide drops the utterance (focus lost mid-press included).
        moved = false
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
        stopBtn = null
        spinner = null
    }

    /**
     * Bubble chrome per state (owner UX): RECORDING reads red-mic +
     * stop, WORKING reads dimmed mic + small spinner + stop (calm
     * processing, no pulsing), IDLE reads purple mic with stop and
     * spinner hidden. Recording vs processing differ at a glance.
     */
    private fun refreshChrome(state: Dictation.State) {
        val recording = FloatState.isRecording(state)
        val processing = FloatState.isProcessing(state)
        micBtn?.apply {
            setBackground(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            recording -> 0xFFB3261E.toInt()
                            processing -> 0xFF4F378B.toInt()
                            else -> 0xFF6750A4.toInt()
                        },
                    )
                },
            )
            // Static dim while processing — no animation, no pulse.
            alpha = if (processing) 0.6f else 1f
        }
        spinner?.visibility = if (processing) View.VISIBLE else View.GONE
        stopBtn?.visibility =
            if (FloatState.isStopVisible(state)) View.VISIBLE else View.GONE
    }

    // -- Dictation.Listener ----------------------------------------------

    override fun onState(s: Dictation.State, toggled: Boolean) {
        micBtn?.post {
            refreshChrome(s)
        }
        refreshNotif(s)
    }

    override fun onResult(text: String) {
        micBtn?.post { refreshChrome(Dictation.State.IDLE) }
        deliver(text)
    }

    private val bgWorkers = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onError(msg: String) {
        micBtn?.post { refreshChrome(Dictation.State.IDLE) }
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
        foregrounded = false
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

/** Mic glyph elevation (dp): the spinner must sit above this. */
internal const val BUBBLE_MIC_ELEVATION_DP = 6

/** Processing spinner elevation (dp): above the mic, below nothing. */
internal const val BUBBLE_SPINNER_ELEVATION_DP = 8

/** Stop button drawn size (dp): kept small, expanded by TouchDelegate. */
internal const val STOP_VISUAL_DP = 32

/** Stop button minimum touch target (dp): a11y parity with the grip. */
internal const val STOP_TARGET_DP = 48

/**
 * Pure hit-rect (framework-free so JVM tests pin it): TouchDelegate
 * inset is negative to expand; the expanded edge equals
 * (target - visual) / 2 per side.
 */
internal data class HitRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Per-side expansion (px) from a visual size to a target size. */
internal fun touchExpandPx(visualPx: Int, targetPx: Int): Int =
    (targetPx - visualPx) / 2

/** Apply a TouchDelegate-style inset (negative = expand) to a rect. */
internal fun expandHitRect(r: HitRect, insetPx: Int): HitRect =
    HitRect(r.left + insetPx, r.top + insetPx, r.right - insetPx, r.bottom - insetPx)
