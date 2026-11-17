package com.mywhisper.mobile

import android.content.Context
import java.io.File

/**
 * On-device LLM for dictation cleanup, called from Rust
 * (`local_llm::try_local_cleanup`, registered via
 * [CoreBridge.setLocalLlmRunner]) when the enabled `Local` cleanup
 * entry's turn comes. Same runtime and file choice as the Settings
 * self-test.
 *
 * Runs on the finish worker while the core engine lock is held:
 * never call [CoreBridge] from here. Throwing is the failure signal;
 * the Rust chain logs it and falls through to the next entry.
 */
class LocalLlmCleanupRunner(context: Context) {
    private val app = context.applicationContext
    private val assets by lazy { LocalLlmManager.parseManifest(MainActivity.LLM_MANIFEST_JSON) }

    /** Name and signature are looked up by JNI (`core/src/lib.rs`). */
    fun cleanup(system: String, transcript: String): String {
        val prefs = app.getSharedPreferences("mw_state", Context.MODE_PRIVATE)
        val asset = MainActivity.llmSelectedAsset(assets, prefs.getString(MainActivity.PREF_SELECTED_LLM, null))
            ?: throw IllegalStateException("no local llm asset in manifest")
        val file = modelFile(app.filesDir, asset)
        return LocalLlmManager.generate(app, file, system, transcript, asset)
    }

    companion object {
        /**
         * Per-asset slot, else the adopted legacy slot (self-test order).
         * shortcut: stat-only, no SHA re-verify per dictation (GB packs);
         * enabling Local already requires a verified pack and downloads
         * land via `.part` + rename, so a present slot is a complete one.
         */
        @JvmStatic
        internal fun modelFile(dir: File, asset: LocalLlmManager.ModelAsset): File {
            val slot = MainActivity.llmTaskFileFor(dir, asset)
            if (slot.isFile) return slot
            val legacy = LocalLlmManager.defaultTaskFile(dir)
            if (legacy.isFile) return legacy
            throw IllegalStateException("local llm model not downloaded: ${asset.id}")
        }
    }
}
