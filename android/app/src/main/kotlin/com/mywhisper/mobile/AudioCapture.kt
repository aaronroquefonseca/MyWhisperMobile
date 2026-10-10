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
    /** 0..1 peak per read, for the pill visualizer. */
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
                val buf = ShortArray(2048)
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
                            var peak = 0
                            for (i in 0 until n) {
                                val a = kotlin.math.abs(buf[i].toInt())
                                if (a > peak) peak = a
                            }
                            onLevel?.invoke((peak / 32768f).coerceIn(0f, 1f))
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
