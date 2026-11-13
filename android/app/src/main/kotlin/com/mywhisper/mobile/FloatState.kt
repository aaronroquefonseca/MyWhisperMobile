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
     * own keyboard is up), the accessibility commit path on, and a
     * text field focused. Without the accessibility service the bubble
     * cannot land text anywhere, so it must not linger on screen: an
     * a11y-off session used to show it permanently (owner repro: pill
     * visible on the launcher, every tap answered "no editable field
     * focused"). Setup carries the enable-service CTA instead.
     */
    fun shouldShowBubble(
        floatEnabled: Boolean,
        imeActive: Boolean,
        a11yOn: Boolean,
        hasTextFocus: Boolean,
    ): Boolean =
        floatEnabled && !imeActive && a11yOn && hasTextFocus

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
     * Move arming (owner UX: grip is the ONLY move affordance): the
     * bubble moves only from the drag handle past touch slop. Mic
     * tap / long-press / drag never arms a move — pure Dictation
     * input, never a drag, never a cancel.
     */
    fun shouldArmMove(
        movedPx: Float,
        slopPx: Float,
        onHandle: Boolean,
    ): Boolean =
        onHandle && movedPx > slopPx

    /** Stop affordance: always visible while active (RECORDING or WORKING). */
    fun isStopVisible(state: Dictation.State): Boolean =
        state != Dictation.State.IDLE

    /** Calm processing treatment (spinner + dim, no pulse) while WORKING. */
    fun isProcessing(state: Dictation.State): Boolean =
        state == Dictation.State.WORKING

    /** Recording treatment (branded mic + glow, no spinner) while RECORDING. */
    fun isRecording(state: Dictation.State): Boolean =
        state == Dictation.State.RECORDING

    /**
     * Branded mic chrome per state (owner pill round-2: distinctive
     * MyWhisper identity, never a generic purple dot). RECORDING reads
     * full-brand + glow, WORKING reads deep-brand dimmed + spinner
     * (calm processing, static — no pulse ever), IDLE reads brand.
     * Pure so tests pin the identity (bg never the old generic fill).
     */
    data class MicChrome(val bgColor: Int, val bgEndColor: Int, val strokeColor: Int, val glowColor: Int, val alpha: Float)

    fun micChrome(state: Dictation.State): MicChrome =
        when (state) {
            Dictation.State.RECORDING ->
                MicChrome(
                    bgColor = 0xFFB3261E.toInt(), bgEndColor = 0xFF7A1A14.toInt(),
                    strokeColor = 0xFFFFDAD6.toInt(), glowColor = 0x59B3261E.toInt(), alpha = 1f,
                )
            Dictation.State.WORKING ->
                MicChrome(
                    bgColor = 0xFF2E1A57.toInt(), bgEndColor = 0xFF1D1038.toInt(),
                    strokeColor = 0xFF9C8DC4.toInt(), glowColor = 0x402E1A57.toInt(), alpha = 0.6f,
                )
            Dictation.State.IDLE ->
                MicChrome(
                    bgColor = 0xFF4A2B8A.toInt(), bgEndColor = 0xFF2E1A57.toInt(),
                    strokeColor = 0xFFD6C6F5.toInt(), glowColor = 0x4D4A2B8A.toInt(), alpha = 1f,
                )
        }

    /** The old generic dot fill — brand chrome must never regress to it. */
    val LEGACY_GENERIC_MIC_FILL: Int = 0xFF6750A4.toInt()

    /**
     * Stop-tap decision matrix (owner pill round-2: CANCEL MUST NOT
     * TRANSCRIBE): the X button is pure cancel in every active mode —
     * RECORDING discards (never finish/commit, never a WORKING spinner,
     * never an empty-text commit), WORKING discards, IDLE no-ops.
     * Committing from the bubble happens only via the mic release
     * (pressUp → finish). Per-mode commit semantics live in [Dictation].
     */
    enum class StopAction { CANCEL, NONE }

    fun stopAction(state: Dictation.State): StopAction =
        when (state) {
            Dictation.State.RECORDING -> StopAction.CANCEL
            Dictation.State.WORKING -> StopAction.CANCEL
            Dictation.State.IDLE -> StopAction.NONE
        }

    /**
     * Grip drag-down cancel (mirrors the IME slide-down-cancel): a
     * downward grip drag past threshold while active discards the
     * utterance. Pure so tests pin the gesture (down = cancel, up /
     * sideways = plain move, idle = never).
     */
    fun shouldCancelOnGripDragDown(
        dyPx: Float,
        thresholdPx: Float,
        active: Boolean,
    ): Boolean =
        active && dyPx >= thresholdPx

    /**
     * Drop-on-X cancel (drag the mic onto the stop button): the release
     * point inside the stop hit-rect discards instead of finishing.
     */
    fun isDropOnCancel(x: Float, y: Float, stop: HitRectF): Boolean =
        x >= stop.left && x <= stop.right && y >= stop.top && y <= stop.bottom

    /** Float rect (raw screen px) for drop-on-X hit testing. */
    data class HitRectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Bottom-center visualizer-pill offset: sits [marginPx] above the
     * taller of the IME and the nav bar, else above the screen bottom.
     * IME insets read 0 when the overlay cannot see them — the max()
     * degrades gracefully to the nav-bar fallback.
     */
    fun vizPillYOffset(imeBottomPx: Int, navBottomPx: Int, marginPx: Int): Int =
        maxOf(imeBottomPx, navBottomPx) + marginPx

    /**
     * First-run move hint: shown once until the user has moved the
     * bubble via the grip (persisted by the service). Pure so tests
     * pin the once-only rule.
     */
    fun shouldShowMoveHint(hasSeenHint: Boolean): Boolean = !hasSeenHint

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
