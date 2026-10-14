package com.mywhisper.mobile

import android.content.Context

/**
 * Process-wide bubble visibility state (IME and overlay service run
 * in the same process, so a singleton suffices — no IPC).
 *
 * Visible = floating enabled, our IME not active, and (when the
 * accessibility service is on) a text field focused. No manual
 * hide needed.
 *
 * imeActive is persisted: process death wipes memory (default false),
 * so the IME re-syncs from prefs in onCreate and publishes every
 * change via [setImeActive].
 */
object FloatState {
    @Volatile var imeActive: Boolean = false
    @Volatile var hasTextFocus: Boolean = false

    /** Set by OverlayService; invoked on every state change. */
    @Volatile var onChange: (() -> Unit)? = null

    fun refresh() {
        try {
            onChange?.invoke()
        } catch (_: Exception) {
        }
    }

    /**
     * Publish IME visibility and persist it for post-death re-sync.
     * The IME calls this instead of assigning [imeActive] directly.
     */
    fun setImeActive(ctx: Context?, active: Boolean) {
        imeActive = active
        try {
            ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_IME_ACTIVE, active)?.apply()
        } catch (_: Exception) {
        }
        refresh()
    }

    /**
     * Re-sync memory from prefs after process death. Call from the
     * IME onCreate (before any view exists).
     */
    fun restoreImeActive(ctx: Context) {
        try {
            imeActive = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_IME_ACTIVE, false)
        } catch (_: Exception) {
        }
    }

    /**
     * Pure resolution for tests: after death memory is default-false,
     * so a persisted value wins; otherwise keep memory.
     */
    fun resolveImeActive(persisted: Boolean?, memActive: Boolean): Boolean =
        HardeningGuards.resolveImeActive(persisted, memActive)

    private const val PREFS = "mw_state"
    private const val KEY_IME_ACTIVE = "ime_active"
}
