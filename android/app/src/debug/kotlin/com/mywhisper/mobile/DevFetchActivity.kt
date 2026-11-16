package com.mywhisper.mobile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-only file fetcher (src/debug, never in release builds) for
 * on-device benches over adb, where the shell has no HTTP client:
 *
 *   am start -n <pkg>/com.mywhisper.mobile.DevFetchActivity \
 *     --es url https://… --es out bench/model.gguf
 *
 * Streams into `<filesDir>/<out>` (`.part` + rename) and logs
 * `MW_FETCH done <out> <bytes>` or `MW_FETCH error …`; then read it with
 * `run-as <pkg>`.
 */
class DevFetchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fetch(intent)
        finish()
    }

    // Back-to-back `am start`s reach this (still finishing) instance here.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        fetch(intent)
    }

    private fun fetch(intent: android.content.Intent) {
        val url = intent.getStringExtra("url").orEmpty()
        val out = intent.getStringExtra("out").orEmpty()
        val dest = File(filesDir, out).canonicalFile
        if (url.isBlank() || !dest.path.startsWith(filesDir.canonicalPath + "/")) {
            Log.i("MW_FETCH", "error bad args")
            return
        }
        Thread {
            try {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it, 1 shl 16) } }
                check(tmp.renameTo(dest)) { "rename failed" }
                Log.i("MW_FETCH", "done $out ${dest.length()}")
            } catch (e: Exception) {
                Log.i("MW_FETCH", "error $out ${e.message}")
            }
        }.start()
    }
}
