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
import androidx.core.content.edit
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
        const val ACTION_UNSNOOZE = "com.mywhisper.mobile.float.UNSNOOZE"
        const val ACTION_REFRESH = "com.mywhisper.mobile.float.REFRESH_NOTIF"
        // Shared row: same channel + ID as SyncService (see
        // NotifPolicy.UNIFIED_*); one visible row, two FGS owners.
        private const val CHANNEL = "mw-status"
        private const val NOTIF_ID = 41
        private const val PREFS = "mw_state"
        private const val KEY_X = "float_x"
        private const val KEY_Y = "float_y"
        private const val KEY_MOVE_HINT = "float_move_hint_seen"
        private const val KEY_GRAB = "float_grab"
        private const val KEY_PRESS_DELAY = "float_press_delay"

        fun start(ctx: Context) {
            // Enabled by default; first-run setup owns permission requests.
            // Avoid launching system settings or a microphone foreground service
            // until the user has granted the required permissions.
            if (!android.provider.Settings.canDrawOverlays(ctx) ||
                !MyWhisperAccessibility.isOn(ctx) ||
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
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
    private var holder: ViewGroup? = null
    private var haloView: View? = null
    private var micBtn: ImageButton? = null
    private var dragHandle: View? = null
    private var stopBtn: ImageButton? = null
    private var spinner: View? = null
    private var params: WindowManager.LayoutParams? = null
    // Bottom-center level visualizer pill (RECORDING only).
    private var vizHolder: FrameLayout? = null
    private var vizBars: BarsView? = null
    private var vizParams: WindowManager.LayoutParams? = null
    // One-shot move-hint coachmark (first bubble show ever).
    private var hintView: View? = null
    private var hintParams: WindowManager.LayoutParams? = null
    private val hintHide = Runnable { hideMoveHint() }

    private var downRawX = 0f
    private var downRawY = 0f
    private var baseX = 0
    private var baseY = 0
    private var moved = false
    /** Grip drag-down already cancelled this gesture (fire once). */
    private var gripCancelled = false
    /** Layout built with the optional grip (rebuild when the pref flips). */
    private var builtGrab = false
    /** Pending delayed hold start (no grip: wait out finger jitter). */
    private var pressStart: Runnable? = null
    /** Recording already opened by this touch (hold or deferred tap). */
    private var pressStarted = false
    private var holdControlsReady = false
    private var lastHoldY = 0f
    private var holdOriginY = 0f
    private val revealHoldControls = Runnable {
        if (pressStarted && dictation.current == Dictation.State.RECORDING && !toggleRecording) {
            holdControlsReady = true
            holdOriginY = lastHoldY
            refreshChrome(dictation.current)
            showLockTarget()
            stopBtn?.apply { alpha = 0f; animate().alpha(1f).setDuration(85L).start() }
            lockHolder?.apply { animate().cancel(); alpha = 0f; animate().alpha(1f).setDuration(85L).start() }
            cancelGuide?.apply { alpha = if (lockHolder != null) 0f else 1f }
        }
    }
    /** This touch became a free drag (grip hidden). */
    private var draggingBubble = false
    private var toggleRecording = false
    private var lockTarget: ImageButton? = null
    private var lockTravelDistance = 0f
    private var lockArmed = false
    private var toggleCollapsing = false
    private var toggleReturnOffset = 0f
    private var toggleReturnAnimator: android.animation.ValueAnimator? = null
    private var lockHolder: FrameLayout? = null
    private var lockGuide: View? = null
    private var cancelArmed = false
    private var holdUnstuck = false
    private var holdReturning = false
    private var processingTouch = false
    private var processingAnimator: android.animation.ObjectAnimator? = null
    private var chromeState: Dictation.State? = null
    private var cancelGuide: View? = null
    private var snoozeTarget: android.widget.ImageView? = null
    private var snoozeHolder: FrameLayout? = null
    private var snoozeReady = false
    private var lastDragX = 0f
    private var lastDragY = 0f
    private val revealSnooze = Runnable { if (draggingBubble || moved) showSnoozeTarget() }
    private var snoozeArmed = false
    private val snoozeEnd = Runnable { resumeBubble() }

    private fun snoozed(): Boolean = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getLong("float_snoozed_until", 0) > System.currentTimeMillis()

    private fun resumeBubble() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { remove("float_snoozed_until") }
        mainHandler.removeCallbacks(snoozeEnd)
        getSystemService(NotificationManager::class.java)?.cancel(45)
        applyVisibility()
    }

    private fun snoozeBubble() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val minutes = prefs.getInt("float_snooze_minutes", 10).coerceIn(1, 120)
        prefs.edit { putLong("float_snoozed_until", System.currentTimeMillis() + minutes * 60000L) }
        mainHandler.removeCallbacks(snoozeEnd)
        mainHandler.postDelayed(snoozeEnd, minutes * 60000L)
        removeBubble(animate = true)
        val resume = PendingIntent.getService(this, 45,
            Intent(this, OverlayService::class.java).setAction(ACTION_UNSNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java)?.notify(45,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(getString(R.string.float_snoozed, minutes))
                .setContentText(getString(R.string.float_resume)).setContentIntent(resume)
                .addAction(R.drawable.ic_mic, getString(R.string.float_resume), resume).build())
    }

    private fun showLockTarget() {
        if (lockTarget != null || !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("float_lock_enabled", true)) return
        val mic = micBtn ?: return
        val loc = IntArray(2)
        mic.getLocationOnScreen(loc)
        val targetSize = dp(32)
        val window = WindowManager.LayoutParams(dp(50), dp(80),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = params?.x ?: loc[0]
                val startY = (params?.y ?: loc[1]) + (if (builtGrab) dp(14) else 0) + dp(4)
                y = (startY - dp(80)).coerceAtLeast(safeBounds(targetSize, targetSize).minY)
                height = (startY - y).coerceAtLeast(targetSize) + dp(146)
                lockTravelDistance = (startY + dp(25) - y - targetSize / 2f).coerceAtLeast(dp(24).toFloat())
            }
        val target = ImageButton(this).apply {
            setImageResource(R.drawable.ic_float_lock)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC262626.toInt()) }
            contentDescription = getString(R.string.float_lock)
        }
        val root = FrameLayout(this)
        // Both segments share a single canvas spanning the entire hold gesture.
        // Their endpoints derive from the same moving button bounds.
        val guide = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = dp(2).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val buttonTop = lockTravelDistance - dp(9) + (micBtn?.translationY ?: 0f)
                val buttonBottom = buttonTop + dp(50)
                val x = width / 2f
                paint.color = if (lockArmed) Color.WHITE else 0x66FFFFFF
                if (buttonTop > targetSize) canvas.drawLine(x, targetSize.toFloat(), x, buttonTop, paint)
                val cancelTop = lockTravelDistance - dp(9) + dp(98)
                paint.color = if (cancelArmed) 0xFFD34343.toInt() else 0x66FFFFFF
                if (buttonBottom < cancelTop) canvas.drawLine(x, buttonBottom, x, cancelTop, paint)
            }
        }
        root.addView(guide, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(target, FrameLayout.LayoutParams(targetSize, targetSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        try { wm?.addView(root, window); lockTarget = target; lockHolder = root; lockGuide = guide; cancelGuide?.alpha = 0f } catch (_: Exception) { }
    }

    private fun hideLockTarget() {
        lockHolder?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        lockTarget = null
        lockHolder = null
        lockGuide = null
        cancelGuide?.alpha = 1f
        lockArmed = false
    }

    private fun syncCancelGuide() {
        val travel = micBtn?.translationY ?: 0f
        // Guide spans the gap from the moving button's bottom to the fixed target's top.
        // Keep its layout space stable so the cancel target never moves.
        val gap = dp(48).toFloat()
        lockGuide?.invalidate()
        cancelGuide?.alpha = if (lockHolder != null) 0f else 1f
        cancelGuide?.apply {
            pivotY = 0f
            translationY = travel.coerceAtMost(gap)
            scaleY = ((gap - travel) / gap).coerceAtLeast(0f)
        }
    }

    private fun holdTravel(dy: Float) {
        val upward = dy < 0 && lockTarget != null
        val distance = FloatState.holdGestureDistance(dy, lockTarget != null)
        val wasUnstuck = holdUnstuck
        holdUnstuck = FloatState.magnetReleased(distance, holdUnstuck, dp(18).toFloat(), dp(10).toFloat())
        if (wasUnstuck != holdUnstuck) tick(8L, 35)
        val limit = if (upward) lockTravelDistance else dp(89).toFloat()
        val wasArmed = if (upward) lockArmed else cancelArmed
        val armed = holdUnstuck && FloatState.magnetCaptured((limit - distance).coerceAtLeast(0f), wasArmed,
            dp(20).toFloat(), dp(34).toFloat())
        val travel = (if (!holdUnstuck) 0f else if (armed) limit else distance.coerceAtMost(limit)) * (if (upward) -1 else 1)
        val nextLock = upward && armed
        val nextCancel = !upward && armed
        if (nextLock != lockArmed) tick(if (nextLock) 18L else 10L, if (nextLock) 100 else 45)
        lockArmed = nextLock
        lockTarget?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (lockArmed) 0xFF555555.toInt() else 0xCC262626.toInt())
        }
        lockTarget?.scaleX = if (lockArmed) 1.15f else 1f
        lockTarget?.scaleY = if (lockArmed) 1.15f else 1f
        lockGuide?.invalidate()
        micBtn?.animate()?.withEndAction(null)?.cancel()
        holdReturning = false
        micBtn?.translationY = travel
        syncCancelGuide()
        syncHoldVizPosition()
        if (nextCancel != cancelArmed) tick(if (nextCancel) 18L else 10L, if (nextCancel) 100 else 45)
        cancelArmed = nextCancel
        stopBtn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (cancelArmed) 0xFFD34343.toInt() else 0xCC262626.toInt())
        }
        cancelGuide?.setBackgroundColor(if (cancelArmed) 0xFFD34343.toInt() else 0x66FFFFFF)
        stopBtn?.scaleX = if (cancelArmed) 1.15f else 1f
        stopBtn?.scaleY = if (cancelArmed) 1.15f else 1f
    }

    private fun resetHoldTravel() {
        val mic = micBtn
        if (mic != null && mic.translationY != 0f && !holdReturning) {
            holdReturning = true
            mic.animate().translationY(0f).setDuration(100L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setUpdateListener { syncCancelGuide(); syncHoldVizPosition() }
                .withEndAction {
                    holdReturning = false
                    if (micBtn === mic) refreshChrome(dictation.current)
                }.start()
        }
        holdUnstuck = false
        lockArmed = false
        cancelArmed = false
        stopBtn?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC262626.toInt())
        }
        cancelGuide?.setBackgroundColor(0x66FFFFFF)
        syncCancelGuide()
        stopBtn?.scaleX = 1f
        stopBtn?.scaleY = 1f
    }

    private fun beginSnoozeDrag() {
        params?.let { dragVisualX = it.x.toFloat(); dragVisualY = it.y.toFloat() }
        mainHandler.removeCallbacks(revealSnooze)
        mainHandler.postDelayed(revealSnooze, 300L)
    }

    private fun showSnoozeTarget() {
        if (snoozeTarget != null) return
        val target = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_float_snooze)
            contentDescription = getString(R.string.float_snooze_target)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            alpha = 0f
            translationY = dp(112).toFloat()
        }
        val root = FrameLayout(this).apply {
            addView(target, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(dp(160), dp(160),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(32) }
        try {
            wm?.addView(root, lp)
            snoozeTarget = target
            snoozeHolder = root
            target.animate().translationY(0f).alpha(0.8f).setDuration(160L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    if (snoozeTarget === target) {
                        snoozeReady = true
                        trackSnooze(lastDragX, lastDragY)
                    }
                }.start()
        } catch (_: Exception) { }
    }

    private var dragFramePending = false
    private var lastDragFrameNanos = 0L
    private var dragTargetX = 0f
    private var dragTargetY = 0f
    private var dragVisualX = 0f
    private var dragVisualY = 0f
    private var capturedX = 0f
    private var capturedY = 0f
    private val dragFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            dragFramePending = false
            val lp = params ?: return
            val root = holder ?: return
            val dt = if (lastDragFrameNanos == 0L) 1f / 60f
                else ((frameTimeNanos - lastDragFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastDragFrameNanos = frameTimeNanos
            val blend = (1.0 - kotlin.math.exp(-30.0 * dt)).toFloat()
            dragVisualX += (dragTargetX - dragVisualX) * blend
            dragVisualY += (dragTargetY - dragVisualY) * blend
            val settled = kotlin.math.abs(dragTargetX - dragVisualX) < 0.5f &&
                kotlin.math.abs(dragTargetY - dragVisualY) < 0.5f
            if (settled) { dragVisualX = dragTargetX; dragVisualY = dragTargetY }
            lp.x = dragVisualX.toInt()
            lp.y = dragVisualY.toInt()
            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) { stopDragMotion(); return }
            if (!settled) scheduleDragFrame()
        }
    }

    private fun scheduleDragFrame() {
        if (!dragFramePending) {
            dragFramePending = true
            android.view.Choreographer.getInstance().postFrameCallback(dragFrame)
        }
    }

    private fun stopDragMotion() {
        android.view.Choreographer.getInstance().removeFrameCallback(dragFrame)
        dragFramePending = false
        lastDragFrameNanos = 0L
    }

    private fun trackSnooze(x: Float, y: Float) {
        lastDragX = x
        lastDragY = y
        val target = snoozeTarget
        if (target == null || !snoozeReady) {
            val lp = params ?: return
            val bounds = safeBubbleBounds(lp.width, lp.height)
            val free = FloatState.clampBubble(baseX + (x - downRawX).toInt(), baseY + (y - downRawY).toInt(),
                bounds.minX, bounds.minY, bounds.maxX, bounds.maxY)
            dragTargetX = free.first.toFloat()
            dragTargetY = free.second.toFloat()
            scheduleDragFrame()
            return
        }
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val targetX = loc[0] + target.width / 2f
        val targetY = loc[1] + target.height / 2f
        val distance = kotlin.math.hypot((x - targetX).toDouble(), (y - targetY).toDouble()).toFloat()
        val armed = FloatState.magnetCaptured(distance, snoozeArmed, dp(64).toFloat(), dp(96).toFloat())
        val lp = params ?: return
        if (armed && !snoozeArmed) {
            val mic = micBtn ?: return
            val micLoc = IntArray(2)
            mic.getLocationOnScreen(micLoc)
            capturedX = lp.x + targetX - micLoc[0] - mic.width / 2f
            capturedY = lp.y + targetY - micLoc[1] - mic.height / 2f
        }
        // One position owner: capture chooses a fixed destination; finger motion
        // only updates the free destination. The frame callback is the only writer.
        if (armed) {
            dragTargetX = capturedX
            dragTargetY = capturedY
        } else {
            val bounds = safeBubbleBounds(lp.width, lp.height)
            val free = FloatState.clampBubble(baseX + (x - downRawX).toInt(), baseY + (y - downRawY).toInt(),
                bounds.minX, bounds.minY, bounds.maxX, bounds.maxY)
            dragTargetX = free.first.toFloat()
            dragTargetY = free.second.toFloat()
        }
        scheduleDragFrame()
        val changed = armed != snoozeArmed
        if (changed) tick(12L, 60)
        snoozeArmed = armed
        if (changed) {
            target.animate().alpha(if (armed) 1f else 0.65f).scaleX(if (armed) 1.08f else 1f).scaleY(if (armed) 1.08f else 1f)
                .setDuration(75L).start()
        }
    }

    private fun hideSnoozeTarget() {
        mainHandler.removeCallbacks(revealSnooze)
        stopDragMotion()
        snoozeTarget?.animate()?.withEndAction(null)?.cancel()
        snoozeHolder?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        snoozeTarget = null
        snoozeHolder = null
        snoozeReady = false
        snoozeArmed = false
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Short haptic tick (press / release / toggle). */
    private fun tick(ms: Long = 25L, amplitude: Int = android.os.VibrationEffect.DEFAULT_AMPLITUDE) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vib?.vibrate(
                android.os.VibrationEffect.createOneShot(ms, amplitude),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Visibility watchdog: accessibility events miss focus loss
     * (tapping dead space, keyboard dismiss, app switch), leaving
     * the bubble stuck. Window events handle normal changes; re-check every 250 ms as fallback.
     */
    private val watchTick = object : Runnable {
        override fun run() {
            // Both directions: a missed focus event (IME window wins
            // the race) must not leave the bubble stuck either way.
            try {
                MyWhisperAccessibility.syncFocus()
            } catch (_: Exception) {
            }
            mainHandler.postDelayed(this, 100)
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
            NotificationChannel(CHANNEL, getString(R.string.unified_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // Drop the retired solo channels (pre-unification installs).
        try {
            val nm = getSystemService(NotificationManager::class.java)
            for (id in NotifPolicy.LEGACY_CHANNEL_IDS) nm?.deleteNotificationChannel(id)
        } catch (_: Exception) {
        }
        dictation = Dictation(
            smartTapMs = { readLong("hotkeys", "smart_tap_ms", 150L) },
            mode = { readMode() },
            releaseDelayMs = { readLong("stt", "release_delay_ms", 0L) },
            // On-device first (moonshine-v2 pack, VAD-chunked); miss or
            // throw → server chain. Pack-gated: null without the pack.
            localTranscribe = { pcm -> LocalStt.transcribeIfEnabled(this, pcm) },
            // Shutdown releases the native recognizer (no resident ORT
            // session across service restarts in one process).
            releaseLocal = {
                try { LocalStt.unload() } catch (_: Throwable) {}
                try { LocalLlmManager.releaseLocalEngines() } catch (_: Throwable) {}
            },
        )
        dictation.listener = this
        // Live peaks feed the bottom-center visualizer pill (capture
        // thread → posted to the bars view; no-op when hidden).
        dictation.onLevel = { peak ->
            try {
                vizBars?.post { vizBars?.pushLevel(peak) }
            } catch (_: Exception) {
            }
        }
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

    /** Optional grip (default off) and the no-grip hold delay. */
    private fun grabEnabled(): Boolean = try {
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GRAB, false)
    } catch (_: Exception) {
        false
    }

    private fun pressDelayMs(): Int = try {
        FloatState.pressDelayMs(
            getSharedPreferences(PREFS, MODE_PRIVATE).getInt(
                KEY_PRESS_DELAY, FloatState.PRESS_DELAY_DEFAULT_MS,
            ),
        )
    } catch (_: Exception) {
        FloatState.PRESS_DELAY_DEFAULT_MS
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
            ACTION_UNSNOOZE -> {
                resumeBubble()
                if (foregrounded) return START_STICKY
                return onStartCommand(Intent(this, OverlayService::class.java).setAction(ACTION_START), flags, startId)
            }
            ACTION_STOP -> {
                teardownFloat()
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
                    teardownFloat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                FloatState.clearOverlayDenied()
                // Publish before foregrounding so the shared builder sees us.
                NotifPolicy.UnifiedState.floatActive = true
                NotifPolicy.UnifiedState.floatState =
                    if (::dictation.isInitialized) dictation.current else Dictation.State.IDLE
                try {
                    startForegroundWithType()
                } catch (e: Exception) {
                    // Background start denied (boot redelivery): never run
                    // un-foregrounded — the system kills us ~10s later.
                    android.util.Log.i("MwFloat", "fg denied, stopping: ${e.message}")
                    teardownFloat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureCore()
                dictation.listener = this
                foregrounded = true
                FloatState.onChange = { applyVisibility() }
                applyVisibility()
                mainHandler.removeCallbacks(watchTick)
                mainHandler.postDelayed(watchTick, 100)
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
        NotifPolicy.UnifiedState.floatState = s
        val notif = buildNotif(s)
        // Drop the retired solo bubble row (pre-unification installs).
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
        } catch (_: Exception) {
        }
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
     * SHARED unified-row builder (same channel + ID + spec as
     * SyncService): bubble wins while active (RECORDING/WORKING), else
     * sync paints when alive, else the lone idle bubble. Self counts as
     * alive (the builder runs foregrounded); the peer comes from
     * [NotifPolicy.UnifiedState]. STOP stops both (SyncService STOP_ALL),
     * tap opens Setup (MainActivity lands on Setup), BigTextStyle keeps
     * the actions expanded, per-mode prefs via [NotifPolicy].
     */
    internal fun buildNotif(state: Dictation.State): Notification {
        val prefsObj = readPrefsObj()
        val floatPrefs = NotifPolicy.floatPrefsJson(prefsObj)
        val syncPrefs = NotifPolicy.syncPrefsJson(prefsObj)
        val spec = NotifPolicy.forUnified(
            floatActive = true,
            floatState = state,
            floatPrefs = floatPrefs,
            syncActive = NotifPolicy.UnifiedState.syncActive,
            syncing = NotifPolicy.UnifiedState.syncing,
            syncPrefs = syncPrefs,
        ) ?: NotifPolicy.UnifiedSpec(
            NotifPolicy.UnifiedChrome.FLOAT_IDLE,
            ongoing = floatPrefs.idleOngoing,
            showProgress = false,
            showSyncNow = false,
        )
        val stopIntent = PendingIntent.getService(
            this, 10,
            Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nowIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SyncService::class.java).setAction(SyncService.ACTION_SYNC_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (spec.chrome) {
            NotifPolicy.UnifiedChrome.FLOAT_RECORDING -> getString(R.string.float_notif_rec_text)
            NotifPolicy.UnifiedChrome.FLOAT_WORKING -> getString(R.string.ime_working)
            NotifPolicy.UnifiedChrome.FLOAT_IDLE -> getString(R.string.float_notif_text)
            NotifPolicy.UnifiedChrome.SYNCING -> getString(R.string.notif_sync_working_text)
            NotifPolicy.UnifiedChrome.SYNC_IDLE -> getString(R.string.notif_sync_text)
        }
        val title = when (spec.chrome) {
            NotifPolicy.UnifiedChrome.FLOAT_RECORDING,
            NotifPolicy.UnifiedChrome.FLOAT_WORKING,
            NotifPolicy.UnifiedChrome.FLOAT_IDLE -> getString(R.string.float_notif_title)
            NotifPolicy.UnifiedChrome.SYNCING,
            NotifPolicy.UnifiedChrome.SYNC_IDLE -> getString(R.string.notif_sync_title)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentIntent(openIntent)
            .setOngoing(spec.ongoing)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
        if (spec.showSyncNow) {
            b.addAction(android.R.drawable.ic_popup_sync, getString(R.string.notif_sync_now), nowIntent)
        }
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

    /** Refresh the shared foreground row for the live state (no stale mode). */
    private fun refreshNotif(state: Dictation.State) {
        try {
            NotifPolicy.UnifiedState.floatState = state
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
     * Shared teardown for every float death path (STOP, overlay-denied
     * START, addView denial, FGS denial): detach first so in-flight
     * Dictation.finish set(IDLE) can't refreshNotif an ongoing row with
     * no live service behind it, drop the bubble + state, remove the
     * shared row, and hand it back to sync when alive (recompose, not
     * orphan — a bare stopSelf would leave stale float chrome while
     * sync lives). Every step best-effort; callers stopSelf afterwards.
     * Executes [floatTeardownPlan] field by field (JVM-tested matrix).
     */
    private fun teardownFloat() {
        val plan = floatTeardownPlan(NotifPolicy.UnifiedState.syncActive)
        if (plan.detachListener) {
            try {
                if (::dictation.isInitialized) dictation.listener = null
            } catch (_: Exception) {
            }
        }
        if (plan.removeBubble) removeBubble()
        if (plan.clearState) clearFloatState()
        foregrounded = false
        if (plan.resetUnifiedState) {
            NotifPolicy.UnifiedState.floatActive = false
            NotifPolicy.UnifiedState.floatState = Dictation.State.IDLE
        }
        if (plan.removeSharedRow) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            try {
                getSystemService(NotificationManager::class.java)?.cancel(NotifPolicy.LEGACY_FLOAT_NOTIF_ID)
            } catch (_: Exception) {
            }
        }
        if (plan.recomposeSync) {
            try {
                SyncService.refreshNotif(this)
            } catch (_: Exception) {
            }
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
            s.optJSONObject("preferences")?.optBoolean("float_enabled", true) == true
        } catch (_: Exception) {
            false
        }
        android.util.Log.i("MwFloat", "apply on=$on ime=${FloatState.imeActive} focus=${FloatState.hasTextFocus}")
        if (!on || snoozed()) {
            if (snoozed()) {
                mainHandler.removeCallbacks(snoozeEnd)
                mainHandler.postDelayed(snoozeEnd, (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getLong("float_snoozed_until", 0) - System.currentTimeMillis()).coerceAtLeast(1))
            }
            removeBubble()
            return
        }
        val show = FloatState.shouldShowBubble(
            floatEnabled = true,
            imeActive = FloatState.imeActive,
            a11yOn = MyWhisperAccessibility.isOn(this),
            hasTextFocus = FloatState.hasTextFocus,
            followKeyboard = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("float_follow_keyboard", true),
            keyboardVisible = FloatState.keyboardVisible,
        )
        android.util.Log.i("MwFloat", "apply show=$show holder=${holder != null}")
        // Grip pref flipped while the bubble is up: rebuild the chrome so
        // the layout matches (no grip row <=> free drag).
        if (holder != null && builtGrab != grabEnabled()) {
            removeBubble()
        }
        if (show) {
            if (holder == null) showBubble() else refreshChrome(dictation.current)
        } else {
            removeBubble(animate = true)
        }
    }

    /** Compact monochrome squircle with an optional full-width grip. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (holder != null) {
            refreshChrome(Dictation.State.IDLE)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val size = dp(50)
        val haloPad = 0
        val cellW = size + haloPad * 2
        val gripTouch = size
        val stopSize = dp(STOP_VISUAL_DP)
        val stopCell = dp(96)
        val grab = grabEnabled()
        // Grip off (default): no grip row at all — the mic itself drags,
        // and the window shrinks so no dead touch strip sits on top.
        val gripCell = if (grab) dp(12) else 0
        val rootW = cellW
        val rootH = gripCell + cellW + dp(8)
        val lp = WindowManager.LayoutParams(
            rootW, rootH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            val b = safeBubbleBounds(rootW, rootH)
            val (cx, cy) = FloatState.clampBubble(
                prefs.getInt(KEY_X, 60), prefs.getInt(KEY_Y, 400),
                b.minX, b.minY, b.maxX, b.maxY,
            )
            it.x = cx
            it.y = cy
        }
        params = lp

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            // Keep overlay children at fixed bounds; animate their alpha/translation instead.

        }
        // Optional full-width grip, aligned with the squircle sides.
        if (grab) {
            val grip = FrameLayout(this).apply {
                contentDescription = getString(R.string.float_drag_handle)
                background = null
                layoutParams = android.widget.LinearLayout.LayoutParams(gripTouch, dp(12)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                }
                setOnTouchListener { _, ev -> onHandleTouch(ev) }
            }
            val pill = View(this).apply {
                isClickable = false
                isFocusable = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(0x8CFFFFFF.toInt())
                }
                layoutParams = FrameLayout.LayoutParams(dp(28), dp(4), Gravity.CENTER)
            }
            grip.addView(pill)
            dragHandle = grip
            root.addView(grip)
        }
        builtGrab = grab
        // Compact mic cell.
        val micCell = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = android.widget.LinearLayout.LayoutParams(cellW, cellW).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
        }
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = getString(R.string.ime_mic)
            // Small white mic centered in the squircle.
            setPadding(dp(11), dp(11), dp(11), dp(11))
            elevation = 0f
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setOnTouchListener { _, ev -> onMicTouch(ev) }
        }
        micBtn = mic
        micCell.addView(mic)
        root.addView(micCell)
        // Stop BELOW the mic: same column, centered (symmetric on both
        // edges). Always visible while RECORDING or WORKING, hidden
        // while IDLE. Tap discards (never commits — owner pill round-2).
        val guide = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(2), dp(48))
            setBackgroundColor(0x66FFFFFF)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cancelGuide = guide
        root.addView(guide)
        val stop = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            contentDescription = getString(R.string.float_stop)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC262626.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(7).toFloat()
            layoutParams = android.widget.LinearLayout.LayoutParams(stopSize, stopSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 0
            }
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
        root.pivotX = rootW / 2f
        root.pivotY = gripCell + dp(4) + size / 2f
        root.alpha = 0f
        root.scaleX = 0.96f
        root.scaleY = 0.96f
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
            teardownFloat()
            stopSelf()
            return
        }
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(100L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // First-run move-hint coachmark: once ever (seen-flag
        // persists at show time, so it can never replay).
        maybeShowMoveHint()
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
                    b.left + ins.left + dp(12),
                    b.top + ins.top,
                    b.right - ins.right - bubbleW - dp(12),
                    b.bottom - ins.bottom - bubbleH,
                )
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                SafeBounds(dp(12), 0, dm.widthPixels - bubbleW - dp(12), dm.heightPixels - bubbleH)
            }
        } catch (_: Exception) {
            SafeBounds(0, 0, 0, 0)
        }
    }

    /** Reserve room before capture so Android never refits the hold window under the finger. */
    private fun safeBubbleBounds(width: Int, height: Int): SafeBounds =
        safeBounds(width, maxOf(height, dp(FloatState.recordingWindowHeightDp(grabEnabled()))))

    private fun completeBubbleDrag(x: Float, y: Float): Boolean {
        trackSnooze(x, y)
        val snooze = snoozeArmed
        if (!snooze) params?.let { lp ->
            val bounds = safeBubbleBounds(lp.width, lp.height)
            val final = FloatState.clampBubble(dragTargetX.toInt(), dragTargetY.toInt(),
                bounds.minX, bounds.minY, bounds.maxX, bounds.maxY)
            lp.x = final.first
            lp.y = final.second
            try { wm?.updateViewLayout(holder, lp) } catch (_: Exception) { }
        }
        hideSnoozeTarget()
        return snooze
    }

    /** Dock to the nearest horizontal edge, clamp, persist. */
    private fun snapAndPersist() {
        params?.let { lp ->
            val b = safeBubbleBounds(lp.width, lp.height)
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
     * Mic touch. Two modes:
     *
     * Grip on: the handle is the only move surface — the mic talks
     * immediately and a release ON the X discards.
     *
     * Grip off (default): the mic itself drags. A finger that stays
     * inside the touch-slop margin for [pressDelayMs] opens dictation
     * (hold semantics; Smart/Tap keep their toggle behavior). Moving
     * past the margin before that window means the touch was a drag:
     * dictation never opens, the bubble follows the finger, and release
     * docks it to the edge. Moving while already recording keeps the
     * same drag (release still finishes unless dropped on the X).
     * Inside the margin, jitter is involuntary and ignored.
     */
    private fun onMicTouch(ev: MotionEvent): Boolean {
        val grab = grabEnabled()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && dictation.current == Dictation.State.WORKING) {
            processingTouch = true
            tick()
            dictation.cancel()
            return true
        }
        if (processingTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) processingTouch = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastHoldY = ev.rawY
                holdOriginY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                pressStarted = false
                draggingBubble = false
                cancelPendingPress()
                if (grab) {
                    tick()
                    dictation.pressDown()
                    pressStarted = true
                } else {
                    // Give the finger a moment to prove it is not a drag.
                    val start = Runnable {
                        pressStart = null
                        if (!moved && !draggingBubble) {
                            tick()
                            dictation.pressDown()
                            pressStarted = true
                        }
                    }
                    pressStart = start
                    mainHandler.postDelayed(start, pressDelayMs().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastHoldY = ev.rawY
                if (pressStarted && dictation.current == Dictation.State.RECORDING && !toggleRecording) {
                    if (!holdControlsReady) return true
                    holdTravel(ev.rawY - holdOriginY)
                    return true
                }
                if (grab || pressStarted) return true
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (!draggingBubble && FloatState.shouldArmFreeMove(dist, dp(6).toFloat())) {
                    // Past the deadzone: this is a drag, never a talk press.
                    draggingBubble = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    tick(8L, 35)
                    beginSnoozeDrag()
                    moved = true
                    cancelPendingPress()
                }
                if (draggingBubble) {
                    trackSnooze(ev.rawX, ev.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingPress()
                val dragged = draggingBubble
                draggingBubble = false
                moved = false
                if (dragged) {
                    val snooze = completeBubbleDrag(ev.rawX, ev.rawY)
                    if (snooze) { tick(25L, 100); snoozeBubble(); return true }
                    tick(18L, 100)
                    snapAndPersist()
                    if (pressStarted && droppedOnStop(ev.rawX, ev.rawY)) {
                        tick(40L)
                        dictation.cancel()
                    } else if (pressStarted) {
                        tick()
                        dictation.pressUp()
                    }
                    pressStarted = false
                    return true
                }
                if (!pressStarted) {
                    // Lifted inside the hold window: a tap/toggle press.
                    tick()
                    dictation.pressDown()
                    dictation.pressUp()
                    return true
                }
                pressStarted = false
                mainHandler.removeCallbacks(revealHoldControls)
                val cancelHold = holdControlsReady && cancelArmed
                val lockHold = holdControlsReady && lockArmed
                if (lockHold) toggleReturnOffset = micBtn?.translationY ?: 0f
                lockArmed = false
                resetHoldTravel()
                if (lockHold) {
                    tick(20L, 100)
                    dictation.lockRecording()
                    return true
                }
                if (cancelHold || (holdControlsReady && droppedOnStop(ev.rawX, ev.rawY))) {
                    // Drag-to-X: discard; the session token invalidates
                    // any in-flight finish so no WORKING follows.
                    tick(40L)
                    dictation.cancel()
                } else {
                    tick()
                    dictation.pressUp()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingPress()
                mainHandler.removeCallbacks(revealHoldControls)
                holdControlsReady = false
                resetHoldTravel()
                hideSnoozeTarget()
                draggingBubble = false
                moved = false
                pressStarted = false
                dictation.cancel()
                return true
            }
        }
        return false
    }

    /** Drop a scheduled hold start (finger moved / lifted / cancelled). */
    private fun cancelPendingPress() {
        pressStart?.let { mainHandler.removeCallbacks(it) }
        pressStart = null
    }

    /** True when the release point lands on the stop button (drop-on-X). */
    private fun droppedOnStop(rawX: Float, rawY: Float): Boolean {
        val stop = stopBtn ?: return false
        if (stop.visibility != View.VISIBLE) return false
        return try {
            val loc = IntArray(2)
            stop.getLocationOnScreen(loc)
            val pad = (dp(STOP_TARGET_DP) - dp(STOP_VISUAL_DP)) / 2
            FloatState.isDropOnCancel(
                rawX, rawY,
                FloatState.HitRectF(
                    (loc[0] - pad).toFloat(), (loc[1] - pad).toFloat(),
                    (loc[0] + stop.width + pad).toFloat(), (loc[1] + stop.height + pad).toFloat(),
                ),
            )
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Grip touch: move, plus drag-DOWN cancel (mirrors the IME
     * slide-down-cancel): pulling the grip down past threshold while
     * active discards the utterance — same invalidate path as the X,
     * so no WORKING spinner and no commit follow. Sideways/up drags
     * still move; release docks to the edge and marks the first-run
     * hint seen. Never Dictation input.
     */
    private fun onHandleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                gripCancelled = false
                tick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gripCancelled && FloatState.shouldCancelOnGripDragDown(
                        ev.rawY - downRawY, dp(GRIP_CANCEL_DY_DP).toFloat(),
                        active = dictation.current != Dictation.State.IDLE,
                    )
                ) {
                    gripCancelled = true
                    tick(40L)
                    dictation.cancel()
                    return true
                }
                if (gripCancelled) return true
                val dist = kotlin.math.hypot(
                    (ev.rawX - downRawX).toDouble(),
                    (ev.rawY - downRawY).toDouble(),
                ).toFloat()
                if (!moved && FloatState.shouldArmMove(dist, dp(6).toFloat(), onHandle = true)) {
                    moved = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    tick(8L, 35)
                    beginSnoozeDrag()
                }
                if (moved) {
                    trackSnooze(ev.rawX, ev.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    val snooze = completeBubbleDrag(ev.rawX, ev.rawY)
                    if (snooze) { tick(25L, 100); snoozeBubble(); return true }
                    tick(18L, 100)
                    snapAndPersist()
                    moved = false
                }
                gripCancelled = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideSnoozeTarget()
                moved = false
                gripCancelled = false
                return true
            }
        }
        return false
    }

    /**
     * Explicit cancel tap (owner pill round-2: CANCEL MUST NOT
     * TRANSCRIBE): the X below the mic discards in every active mode —
     * RECORDING cancels (never finish/commit, never a WORKING
     * spinner, never an empty-text commit), WORKING cancels
     * (discard). Committing from the bubble happens only via the mic
     * release. IDLE is unreachable (button hidden) and no-ops.
     */
    private fun onStopTap() {
        tick()
        if (FloatState.stopAction(dictation.current) != FloatState.StopAction.NONE) {
            dictation.cancel()
        }
    }

    private fun removeBubble(animate: Boolean = false) {
        vizMorphAnimator?.cancel()
        vizMorphAnimator = null
        mainHandler.removeCallbacks(revealHoldControls)
        holdControlsReady = false
        hideLockTarget()
        micBtn?.animate()?.withEndAction(null)?.cancel()
        micBtn?.translationY = 0f
        holdReturning = false
        resetHoldTravel()
        hideSnoozeTarget()
        // Any hide drops the utterance (focus lost mid-press included).
        moved = false
        gripCancelled = false
        cancelPendingPress()
        draggingBubble = false
        pressStarted = false
        try {
            dictation.cancel()
        } catch (_: Exception) {
        }
        hideVizPill()
        hideMoveHint()
        holder?.let { outgoing ->
            outgoing.animate().cancel()
            if (animate) {
                // Focus is gone: stop intercepting touches before the exit finishes.
                params?.let { lp ->
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try { wm?.updateViewLayout(outgoing, lp) } catch (_: Exception) { }
                }
                outgoing.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f)
                    .setDuration(60L)
                    .withEndAction {
                        try { wm?.removeView(outgoing) } catch (_: Exception) { }
                    }.start()
            } else {
                try { wm?.removeView(outgoing) } catch (_: Exception) { }
            }
        }
        holder = null
        haloView = null
        micBtn = null
        dragHandle = null
        stopBtn = null
        spinner = null
        processingAnimator?.cancel()
        processingAnimator = null
        chromeState = null
    }

    /** Flat monochrome states; opacity affects the surface, keeping the mic legible. */
    private fun refreshChrome(state: Dictation.State) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("float_lock_enabled", true)) hideLockTarget()
        val processing = FloatState.isProcessing(state)
        val stateChanged = chromeState != state
        if (stateChanged) {
            processingAnimator?.cancel()
            processingAnimator = null
            micBtn?.imageAlpha = 255
            chromeState = state
        }
        val opacity = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt("float_opacity", 65).coerceIn(30, 100)
        micBtn?.apply {
            val nextBackground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(if (state == Dictation.State.RECORDING) 0xFFE8E8E8.toInt() else Color.WHITE)
                alpha = opacity * 255 / 100
            }
            backgroundTintList = null
            background = nextBackground
            minimumWidth = 0
            minimumHeight = 0
            adjustViewBounds = false
            val logo = true
            setImageResource(if (processing) R.drawable.ic_float_processing else if (logo) R.drawable.ic_stat_sync else R.drawable.ic_mic)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val iconPadding = dp(if (logo) 10 else 11)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            alpha = if (state == Dictation.State.RECORDING && !toggleRecording) 0f else 1f
        }
        haloView?.visibility = View.GONE
        dragHandle?.visibility = if (state == Dictation.State.RECORDING) View.INVISIBLE else View.VISIBLE
        if (stateChanged) {
            if (processing) {
                micBtn?.contentDescription = getString(R.string.float_cancel_processing)
                processingAnimator = android.animation.ObjectAnimator.ofInt(micBtn, "imageAlpha", 100, 255).apply {
                    duration = 450L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            } else {
                micBtn?.contentDescription = getString(R.string.ime_mic)
                processingAnimator = android.animation.ObjectAnimator.ofInt(micBtn, "imageAlpha", 160, 255).apply {
                    duration = 90L
                    start()
                }
            }
        }
        cancelGuide?.visibility = if (state == Dictation.State.RECORDING && !toggleRecording) {
            if (holdControlsReady) View.VISIBLE else View.INVISIBLE
        } else View.GONE
        if (stateChanged && state != Dictation.State.RECORDING) resetHoldTravel()
        stopBtn?.visibility =
            if (FloatState.isStopVisible(state) && !toggleRecording) {
                if (holdControlsReady) View.VISIBLE else View.INVISIBLE
            } else View.GONE
        params?.let { lp ->
            val height = dp(50 + 8 + (if (builtGrab) 14 else 0) +
                (if ((state == Dictation.State.RECORDING && !toggleRecording) || holdReturning) 96 else 0))
            if (lp.height != height) {
                lp.height = height
                try { wm?.updateViewLayout(holder, lp) } catch (_: Exception) { }
            }
        }
    }

    private var vizPrimary: ImageButton? = null
    private var vizCancel: ImageButton? = null
    private var vizDockedLeft = false
    private var vizVisibleWidth = 0f
    private var vizMorphAnimator: android.animation.ValueAnimator? = null
    private var vizBuiltToggle = false

    /** One persistent window and one width animation for every recording state. */
    private fun morphRecordingBar(width: Float, finished: (() -> Unit)? = null) {
        val pill = vizHolder ?: return
        vizMorphAnimator?.cancel()
        vizMorphAnimator = android.animation.ValueAnimator.ofFloat(vizVisibleWidth, width).apply {
            duration = 120L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                vizVisibleWidth = it.animatedValue as Float
                val full = dp(togglePillWidthDp())
                val visible = vizVisibleWidth.toInt()
                pill.clipBounds = android.graphics.Rect(if (vizDockedLeft) 0 else full - visible,
                    0, if (vizDockedLeft) visible else full, dp(52))
                pill.invalidate()
            }
            if (finished != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finished() }
            })
            start()
        }
    }

    private fun updateRecordingBarMode() {
        val pill = vizHolder ?: return
        val primary = vizPrimary ?: return
        vizBuiltToggle = toggleRecording
        primary.setImageResource(if (toggleRecording) R.drawable.ic_float_finish else R.drawable.ic_stat_sync)
        primary.contentDescription = getString(if (toggleRecording) R.string.float_finish else R.string.ime_mic)
        val padding = dp(if (toggleRecording) 14 else 10)
        primary.setPadding(padding, padding, padding, padding)
        primary.isClickable = toggleRecording
        vizCancel?.apply {
            isClickable = toggleRecording
            importantForAccessibility = if (toggleRecording) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            animate().alpha(if (toggleRecording) 1f else 0f).setDuration(100L).start()
        }
        vizParams?.let { lp ->
            lp.flags = if (toggleRecording) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm?.updateViewLayout(pill, lp) } catch (_: Exception) { }
        }
        morphRecordingBar(dp(if (toggleRecording) togglePillWidthDp() else recordingCommonWidthDp()).toFloat())
        if (toggleRecording && toggleReturnOffset != 0f) {
            toggleReturnOffset = 0f
            val lp = vizParams ?: return
            val destination = (params?.y ?: lp.y) + (if (builtGrab) dp(14) else 0) + dp(4)
            toggleReturnAnimator?.cancel()
            toggleReturnAnimator = android.animation.ValueAnimator.ofInt(lp.y, destination).apply {
                duration = 120L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    if (vizHolder === pill) {
                        lp.y = it.animatedValue as Int
                        try { wm?.updateViewLayout(pill, lp) } catch (_: Exception) { }
                    }
                }
                start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureVizPill() {
        if (vizHolder != null) {
            if (vizBuiltToggle != toggleRecording) updateRecordingBarMode()
            return
        }
        val bubble = params ?: return
        val bounds = safeBubbleBounds(bubble.width, bubble.height)
        vizDockedLeft = bubble.x - bounds.minX <= bounds.maxX - bubble.x
        val width = dp(togglePillWidthDp())
        val lp = WindowManager.LayoutParams(width, dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (vizDockedLeft) bubble.x else bubble.x + bubble.width - width
            y = bubble.y + (if (builtGrab) dp(14) else 0) + dp(4)
        }
        val pill = object : FrameLayout(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1D1B20.toInt() }
            init { setWillNotDraw(false) }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val left = if (vizDockedLeft) 0f else this.width - vizVisibleWidth
                val right = if (vizDockedLeft) vizVisibleWidth else this.width.toFloat()
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), dp(18).toFloat(), dp(18).toFloat(), paint)
                super.onDraw(canvas)
            }
        }
        val bars = BarsView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(dp(vizBarsContentWidthDp()), dp(VIZ_BARS_H_DP),
                Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(toggleWaveformLeftDp(vizDockedLeft)) }
        }
        pill.addView(bars)
        fun control(icon: Int, primary: Boolean): ImageButton = ImageButton(this).apply {
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.BLACK else Color.WHITE)
            backgroundTintList = null
            background = if (primary) GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) } else null
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val left = if (primary) vizDockedLeft else !vizDockedLeft
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), (if (left) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL)
        }
        val cancel = control(R.drawable.ic_float_cancel, false).apply {
            alpha = 0f
            contentDescription = getString(R.string.float_stop)
            setOnClickListener { tick(); dictation.cancel() }
        }
        val primary = control(R.drawable.ic_stat_sync, true).apply {
            setOnClickListener { if (toggleRecording) { tick(); dictation.pressDown(); dictation.pressUp() } }
        }
        pill.addView(cancel)
        pill.addView(primary)
        vizHolder = pill
        vizParams = lp
        vizBars = bars
        vizPrimary = primary
        vizCancel = cancel
        vizVisibleWidth = dp(52).toFloat()
        pill.clipBounds = android.graphics.Rect(if (vizDockedLeft) 0 else width - dp(52), 0,
            if (vizDockedLeft) dp(52) else width, dp(52))
        try { wm?.addView(pill, lp); updateRecordingBarMode() }
        catch (_: Exception) {
            vizMorphAnimator?.cancel()
            vizHolder = null
            vizBars = null
            vizPrimary = null
            vizCancel = null
            // Losing the interactive recording window must never strand live capture.
            dictation.cancel()
        }
    }

    private fun collapseToggleToBubble() {
        val outgoing = vizHolder ?: return
        val normal = holder ?: return
        toggleReturnAnimator?.cancel()
        toggleCollapsing = true
        vizParams?.let { lp ->
            lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            try { wm?.updateViewLayout(outgoing, lp) } catch (_: Exception) { }
        }
        vizCancel?.animate()?.alpha(0f)?.setDuration(80L)?.start()
        vizBars?.animate()?.alpha(0f)?.setDuration(100L)?.start()
        morphRecordingBar(dp(52).toFloat()) {
            try { wm?.removeView(outgoing) } catch (_: Exception) { }
            if (vizHolder === outgoing) { vizHolder = null; vizBars = null; vizPrimary = null; vizCancel = null }
            toggleCollapsing = false
            if (holder === normal && !toggleRecording) {
                normal.animate().cancel()
                normal.alpha = 1f
                normal.scaleX = 1f
                normal.scaleY = 1f
                normal.visibility = View.VISIBLE
                params?.let { lp ->
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    try { wm?.updateViewLayout(normal, lp) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun syncHoldVizPosition() {
        if (toggleRecording) return
        val root = vizHolder ?: return
        val lp = vizParams ?: return
        val bubble = params ?: return
        lp.y = bubble.y + (if (builtGrab) dp(14) else 0) + dp(4) +
            (micBtn?.translationY ?: 0f).toInt()
        try { wm?.updateViewLayout(root, lp) } catch (_: Exception) { }
    }

    private fun hideVizPill() {
        vizMorphAnimator?.cancel()
        vizMorphAnimator = null
        toggleCollapsing = false
        toggleReturnAnimator?.cancel()
        toggleReturnAnimator = null
        try {
            vizBars?.clear()
        } catch (_: Exception) {
        }
        try {
            vizHolder?.let { outgoing ->
                outgoing.animate().cancel()
                vizParams?.let { lp ->
                    lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                    wm?.updateViewLayout(outgoing, lp)
                }
                outgoing.animate().alpha(0f).setDuration(65L).withEndAction {
                    try { wm?.removeView(outgoing) } catch (_: Exception) { }
                }.start()
            }
        } catch (_: Exception) {
        }
        vizHolder = null
        vizBars = null
    }

    /**
     * One-shot move-hint coachmark (replaces the old repeating Toast —
     * on API 31+ the platform renders service-context Toasts as a
     * floating icon pill, reading as a system bubble tooltip that
     * replayed on every bubble re-show). This is our own overlay pill
     * anchored to the bubble: shown once ever (the seen-flag persists
     * BEFORE addView, so a failed show, an early teardown, or a missed
     * tap can never replay it), tap or outside-tap dismisses, a timeout
     * dismisses, and hiding the bubble dismisses.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun maybeShowMoveHint() {
        if (hintView != null) return
        val prefs = try {
            getSharedPreferences(PREFS, MODE_PRIVATE)
        } catch (_: Exception) {
            return
        }
        if (!FloatState.shouldShowMoveHint(prefs.getBoolean(KEY_MOVE_HINT, false))) return
        // Once-ever: persist first, show second. Never replays.
        try {
            prefs.edit { putBoolean(KEY_MOVE_HINT, true) }
        } catch (_: Exception) {
        }
        try {
            val lp = params ?: return
            val padH = dp(HINT_PAD_H_DP)
            val padV = dp(HINT_PAD_V_DP)
            val tv = android.widget.TextView(this).apply {
                text = getString(R.string.float_move_hint)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(0xE61D1B20.toInt())
                }
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = false
                setOnClickListener { hideMoveHint() }
                setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        hideMoveHint()
                        true
                    } else {
                        false
                    }
                }
            }
            // Screen bounds for anchoring (fold/rotate-safe query).
            val (scrW, scrH) = screenSize()
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(scrW - dp(HINT_MARGIN_DP) * 2, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val w = tv.measuredWidth.coerceAtLeast(1)
            val h = tv.measuredHeight.coerceAtLeast(1)
            val margin = dp(HINT_MARGIN_DP)
            val hp = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.x = moveHintX(lp.x + lp.width / 2, w, 0, scrW)
                it.y = moveHintY(lp.y, lp.height, h, margin, scrH)
            }
            hintParams = hp
            hintView = tv
            wm?.addView(tv, hp)
            mainHandler.removeCallbacks(hintHide)
            mainHandler.postDelayed(hintHide, HINT_TIMEOUT_MS)
        } catch (_: Exception) {
            hideMoveHint()
        }
    }

    /** Dismiss the move-hint coachmark (tap / outside / timeout / bubble hide). */
    private fun hideMoveHint() {
        try {
            mainHandler.removeCallbacks(hintHide)
        } catch (_: Exception) {
        }
        try {
            hintView?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        hintView = null
        hintParams = null
    }

    /** Live screen size in px (coachmark anchoring; fold/rotate-safe). */
    private fun screenSize(): Pair<Int, Int> {
        val w = wm ?: return Pair(0, 0)
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = w.currentWindowMetrics.bounds
                Pair(b.width(), b.height())
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (_: Exception) {
            Pair(0, 0)
        }
    }

    // -- Dictation.Listener ----------------------------------------------

    override fun onState(s: Dictation.State, toggled: Boolean) {
        micBtn?.post {
            val nextToggle = s == Dictation.State.RECORDING && toggled
            val leavingRecording = vizHolder != null && s != Dictation.State.RECORDING
            if (leavingRecording && !toggleCollapsing) collapseToggleToBubble()
            toggleRecording = nextToggle
            mainHandler.removeCallbacks(revealHoldControls)
            val holdDelay = if (s == Dictation.State.RECORDING && !nextToggle) dictation.holdControlsDelayMs() else null
            holdControlsReady = holdDelay == 0L
            if (holdDelay != null && holdDelay > 0L) mainHandler.postDelayed(revealHoldControls, holdDelay)
            holder?.let { root ->
                if (toggleRecording || toggleCollapsing) root.visibility = View.INVISIBLE
                else if (root.visibility != View.VISIBLE) {
                    root.animate().cancel()
                    root.scaleX = 1f
                    root.scaleY = 1f
                    root.alpha = 0f
                    root.visibility = View.VISIBLE
                    root.animate().alpha(1f).setDuration(85L).start()
                }
            }
            params?.let { lp ->
                lp.flags = if (toggleRecording || toggleCollapsing) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                lp.flags = if (FloatState.keepsScreenOn(s)) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                try { wm?.updateViewLayout(holder, lp) } catch (_: Exception) { }
            }
            refreshChrome(s)
            if (s == Dictation.State.RECORDING && holder != null) {
                if (!toggleRecording && holdDelay != null) {
                    showLockTarget()
                    lockHolder?.alpha = if (holdControlsReady) 1f else 0f
                }
                ensureVizPill()
            } else if (!toggleCollapsing) hideVizPill()
            if (s != Dictation.State.RECORDING || toggleRecording || holdDelay == null) hideLockTarget()
        }
        refreshNotif(s)
    }

    override fun onResult(text: String) {
        micBtn?.post { refreshChrome(Dictation.State.IDLE) }
        android.util.Log.i("MwFloat", "onResult len=${text.length} text=<$text>")
        deliver(text)
    }

    private val bgWorkers = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onError(msg: String) {
        micBtn?.post { refreshChrome(Dictation.State.IDLE) }
        // The bubble toast truncates, so the full cause must also go
        // to logcat — otherwise the true error is unrecoverable.
        android.util.Log.w("MwFloat", onErrorLogMessage(msg))
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
            android.util.Log.i(
                "MwFloat",
                "deliver entry len=${text.length} trimmedLen=${t.length} text=<$t>",
            )
            if (t.isEmpty()) {
                android.util.Log.i("MwFloat", "deliver drop-empty")
                return@execute
            }
            val ok = MyWhisperAccessibility.commit(t)
            android.util.Log.i("MwFloat", "deliver commit ok=$ok text=<$t>")
            if (!ok) {
                // Toast must run on the main thread (this is a worker).
                // a11y off is a configuration failure, not a missing
                // field: say which one so the fix is obvious.
                val a11yOff = !MyWhisperAccessibility.isOn(this)
                android.util.Log.i("MwFloat", "deliver fail a11yOff=$a11yOff")
                mainHandler.post {
                    try {
                        Toast.makeText(
                            this,
                            getString(
                                if (a11yOff) R.string.float_need_a11y
                                else R.string.float_no_field,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(snoozeEnd)
        foregrounded = false
        NotifPolicy.UnifiedState.floatActive = false
        NotifPolicy.UnifiedState.floatState = Dictation.State.IDLE
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
 * surface as a toast; routine drops stay silent — including the
 * cancel-race "empty transcript" (user discarded, nothing to say).
 */
internal fun shouldSurfaceError(msg: String): Boolean =
    !msg.contains("too short") && !msg.contains("silence") &&
        !msg.contains("empty transcript")

/** Logcat line for bubble failures (unit-testable; toast truncates). */
internal fun onErrorLogMessage(msg: String): String = "onError: $msg"

/** Mic glyph elevation (dp): the spinner must sit above this. */
internal const val BUBBLE_MIC_ELEVATION_DP = 6

/** Processing spinner elevation (dp): above the mic, below nothing. */
internal const val BUBBLE_SPINNER_ELEVATION_DP = 8

/** Stop button drawn size (dp): kept small, expanded by TouchDelegate. */
internal const val STOP_VISUAL_DP = 32

/** Stop button minimum touch target (dp): a11y parity with the grip. */
internal const val STOP_TARGET_DP = 48

/** Grip drag-down cancel travel (dp): mirrors the IME slide-cancel. */
internal const val GRIP_CANCEL_DY_DP = 48

/** Visualizer pill size (dp): 11 bars + side padding. */
internal const val VIZ_W_DP = 108

/** Visualizer pill height (dp). */
internal const val VIZ_H_DP = 36

/** Visualizer pill bottom margin (dp): above IME / nav bar / edge. */
internal const val VIZ_MARGIN_DP = 16

/** Move-hint coachmark auto-dismiss (ms): tap / outside / timeout / bubble-hide dismiss. */
internal const val HINT_TIMEOUT_MS = 6000L

/** Move-hint coachmark screen-edge margin (dp). */
internal const val HINT_MARGIN_DP = 16

/** Move-hint coachmark horizontal text padding (dp). */
internal const val HINT_PAD_H_DP = 14

/** Move-hint coachmark vertical text padding (dp). */
internal const val HINT_PAD_V_DP = 10

/**
 * Coachmark X (px, view origin): centered on the bubble center, clamped
 * into [minX, maxX - hintW]. Degenerate ranges collapse onto min so the
 * pill never lands off-screen.
 */
internal fun moveHintX(bubbleCxPx: Int, hintWPx: Int, minXPx: Int, maxXPx: Int): Int {
    val lo = minXPx.coerceAtMost(maxXPx)
    val hi = (maxXPx - hintWPx).coerceAtLeast(lo)
    return (bubbleCxPx - hintWPx / 2).coerceIn(lo, hi)
}

/**
 * Coachmark Y (px, view origin): below the bubble when the pill fits
 * on-screen, else above it (never negative).
 */
internal fun moveHintY(
    bubbleYPx: Int,
    bubbleHPx: Int,
    hintHPx: Int,
    marginPx: Int,
    screenHPx: Int,
): Int {
    val below = bubbleYPx + bubbleHPx + marginPx
    return if (below + hintHPx <= screenHPx) below else (bubbleYPx - hintHPx - marginPx).coerceAtLeast(0)
}

/** Visualizer bar-strip bar count (mirrors BarsView: 11 bars). */
internal const val VIZ_BAR_COUNT = 11

/** Visualizer bar-strip bar width (dp, mirrors BarsView). */
internal const val VIZ_BAR_W_DP = 3

/** Visualizer bar-strip gap (dp, mirrors BarsView). */
internal const val VIZ_BAR_GAP_DP = 3

/**
 * Visualizer bar-strip view height (dp): pill height minus the former
 * 6dp top/bottom padding (36 - 12 = 24). Width is exact content (see
 * [vizBarsContentWidthDp]), height keeps the old vertical inset.
 */
internal const val VIZ_BARS_H_DP = 24

/**
 * Exact bar-strip content width (dp): bars plus inner gaps, no
 * trailing gap (mirrors BarsView.onDraw: last bar ends at
 * count * (w + gap) - gap).
 */
internal fun vizBarsContentWidthDp(
    count: Int = VIZ_BAR_COUNT,
    barDp: Int = VIZ_BAR_W_DP,
    gapDp: Int = VIZ_BAR_GAP_DP,
): Int =
    if (count <= 0) 0 else count * barDp + (count - 1) * gapDp

/**
 * Per-edge side margin (dp) centering a [contentDp]-wide strip inside
 * a [pillDp]-wide pill: left and right margins are equal by
 * construction. The pill centers the exact-width strip view via
 * Gravity.CENTER, so this is the margin each edge gets.
 */
internal fun vizBarsSideMarginDp(
    pillDp: Int = VIZ_W_DP,
    contentDp: Int = vizBarsContentWidthDp(),
): Float = (pillDp - contentDp) / 2f

/**
 * Viz window horizontal offset (px): always screen-center (0),
 * independent of which edge the bubble is docked to. The viz pill is
 * its own window — it must never inherit an edge-side offset from the
 * bubble position.
 */
@Suppress("UNUSED_PARAMETER")
internal fun vizWindowXForDock(dockedLeft: Boolean): Int = 0

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

/**
 * Pure teardown matrix for the float death paths (STOP, overlay-denied
 * START, addView denial, FGS denial): every path runs the same full
 * teardown — detach, bubble, state, unified-state reset, shared-row
 * removal — and only the survivor recompose varies with sync liveness.
 * [OverlayService.teardownFloat] executes the plan field by field.
 */
internal data class FloatTeardown(
    val detachListener: Boolean,
    val removeBubble: Boolean,
    val clearState: Boolean,
    val resetUnifiedState: Boolean,
    val removeSharedRow: Boolean,
    val recomposeSync: Boolean,
)

/** Full teardown always; recompose the survivor row iff sync lives. */
internal fun floatTeardownPlan(syncActive: Boolean): FloatTeardown =
    FloatTeardown(
        detachListener = true,
        removeBubble = true,
        clearState = true,
        resetUnifiedState = true,
        removeSharedRow = true,
        recomposeSync = syncActive,
    )

/** Cancel has no visible box: trim its empty inner inset to balance optical gaps. */
internal const val TOGGLE_CONTROL_DP = 52
internal const val TOGGLE_WAVE_GAP_DP = 12
internal const val TOGGLE_CANCEL_INNER_INSET_DP = 19
internal fun togglePillWidthDp(): Int = TOGGLE_CONTROL_DP * 2 +
    TOGGLE_WAVE_GAP_DP * 2 + vizBarsContentWidthDp() - TOGGLE_CANCEL_INNER_INSET_DP
internal fun toggleWaveformLeftDp(dockedLeft: Boolean): Int = TOGGLE_CONTROL_DP +
    TOGGLE_WAVE_GAP_DP - if (dockedLeft) 0 else TOGGLE_CANCEL_INNER_INSET_DP

/** Common recording stage is waveform plus the primary symbol, before toggle's cancel extension. */
internal fun recordingCommonWidthDp(): Int = TOGGLE_CONTROL_DP + TOGGLE_WAVE_GAP_DP * 2 + vizBarsContentWidthDp()
