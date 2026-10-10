package com.mywhisper.mobile

import org.json.JSONObject

/**
 * JNI bridge to `mywhisper-core` (core/src/lib.rs).
 *
 * Every call returns the core's `{ok, data?, error?}` envelope as a raw
 * string; helpers below parse it. Transcription blocks — always call
 * `finishUtterance` off the UI thread.
 */
object CoreBridge {
    init {
        System.loadLibrary("mywhisper_core")
    }

    @JvmStatic external fun nativeOpen(filesDir: String, dbKeyHex: String): String
    @JvmStatic external fun nativeGetSettings(): String
    @JvmStatic external fun nativePutSettings(json: String): String
    @JvmStatic external fun nativeStartUtterance(sampleRate: Int): String
    @JvmStatic external fun nativePushPcm(data: ShortArray, length: Int): String
    @JvmStatic external fun nativeFinishUtterance(): String
    @JvmStatic external fun nativeCancelUtterance(): String
    @JvmStatic external fun nativeGetHistory(limit: Int): String
    @JvmStatic external fun nativeClearHistory(): String
    @JvmStatic external fun nativeDeleteHistory(uuid: String): String

    /** Parse `{ok, data?, error?}`. Returns data or throws with error text. */
    fun dataOf(raw: String): Any? {
        val o = JSONObject(raw)
        if (!o.optBoolean("ok", false)) {
            throw IllegalStateException(o.optString("error", "unknown core error"))
        }
        if (o.isNull("data")) return null
        return o.get("data")
    }

    fun open(filesDir: String, dbKeyHex: String): Result<String> = runCatching {
        dataOf(nativeOpen(filesDir, dbKeyHex)) as? JSONObject
            ?: throw IllegalStateException("bad open reply")
        "ok"
    }

    fun getSettings(): Result<JSONObject> = runCatching {
        JSONObject(nativeGetSettings())
    }

    fun putSettings(s: JSONObject): Result<Unit> = runCatching {
        dataOf(nativePutSettings(s.toString()))
        Unit
    }

    fun startUtterance(rate: Int) {
        nativeStartUtterance(rate)
    }

    fun pushPcm(data: ShortArray, n: Int) {
        nativePushPcm(data, n)
    }

    /** Blocks on the server chain. Returns (text, engine) or throws. */
    fun finishUtterance(): Pair<String, String> {
        val d = dataOf(nativeFinishUtterance()) as JSONObject
        return d.getString("text") to d.optString("engine", "")
    }

    fun cancelUtterance() {
        nativeCancelUtterance()
    }
}
