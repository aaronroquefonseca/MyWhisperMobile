package com.mywhisper.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live mic-level visualizer for the floating pill and IME (11 bars,
 * 3px wide, 3px gap, 18px lanes, 4px resting height, white). Center
 * bloom: silence = dots; voice opens the bars from the middle out,
 * every bar showing the current level under a mirrored bell.
 */
class BarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    private val bloom = BloomBars(BAR_COUNT)
    private var lastFrameMs = 0L

    /** Push a 0..1 normalized level (UI thread); animates until settled. */
    fun pushLevel(level: Float) {
        bloom.push(level, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    fun clear() {
        bloom.reset()
        lastFrameMs = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 1f / 60f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        if (bloom.step(dt, now)) postInvalidateOnAnimation() else lastFrameMs = 0L
        val d = resources.displayMetrics.density
        // Guard: first layout / window resize can deliver a ~0 height;
        // without this mid - 1f goes negative and bars invert.
        if (height < MIN_HEIGHT_DP * d || width <= 0) return
        val bw = 3f * d
        val gap = 3f * d
        val mid = height / 2f
        for (i in 0 until BAR_COUNT) {
            val h = HardeningGuards.barHalfHeight(height.toFloat(), bloom.heights[i], d)
            val x = i * (bw + gap)
            canvas.drawRoundRect(
                x, mid - h, x + bw, mid + h,
                bw / 2f, bw / 2f, paint,
            )
        }
    }

    companion object {
        private const val BAR_COUNT = 11

        /** Below this view height bars are skipped (dp). */
        const val MIN_HEIGHT_DP = 5f
    }
}

/**
 * Center-bloom shape, View-free so it unit-tests. [push] feeds levels,
 * [step] advances one frame and fills [heights] (0..1 per bar).
 * Smoothing rates are "per 60 Hz frame", rescaled by dt for 90/120 Hz.
 */
class BloomBars(private val count: Int, random: Random = Random.Default) {
    val heights = FloatArray(count)

    private var input = 0f
    private var level = 0f
    private var spread = 0f
    private var clock = 0f
    private var lastVoiceMs = Long.MIN_VALUE / 2

    // One phase set per mirrored pair, so left and right halves move together.
    private val phases = Array((count + 1) / 2) {
        floatArrayOf(
            random.nextFloat() * 2f * PI.toFloat(),
            random.nextFloat() * 2f * PI.toFloat(),
            2f + random.nextFloat() * 3f,
            3f + random.nextFloat() * 4f,
        )
    }

    fun push(value: Float, nowMs: Long) {
        input = value.coerceIn(0f, 1f)
        if (input > VOICE_GATE) lastVoiceMs = nowMs
    }

    fun reset() {
        heights.fill(0f)
        input = 0f
        level = 0f
        spread = 0f
        lastVoiceMs = Long.MIN_VALUE / 2
    }

    /** Advance [dtSec]; returns true while still moving (keep drawing). */
    fun step(dtSec: Float, nowMs: Long): Boolean {
        clock += dtSec
        val voice = nowMs - lastVoiceMs < HANGOVER_MS
        level = approach(level, input, if (input > level) 0.55f else 0.18f, dtSec)
        // ~120 ms to open, ~400 ms to close.
        spread = approach(spread, if (voice) 1f else 0f, if (voice) 0.22f else 0.07f, dtSec)
        val c = (count - 1) / 2f
        var moving = voice || spread > SETTLED
        for (i in 0 until count) {
            val d = if (c == 0f) 0f else abs(i - c) / c
            val reveal = ((spread * 1.25f - d) / 0.25f).coerceIn(0f, 1f)
            val bell = 0.12f + 0.88f * exp(-(d * d) / (2f * BELL_SIGMA * BELL_SIGMA))
            val p = phases[min(i, count - 1 - i)]
            val wobble = 1f - WOBBLE + WOBBLE *
                (0.5f + 0.25f * sin(clock * p[2] + p[0]) + 0.25f * sin(clock * p[3] + p[1]))
            heights[i] = approach(heights[i], reveal * level * bell * wobble, 0.45f, dtSec)
            if (heights[i] > SETTLED) moving = true
        }
        return moving
    }

    private fun approach(cur: Float, target: Float, perFrame60: Float, dtSec: Float): Float =
        cur + (target - cur) * (1f - (1f - perFrame60).pow(dtSec * 60f))

    companion object {
        /** Normalized level above this counts as voice (LevelNormalizer already gates noise). */
        const val VOICE_GATE = 0.08f
        /** Voice stays "on" this long after the last loud block, bridging word gaps. */
        const val HANGOVER_MS = 220L
        private const val BELL_SIGMA = 0.32f
        private const val WOBBLE = 0.16f
        private const val SETTLED = 0.002f
    }
}
