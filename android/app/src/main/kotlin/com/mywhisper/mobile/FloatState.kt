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

    /**
     * Overlay-permission denial latch for the recovery flow: set when
     * the service finds `canDrawOverlays` false (or an addView throws
     * SecurityException after a runtime revoke), cleared on the next
     * start with the permission granted. Lets Setup/the service tell
     * "never asked" apart from "revoked — send to Settings".
     */
    @Volatile var overlayDenied: Boolean = false

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

    // -- Floating-bubble rules (pure, unit-testable) -------------------

    /**
     * Auto-show rule (mirrors OverlayService.applyVisibility): floating
     * enabled, our IME not active (the bubble stands down while our
     * own keyboard is up), and — when the accessibility commit path is
     * on — a text field focused. No manual hide needed.
     */
    fun shouldShowBubble(
        floatEnabled: Boolean,
        imeActive: Boolean,
        a11yOn: Boolean,
        hasTextFocus: Boolean,
    ): Boolean =
        floatEnabled && !imeActive && (!a11yOn || hasTextFocus)

    /**
     * Edge snap: nearest horizontal safe bound. Ties go right (most
     * users are right-handed; the bubble rests out of the way).
     */
    fun snapToEdge(x: Int, minX: Int, maxX: Int): Int {
        // Degenerate range (fold mid-transition): collapse onto min so
        // the persisted x never lands off-screen despite clampBubble.
        if (maxX <= minX) return minX
        return if (x - minX < maxX - x) minX else maxX
    }

    /**
     * Clamp a bubble origin into the safe bounds. Degenerate ranges
     * (fold mid-transition reporting max < min) collapse onto min so
     * the bubble never lands off-screen.
     */
    fun clampBubble(
        x: Int,
        y: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
    ): Pair<Int, Int> =
        Pair(
            x.coerceIn(minX.coerceAtMost(maxX), maxX.coerceAtLeast(minX)),
            y.coerceIn(minY.coerceAtMost(maxY), maxY.coerceAtLeast(minY)),
        )

    /**
     * Move arming (ux P0: a tap never destroys a recording): the bubble
     * moves only from the drag handle past touch slop, or — for a fat
     * finger that misses the handle — after a long-press on the mic
     * plus movement. Anything else is a tap/hold: pure Dictation input,
     * never a drag, never a cancel.
     */
    fun shouldArmMove(
        movedPx: Float,
        slopPx: Float,
        longPressFired: Boolean,
        onHandle: Boolean,
    ): Boolean =
        (onHandle || longPressFired) && movedPx > slopPx

    /** Record an overlay denial; returns the latch (always true). */
    fun recordOverlayDenial(): Boolean {
        overlayDenied = true
        return true
    }

    /** Clear the denial latch once the permission is granted again. */
    fun clearOverlayDenied() {
        overlayDenied = false
    }

    private const val PREFS = "mw_state"
    private const val KEY_IME_ACTIVE = "ime_active"
}
