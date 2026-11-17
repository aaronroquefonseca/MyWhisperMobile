package com.mywhisper.mobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 16 kHz mono PCM16 capture (feeds the core, which resamples/VADs to
 * its 16 kHz engine format — pass-through rate when the device agrees).
 * One-shot: [start] spawns the read loop, [stop] joins it.
 */
class AudioCapture(
    private val sampleRate: Int = 16_000,
    var onPcm: ((ShortArray, Int) -> Unit)? = null,
    var onError: ((String) -> Unit)? = null,
    /** 0..1 normalized voice level per 32 ms read (see [LevelNormalizer]). */
    var onLevel: ((Float) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile var running = false
        private set

    val actualRate: Int get() = sampleRate

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            onError?.invoke("mic unavailable on this device")
            return false
        }
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 4,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                onError?.invoke("mic init failed")
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({
                // 512 samples = 32 ms → ~31 level updates/s for the bars.
                val buf = ShortArray(512)
                val norm = LevelNormalizer()
                while (running) {
                    val n = try {
                        rec.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) {
                        try {
                            onPcm?.invoke(buf, n)
                        } catch (_: Exception) {
                        }
                        try {
                            var sum = 0.0
                            for (i in 0 until n) {
                                val v = buf[i].toDouble()
                                sum += v * v
                            }
                            onLevel?.invoke(norm.push(kotlin.math.sqrt(sum / n) / 32768.0))
                        } catch (_: Exception) {
                        }
                    } else if (n < 0) {
                        break
                    }
                }
            }, "mw-capture").also { it.start() }
            CoreBridge.startUtterance(sampleRate)
            true
        } catch (e: SecurityException) {
            onError?.invoke("mic permission denied")
            false
        } catch (e: Exception) {
            onError?.invoke("mic start: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(1000)
        } catch (_: Exception) {
        }
        thread = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }
}

/**
 * Raw block RMS (0..1 full scale) → 0..1 visual level. Works in dB:
 * an adaptive noise floor (drops instantly, creeps up slowly) gates
 * room noise to 0, and a decaying loudness ceiling auto-gains so both
 * quiet and loud talkers fill the bars. Linear peak/32768 left normal
 * speech at ~5-20% height.
 */
class LevelNormalizer {
    // Seeded from the first real block: a noisy room is learned at once
    // instead of creeping up at FLOOR_RISE_DB; speech-first just costs one word gap.
    private var floorDb = Double.NaN
    private var ceilDb = Double.NaN

    fun push(rms: Double): Float {
        // Digital-zero warm-up blocks (some HALs) say nothing about room noise.
        if (rms <= 0.0) return 0f
        val db = 20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
        if (floorDb.isNaN()) { floorDb = db; ceilDb = db + MIN_SPAN_DB }
        floorDb = (if (db < floorDb) db else floorDb + FLOOR_RISE_DB).coerceIn(MIN_FLOOR_DB, -20.0)
        ceilDb = maxOf(db, ceilDb - CEIL_FALL_DB, floorDb + MIN_SPAN_DB)
        val gate = floorDb + GATE_DB
        return ((db - gate) / (ceilDb - gate)).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        /** Per 32 ms block: floor rises ~1.5 dB/s, so steady noise is learned in seconds. */
        const val FLOOR_RISE_DB = 0.05
        /** Per block: ceiling relaxes ~6 dB/s after a loud burst. */
        const val CEIL_FALL_DB = 0.2
        /** Quietest floor; real mic self-noise sits above this. */
        const val MIN_FLOOR_DB = -70.0
        /** Level stays 0 until this far above the noise floor. */
        const val GATE_DB = 6.0
        /** Minimum floor→ceiling span, so pure noise never fills the bars. */
        const val MIN_SPAN_DB = 24.0
    }
}
