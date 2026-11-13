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
        const val ACTION_REFRESH = "com.mywhisper.mobile.float.REFRESH_NOTIF"
        // Shared row: same channel + ID as SyncService (see
        // NotifPolicy.UNIFIED_*); one visible row, two FGS owners.
        private const val CHANNEL = "mw-status"
        private const val NOTIF_ID = 41
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
     * Bubble chrome (owner pill round-2): one vertical column — grip
     * TOP, mic middle, stop BELOW — every row centered, so the bubble
     * docks flush and symmetric on both screen edges with no border gap
     * waste (single column width, no sidecar). The mic is branded
     * MyWhisper (plum gradient + lavender ring + static halo glow,
     * never the old generic dot); processing stays calm (dim + small
     * spinner, no pulse ever). The grip is the move affordance (pull
     * DOWN past threshold to cancel, mirrors the IME slide-cancel);
     * the mic talks (drop onto the X cancels); the X below stops with
     * discard. The persisted position is clamped into the live window
     * on show, so a rotation or fold never strands the bubble.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (holder != null) {
            refreshChrome(Dictation.State.IDLE)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val size = dp(60)
        val haloPad = dp(8)
        val cellW = size + haloPad * 2
        val gripTouch = dp(48)
        val stopSize = dp(STOP_VISUAL_DP)
        val stopCell = dp(40)
        val gripCell = dp(32)
        val rootW = cellW
        val rootH = gripCell + cellW + stopCell + dp(8)
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

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Grip TOP: the move affordance. Opaque rounded tab + white
        // pill, 48dp touch target, centered in its cell.
        val grip = FrameLayout(this).apply {
            contentDescription = getString(R.string.float_drag_handle)
            elevation = dp(7).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xCC1D1B20.toInt())
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(gripTouch, dp(24)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
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
        // Mic cell: static halo glow behind the branded mic button.
        val micCell = FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(cellW, cellW).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
        }
        val halo = View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FloatState.micChrome(Dictation.State.IDLE).glowColor)
            }
            layoutParams = FrameLayout.LayoutParams(cellW, cellW, Gravity.CENTER)
        }
        haloView = halo
        micCell.addView(halo)
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = getString(R.string.ime_mic)
            // Big mark: padding trimmed so the glyph fills the button.
            setPadding(dp(7), dp(7), dp(7), dp(7))
            elevation = dp(BUBBLE_MIC_ELEVATION_DP).toFloat()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setOnTouchListener { _, ev -> onMicTouch(ev) }
        }
        micBtn = mic
        micCell.addView(mic)
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
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            visibility = View.GONE
        }
        spinner = spin
        micCell.addView(spin)
        root.addView(micCell)
        // Stop BELOW the mic: same column, centered (symmetric on both
        // edges). Always visible while RECORDING or WORKING, hidden
        // while IDLE. Tap discards (never commits — owner pill round-2).
        val stop = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = getString(R.string.float_stop)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFB3261E.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(7).toFloat()
            layoutParams = android.widget.LinearLayout.LayoutParams(stopSize, stopSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
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
     * Mic touch: talk, with one escape hatch — releasing ON the X
     * below discards instead of finishing (drag-to-X cancel, owner
     * pill round-2: cancel must never transcribe). Movement otherwise
     * neither moves the bubble nor cancels: the grip moves, the X
     * cancels, the mic talks.
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
                if (droppedOnStop(ev.rawX, ev.rawY)) {
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
                moved = false
                dictation.cancel()
                return true
            }
        }
        return false
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
                }
                gripCancelled = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
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

    private fun removeBubble() {
        // Any hide drops the utterance (focus lost mid-press included).
        moved = false
        gripCancelled = false
        try {
            dictation.cancel()
        } catch (_: Exception) {
        }
        hideVizPill()
        hideMoveHint()
        try {
            holder?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        holder = null
        haloView = null
        micBtn = null
        dragHandle = null
        stopBtn = null
        spinner = null
    }

    /**
     * Bubble chrome per state (owner pill round-2 identity): branded
     * plum-gradient mic + lavender ring + static halo glow. RECORDING
     * reads red-brand + glow + stop, WORKING reads deep-brand dimmed +
     * small spinner + stop (calm processing, no pulsing), IDLE reads
     * brand with stop and spinner hidden. Recording vs processing
     * differ at a glance.
     */
    private fun refreshChrome(state: Dictation.State) {
        val chrome = FloatState.micChrome(state)
        val processing = FloatState.isProcessing(state)
        micBtn?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(chrome.bgColor, chrome.bgEndColor)
                setStroke(dp(2), chrome.strokeColor)
            }
            // Static dim while processing — no animation, no pulse.
            alpha = chrome.alpha
        }
        haloView?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(chrome.glowColor)
            }
        }
        spinner?.visibility = if (processing) View.VISIBLE else View.GONE
        stopBtn?.visibility =
            if (FloatState.isStopVisible(state)) View.VISIBLE else View.GONE
    }

    /**
     * Bottom-center level visualizer pill (owner item 7): a small
     * non-touchable BarsView pill, RECORDING only, bottom-center with
     * margin. Anchored above the IME when the keyboard is up
     * (WindowInsets IME bottom), else above the nav bar / screen
     * bottom — best-effort: overlays without input focus may never see
     * IME insets, in which case the inset reads 0 and the max()
     * degrades to the nav-bar fallback. Never focusable, never
     * touchable, Decorative to accessibility.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun ensureVizPill() {
        if (vizHolder != null) {
            vizHolder?.visibility = View.VISIBLE
            return
        }
        val margin = dp(VIZ_MARGIN_DP)
        val lp = WindowManager.LayoutParams(
            dp(VIZ_W_DP), dp(VIZ_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Screen-center anchor: x = 0 always, independent of the
            // bubble dock edge (see vizWindowXForDock). Never inherit an
            // edge-side offset here.
            it.x = 0
            it.y = margin
        }
        vizParams = lp
        val pill = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xCC1D1B20.toInt())
            }
        }
        val bars = BarsView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Exact strip width, centered: BarsView draws its bar strip
            // left-aligned from x = 0, so a MATCH_PARENT view leaves the
            // trailing (stripW..viewW) dead space on the right and the
            // pill reads off-center with more margin right. Sizing the
            // view to the strip content and centering it in the pill
            // gives equal margins on both edges.
            layoutParams = FrameLayout.LayoutParams(
                dp(vizBarsContentWidthDp()),
                dp(VIZ_BARS_H_DP),
                Gravity.CENTER,
            )
        }
        vizBars = bars
        pill.addView(bars)
        // IME-aware anchor: re-seat above the keyboard when visible.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                pill.setOnApplyWindowInsetsListener { _, insets ->
                    val ime = insets.getInsets(
                        android.view.WindowInsets.Type.ime(),
                    ).bottom
                    val nav = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars(),
                    ).bottom
                    vizParams?.let { p ->
                        p.y = FloatState.vizPillYOffset(ime, nav, margin)
                        try {
                            wm?.updateViewLayout(vizHolder, p)
                        } catch (_: Exception) {
                        }
                    }
                    insets
                }
            } catch (_: Exception) {
            }
        }
        vizHolder = pill
        try {
            wm?.addView(pill, lp)
        } catch (_: Exception) {
            vizHolder = null
            vizBars = null
        }
    }

    private fun hideVizPill() {
        try {
            vizBars?.clear()
        } catch (_: Exception) {
        }
        try {
            vizHolder?.let { wm?.removeView(it) }
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
            refreshChrome(s)
        }
        // Visualizer pill lives exactly while RECORDING (with the
        // bubble up); every other state hides it.
        if (s == Dictation.State.RECORDING && holder != null) {
            try {
                micBtn?.post { ensureVizPill() }
            } catch (_: Exception) {
            }
        } else {
            micBtn?.post { hideVizPill() }
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
