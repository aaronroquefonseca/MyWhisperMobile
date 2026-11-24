package com.mywhisper.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * 16 kHz mono PCM16 capture (feeds the core, which resamples/VADs to
 * its 16 kHz engine format — pass-through rate when the device agrees).
 * One-shot: [start] spawns the read loop, [stop] joins it.
 */
class AudioCapture(
    /** Routes per `preferences.mic_priority`; null = system default input. */
    private val context: Context? = null,
    private val sampleRate: Int = 16_000,
    var onPcm: ((ShortArray, Int) -> Unit)? = null,
    var onError: ((String) -> Unit)? = null,
    /** 0..1 normalized voice level per 32 ms read (see [LevelNormalizer]). */
    var onLevel: ((Float) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var audioManager: AudioManager? = null
    @Volatile var running = false
        private set

    /** Core utterance token for this recording (set by [start]). */
    var utterance = 0L
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
            routeInput(rec)
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
            utterance = CoreBridge.startUtterance(sampleRate)
            true
        } catch (e: SecurityException) {
            stop()
            onError?.invoke("mic permission denied")
            false
        } catch (e: Exception) {
            stop()
            onError?.invoke("mic start: ${e.message}")
            false
        }
    }

    /**
     * Pins [rec] to the first connected kind in `preferences.mic_priority`.
     * Bluetooth also needs the headset link (SCO / LE Audio) brought up as
     * the communication device; [stop] releases it so earbuds leave call mode.
     */
    private fun routeInput(rec: AudioRecord) {
        val am = context?.getSystemService(AudioManager::class.java) ?: return
        val priority = try {
            val arr = CoreBridge.getSettings().getOrThrow()
                .optJSONObject("preferences")?.optJSONArray("mic_priority")
            arr?.let { a -> List(a.length()) { a.optString(it) } }
        } catch (_: Exception) {
            null
        } ?: return
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val kind = { d: AudioDeviceInfo -> MicRoute.kindOf(d.type) }
        var device = MicRoute.pick(priority, inputs, kind) ?: return
        if (kind(device) == MicRoute.BLUETOOTH) {
            // shortcut: Bluetooth needs setCommunicationDevice (Android 12+); 10-11 fall through, add startBluetoothSco if asked.
            val linked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                am.availableCommunicationDevices.firstOrNull { it.type == device.type }
                    ?.let { am.setCommunicationDevice(it) } == true
            if (linked) {
                audioManager = am
            } else {
                device = MicRoute.pick(priority - MicRoute.BLUETOOTH, inputs, kind) ?: return
            }
        }
        rec.setPreferredDevice(device)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager?.let { try { it.clearCommunicationDevice() } catch (_: Exception) { } }
        }
        audioManager = null
    }
}

/** Input categories, named as core `MicKind` serializes them. */
internal object MicRoute {
    const val WIRED = "Wired"
    const val BUILT_IN = "BuiltIn"
    const val BLUETOOTH = "Bluetooth"

    fun kindOf(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> BUILT_IN
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> BLUETOOTH
        else -> null
    }

    /** First device whose kind ranks highest; null when none is ranked. */
    fun <T> pick(priority: List<String>, devices: List<T>, kind: (T) -> String?): T? {
        for (k in priority) devices.firstOrNull { kind(it) == k }?.let { return it }
        return null
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
