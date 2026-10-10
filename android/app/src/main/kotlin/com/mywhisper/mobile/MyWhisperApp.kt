package com.mywhisper.mobile

import android.app.Application

/**
 * Opens the core once per process (config app and IME run in separate
 * processes, each with its own SQLite connection — same `state.db`).
 */
class MyWhisperApp : Application() {
    var coreError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MyWhisperIME", "app.onCreate begin")
        try {
            val key = KeystoreKey.getOrCreateHex(this)
            android.util.Log.i("MyWhisperIME", "app.onCreate key ok")
            CoreBridge.open(filesDir.absolutePath, key).getOrThrow()
            android.util.Log.i("MyWhisperIME", "app.onCreate core ok")
        } catch (e: Exception) {
            coreError = e.message
            android.util.Log.i("MyWhisperIME", "app.onCreate err=${e.message}")
        }
    }
}
