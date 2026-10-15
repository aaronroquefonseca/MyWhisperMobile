package com.mywhisper.mobile

/**
 * Row-state machine for the Local STT/LLM download rows (desktop
 * `fill_stt_models` parity: install state + Download/Delete/Retry
 * actions + in-place progress). Pure JVM, unit-tested.
 *
 * States: MISSING (nothing usable) / DOWNLOADING (worker in flight)
 * / PRESENT (verified, toggle live) / MISMATCH (files fail verify,
 * re-download) / FAILED (last download threw, Retry offered).
 * Fail-closed: only PRESENT enables the toggle.
 */
object LocalModelDownload {
    enum class RowState { MISSING, DOWNLOADING, PRESENT, MISMATCH, FAILED }

    /**
     * Resolve the row state. Precedence: an in-flight download wins
     * over everything (progress shows in place); otherwise a terminal
     * error pins FAILED until Retry/Delete clears it; otherwise the
     * verified-pack outcome decides.
     */
    @JvmStatic
    fun resolve(
        packPresent: Boolean,
        packMismatch: Boolean,
        downloading: Boolean,
        error: String?,
    ): RowState = when {
        downloading -> RowState.DOWNLOADING
        !error.isNullOrEmpty() -> RowState.FAILED
        packPresent -> RowState.PRESENT
        packMismatch -> RowState.MISMATCH
        else -> RowState.MISSING
    }

    /** Fail-closed gate: the Local toggle goes live on PRESENT only. */
    @JvmStatic
    fun toggleAllowed(state: RowState): Boolean = state == RowState.PRESENT

    /**
     * Whole-MB progress fraction for a LinearProgressIndicator, null
     * when the total is unknown (indeterminate bar). Pure for tests.
     */
    @JvmStatic
    fun progressFraction(receivedBytes: Long, totalBytes: Long?): Float? {
        if (totalBytes == null || totalBytes <= 0 || receivedBytes < 0) return null
        return (receivedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    @JvmStatic
    fun progressMb(receivedBytes: Long, totalBytes: Long?): Pair<Long, Long?> =
        Pair(receivedBytes / 1_000_000, totalBytes?.let { it / 1_000_000 })
}
