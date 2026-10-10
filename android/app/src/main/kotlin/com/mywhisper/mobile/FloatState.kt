package com.mywhisper.mobile

/**
 * Process-wide bubble visibility state (IME and overlay service run
 * in the same process, so a singleton suffices — no IPC).
 *
 * Visible = floating enabled, our IME not active, and (when the
 * accessibility service is on) a text field focused. No manual
 * hide needed.
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
}
