package com.mywhisper.mobile

/**
 * Pure JVM-testable guards for the hardening leftovers. No Android
 * imports: Android classes (IME, views, receivers) delegate here so
 * the logic is unit-testable without Robolectric.
 */
object HardeningGuards {

    /**
     * Pill fade-out guard: the end action runs only when [current] is
     * still the view the animation started on (identity — rebuild
     * replaces pillBox).
     */
    fun pillEndActionApplies(current: Any?, target: Any?): Boolean =
        current !== null && current === target

    /** TalkBack dedup: announce a new non-empty note once. */
    fun shouldAnnounceError(note: String, lastAnnounced: String): Boolean =
        note.isNotEmpty() && note != lastAnnounced

    /**
     * Half-height of one visualizer bar, clamped to [0, mid - 1px].
     *
     * @param viewHeightPx current view height in px
     * @param peak 0..1 mic level
     * @param density display density
     */
    fun barHalfHeight(viewHeightPx: Float, peak: Float, density: Float): Float {
        val v = peak.coerceIn(0f, 1f)
        val laneMax = 18f * density
        val minHalf = 2f * density
        val cap = (viewHeightPx / 2f - 1f).coerceAtLeast(0f)
        return (minHalf + v * (laneMax - minHalf)).coerceIn(0f, cap)
    }

    /**
     * imeActive resolution after process death: memory defaults false,
     * so a persisted value wins; otherwise keep memory.
     */
    fun resolveImeActive(persisted: Boolean?, memActive: Boolean): Boolean =
        persisted ?: memActive

    /** Boot-retry backoff for retry N (1-based): 1m,2m,4m,8m, cap 15m. */
    fun bootRetryDelayMs(retryNumber: Int): Long {
        val shift = (retryNumber - 1).coerceIn(0, 10)
        return (RETRY_BASE_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }

    fun bootAttemptWithinLimit(attempt: Int, maxRetries: Int): Boolean =
        attempt < maxRetries

    /**
     * True when the failure chain smells like a locked credential
     * keystore. Name-based (no android.security refs).
     */
    fun isKeystoreLocked(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 10) {
            val n = t.javaClass.name
            if (n.contains("UserNotAuthenticated") ||
                n.contains("KeyPermanentlyInvalidated") ||
                n.contains("KeyStoreException") ||
                n.contains("UnrecoverableKey") ||
                n.contains("AEADBadTag") ||
                n.contains("MasterKey")
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    const val RETRY_BASE_MS = 60_000L
    const val RETRY_MAX_MS = 15 * 60_000L
}
