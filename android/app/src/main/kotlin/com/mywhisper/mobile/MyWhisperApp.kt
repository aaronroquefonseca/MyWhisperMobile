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
            // Per process (IME, overlay, app): dictation cleanup reaches
            // the on-device LLM only through this registration.
            CoreBridge.setLocalLlmRunner(LocalLlmCleanupRunner(this))
        } catch (e: Exception) {
            coreError = e.message
            android.util.Log.i("MyWhisperIME", "app.onCreate err=${e.message}")
            return
        }
        // Opportunistic convergence: one round per process start so
        // recent changes (including kickSync races) land without
        // waiting for the 60s loop. Background thread, no-op idle.
        Thread({
            try {
                CoreBridge.syncAllNow()
            } catch (_: Exception) {
            }
            // Free storage held by pre-llama.cpp model formats and old packs.
            LocalAiRuntime.removeLegacyModels(filesDir, cacheDir)
            LocalAiRuntime.removeStalePacks(filesDir)
            // Main process only: the IME process would re-enqueue (REPLACE) the same work.
            if (android.app.Application.getProcessName() == packageName) LocalAiRuntime.ensureForInstalledModels(this)
            // Keep the bubble alive across process death when enabled.
            try {
                val s = CoreBridge.getSettings().getOrThrow()
                if (s.optJSONObject("preferences")?.optBoolean("float_enabled", true) == true) {
                    OverlayService.start(this)
                }
            } catch (_: Exception) {
            }
        }, "mw-startup").start()
    }
}
