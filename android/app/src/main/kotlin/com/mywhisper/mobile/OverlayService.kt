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
    }

    private lateinit var dictation: Dictation
    private var wm: WindowManager? = null
    private var holder: FrameLayout? = null
    private var micBtn: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var baseX = 0
    private var baseY = 0
    private var moved = false
    private var micCancelled = false

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
                removeBubble()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.float_need_overlay), Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForegroundWithType()
                } catch (e: Exception) {
                    android.util.Log.i("MwFloat", "fg denied: ${e.message}")
                }
                ensureCore()
                FloatState.onChange = { applyVisibility() }
                applyVisibility()
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
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.float_notif_title))
            .setContentText(getString(R.string.float_notif_text))
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
            .build()
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
        if (!on) {
            removeBubble()
            return
        }
        val show = !FloatState.imeActive &&
            (!MyWhisperAccessibility.isOn(this) || FloatState.hasTextFocus)
        if (show) {
            if (holder == null) showBubble()
        } else {
            removeBubble()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (holder != null) {
            refreshMic(false)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val size = dp(60)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = prefs.getInt(KEY_X, 60)
            it.y = prefs.getInt(KEY_Y, 400)
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
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(size, size)
            setOnTouchListener { v, ev -> onBubbleTouch(v, ev) }
        }
        micBtn = mic
        root.addView(mic)
        holder = root
        try {
            wm?.addView(root, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        refreshMic(false)
    }

    private fun onBubbleTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                params?.let { baseX = it.x; baseY = it.y }
                moved = false
                micCancelled = false
                dictation.pressDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!moved && kotlin.math.hypot(dx, dy) > dp(10)) {
                    // It is a drag: drop the press, move the bubble.
                    moved = true
                    dictation.cancel()
                }
                if (moved) {
                    params?.let { lp ->
                        lp.x = baseX + dx.toInt()
                        lp.y = baseY + dy.toInt()
                        try {
                            wm?.updateViewLayout(holder, lp)
                        } catch (_: Exception) {
                        }
                    }
                    return true
                }
                // Hold-talk slide-down cancel (same threshold as IME).
                if (!micCancelled && ev.rawY - downRawY > dp(90)) {
                    micCancelled = true
                    dictation.cancel()
                    Toast.makeText(this, getString(R.string.ime_cancelled), Toast.LENGTH_SHORT).show()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    // Persist position; visibility stays automatic.
                    params?.let { lp ->
                        try {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                        } catch (_: Exception) {
                        }
                    }
                    moved = false
                    return true
                }
                dictation.pressUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dictation.cancel()
                return true
            }
        }
        return false
    }

    private fun removeBubble() {
        // Any hide drops the utterance (focus lost mid-press included).
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
        micBtn?.post { refreshMic(s == Dictation.State.RECORDING) }
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
        if (!msg.contains("too short") && !msg.contains("silence")) {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    /** Commit runs here (not in onResult) to keep the result text. */
    fun deliver(text: String) {
        bgWorkers.execute {
            val ok = MyWhisperAccessibility.commit(text)
            if (!ok) {
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

    override fun onDestroy() {
        removeBubble()
        bgWorkers.shutdownNow()
        super.onDestroy()
    }
}
