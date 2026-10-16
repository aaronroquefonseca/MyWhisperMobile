package com.mywhisper.mobile

/**
 * ONE provider-test pattern for every chain row (STT + LLM, local
 * and remote kinds): one button, same placement, same inline
 * reporting (`latency + excerpt/status`).
 *
 * Per-kind probes (injected lambdas, no engines touched):
 * - Local STT: decode [samplePcm16] through the Local path.
 *   The probe returns decoded text, or a non-blank status
 *   (`pack ok · N chunks`) when the engine ran on silence —
 *   PASS needs a non-blank return, same rule as every kind.
 * - Server STT: lightweight endpoint check (any HTTP response =
 *   reachable; network throw = FAIL). `ws(s)` has no HTTP surface:
 *   scheme validation only, no heavy transcribe anywhere.
 * - Local LLM: existing [LocalLlmManager.runSelfTest] via
 *   [fromSelfTest] (same [Result], no second pattern).
 * - Remote LLM: minimal ping/generate probe (HTTP GET, short
 *   timeout, injected).
 *
 * Pure JVM (no Context, no org.json): probe lambdas are supplied
 * by the UI layer so unit tests inject stubs.
 */
object ProviderTest {
    /** Inline excerpt cap (matches the LLM self-test width). */
    const val EXCERPT_MAX = 120

    /** Budget for server/remote probes (lightweight, no transcribe). */
    const val SERVER_TIMEOUT_MS = 10_000L

    /** Budget for on-device probes (model load + decode). */
    const val LOCAL_TIMEOUT_MS = 60_000L

    /**
     * Unified row outcome: PASS = ran + non-blank + under timeout
     * with no error. Excerpt carries the reply/status, else error.
     */
    data class Result(
        val passed: Boolean,
        val latencyMs: Long,
        val excerpt: String,
        val error: String? = null,
        val timedOut: Boolean = false,
    )

    /** Config errors for the STT kinds (pure, UI maps to strings). */
    enum class SttError { NONE, NEED_URL, NEED_MODEL, BAD_URL, BAD_BASE }

    /** Config errors for the LLM kinds (pure, UI maps to strings). */
    enum class LlmError { NONE, NEED_URL, NEED_MODEL, BAD_URL }

    /** Single-line excerpt, capped with an ellipsis. */
    fun excerpt(output: String, maxChars: Int = EXCERPT_MAX): String {
        val flat = output.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        return flat.take(maxChars).trimEnd() + "…"
    }

    /**
     * THE mapping: every kind reports through here. PASS = no error
     * + under timeout + non-blank output.
     */
    fun mapResult(
        output: String?,
        latencyMs: Long,
        timeoutMs: Long,
        error: String?,
    ): Result {
        val timedOut = latencyMs > timeoutMs
        val excerpt = when {
            !output.isNullOrBlank() -> excerpt(output)
            !error.isNullOrBlank() -> excerpt(error)
            else -> ""
        }
        val passed = error == null && !timedOut && !output.isNullOrBlank()
        return Result(passed, latencyMs, excerpt, error, timedOut)
    }

    /** Local STT mapping: decode-sample outcome, same rule. */
    fun mapLocalSttResult(
        output: String?,
        latencyMs: Long,
        timeoutMs: Long = LOCAL_TIMEOUT_MS,
        error: String? = null,
    ): Result = mapResult(output, latencyMs, timeoutMs, error)

    /** Server STT mapping: endpoint-check outcome, same rule. */
    fun mapServerSttResult(
        output: String?,
        latencyMs: Long,
        timeoutMs: Long = SERVER_TIMEOUT_MS,
        error: String? = null,
    ): Result = mapResult(output, latencyMs, timeoutMs, error)

    /** Remote LLM mapping: ping/generate outcome, same rule. */
    fun mapRemoteLlmResult(
        output: String?,
        latencyMs: Long,
        timeoutMs: Long = SERVER_TIMEOUT_MS,
        error: String? = null,
    ): Result = mapResult(output, latencyMs, timeoutMs, error)

    /**
     * Unite the existing Local LLM self-test: same [Result], no
     * second pattern. Field-for-field copy.
     */
    fun fromSelfTest(r: LocalLlmManager.SelfTestResult): Result =
        Result(
            passed = r.passed,
            latencyMs = r.latencyMs,
            excerpt = r.excerpt,
            error = r.error,
            timedOut = r.timedOut,
        )

    /**
     * Generic probe runner: measures wall-clock, catches throws,
     * maps via [mapResult]. Callers inject decode/HTTP lambdas.
     */
    fun runProbe(
        timeoutMs: Long = SERVER_TIMEOUT_MS,
        probe: () -> String?,
    ): Result {
        val start = System.nanoTime()
        return try {
            val out = probe()
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapResult(out, latencyMs, timeoutMs, null)
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapResult(null, latencyMs, timeoutMs, e.message ?: "failed")
        }
    }

    /**
     * 1s 440Hz sine PCM @16kHz decode sample for Local STT probes.
     * Deterministic (no asset); the probe reports chunk/engine
     * status so silence still yields a non-blank PASS excerpt.
     */
    fun samplePcm16(sampleRate: Int = 16000): ShortArray {
        val n = sampleRate
        return ShortArray(n) { i ->
            (10_000.0 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt()
                .toShort()
        }
    }

    /**
     * STT config validation (replaces the dialog-local check so rows
     * and dialogs share one rule). SelfHosted needs URL + model;
     * Cloud needs model (blank URL = vendor default). Pure.
     */
    fun validateStt(kind: String, url: String, model: String): SttError {
        val u = url.trim()
        val m = model.trim()
        if (kind == "Local") return SttError.NONE
        if (m.isEmpty()) return SttError.NEED_MODEL
        if (kind == "SelfHosted") {
            if (u.isEmpty()) return SttError.NEED_URL
            val scheme = u.substringBefore("://", "")
            if (!u.contains("://") || scheme !in setOf("http", "https", "ws", "wss")) {
                return SttError.BAD_URL
            }
            return SttError.NONE
        }
        // Cloud (+ unknown): blank URL = vendor default.
        if (u.isNotEmpty()) {
            val scheme = u.substringBefore("://", "")
            if (!u.contains("://") || scheme !in setOf("http", "https")) {
                return SttError.BAD_BASE
            }
        }
        return SttError.NONE
    }

    /**
     * LLM config validation. Ollama needs URL + model; Cloud needs
     * model (blank URL = vendor default). Pure.
     */
    fun validateLlm(kind: String, url: String, model: String): LlmError {
        val u = url.trim()
        val m = model.trim()
        if (kind == "Local") return LlmError.NONE
        if (m.isEmpty()) return LlmError.NEED_MODEL
        if (kind == "Ollama") {
            if (u.isEmpty()) return LlmError.NEED_URL
            val scheme = u.substringBefore("://", "")
            if (!u.contains("://") || scheme !in setOf("http", "https")) {
                return LlmError.BAD_URL
            }
            return LlmError.NONE
        }
        // Cloud (+ unknown): blank URL = vendor default.
        if (u.isNotEmpty()) {
            val scheme = u.substringBefore("://", "")
            if (!u.contains("://") || scheme !in setOf("http", "https")) {
                return LlmError.BAD_URL
            }
        }
        return LlmError.NONE
    }

    /**
     * Local test gate (STT + LLM share it): verified pack on a
     * runner build. Fail-closed. [packVerified] is the caller's
     * `packState == PRESENT` (keeps this free of UI enums).
     */
    fun localTestAllowed(runnerSupported: Boolean, packVerified: Boolean): Boolean =
        runnerSupported && packVerified

    /** Why a local test button stays disabled (pure, UI maps to strings). */
    enum class LocalBlocked { NONE, NEED_RUNNER, NEED_PACK }

    /**
     * Disabled-reason for the local test buttons: no runner (lite
     * flavor / no bridge) outranks a missing pack. NONE = enabled.
     * Pure for unit tests.
     */
    fun localTestBlocked(runnerSupported: Boolean, packVerified: Boolean): LocalBlocked =
        when {
            !runnerSupported -> LocalBlocked.NEED_RUNNER
            !packVerified -> LocalBlocked.NEED_PACK
            else -> LocalBlocked.NONE
        }

    /**
     * Whether the disabled-reason line renders under the test button.
     * Renders even when a stale result lingers (config edited after a
     * run): the blocker is explicit, never hidden behind old output.
     * Pure for unit tests.
     */
    fun showDisabledReason(enabled: Boolean, busy: Boolean, reason: String?): Boolean =
        !enabled && !busy && reason != null

    /** Server STT button goes live when the config validates. */
    fun sttServerTestAllowed(kind: String, url: String, model: String): Boolean =
        validateStt(kind, url, model) == SttError.NONE

    /** Remote LLM button goes live when the config validates. */
    fun llmRemoteTestAllowed(kind: String, url: String, model: String): Boolean =
        validateLlm(kind, url, model) == LlmError.NONE

    // -- Test-row z-order / bounds contract (layout unit-tested) --------
    //
    // Owner phone test: the provider test button rendered IN FRONT OF
    // overlapping content. Cause: the whole chain item (Card + test
    // row) shared the drag lift (zIndex 1 + shadow + 1.015 scale), so
    // the button lifted with the Card and painted over sibling rows
    // during reorder/animation; the result line had no weight so long
    // excerpts overflowed the row bounds instead of wrapping.
    //
    // Contract: only the Card lifts on drag; the test row always sits
    // at z 0, fills the row width, clips to its bounds, and the result
    // text takes the remaining weight (wraps, never overflows) — the
    // logical block order is identical in LTR and RTL (Row mirrors
    // visually, weight keeps the button at row-start either way).

    /** Z the chain Card lifts to while dragged (rest = 0). */
    const val DRAG_LIFT_Z = 1f

    /** Z a resting row (Card or test row) paints at. */
    const val ROW_REST_Z = 0f

    /** Z the provider test row paints at — never lifts, even on drag. */
    const val TEST_ROW_Z = 0f

    /** Z for the chain Card: lifts only while its own row drags. */
    fun chainCardZIndex(isDragging: Boolean): Float =
        if (isDragging) DRAG_LIFT_Z else ROW_REST_Z

    /** Z for the provider test row: always 0, dragging or not. */
    @Suppress("UNUSED_PARAMETER")
    fun testRowZIndex(isDragging: Boolean): Float = TEST_ROW_Z

    /** The test row clips to its bounds (button respects row bounds). */
    fun testRowClipsToBounds(): Boolean = true

    /** Weight the inline result text takes (wraps, never overflows). */
    fun testRowResultWeight(): Float = 1f

    /**
     * Logical test-row block order: button, gap, result. Identical
     * for LTR and RTL (Compose Row mirrors placement visually; the
     * contract pins that no branch reorders blocks per direction).
     * Pure for unit tests.
     */
    @Suppress("UNUSED_PARAMETER")
    fun testRowBlockOrder(isRtl: Boolean): List<String> =
        listOf("button", "gap", "result")

    // -- Chain-item stacking / no-overlap contract -------------------
    //
    // Owner phone test (correction): the test button OVERLAPPED
    // neighboring content — not paint order but layout. Cause: the
    // Local branches emitted Card + test row as sequential children
    // of the item Box, and Box children stack on top of each other.
    // Every chain item lays Card first, test row (or its unavailable
    // reason) below it, in a vertical container — nothing overlaps
    // to begin with. Within the test row, the button keeps its
    // intrinsic width, the gap is fixed, and the result takes the
    // remaining weight and wraps — so the row total never exceeds
    // the row width on any screen width, LTR or RTL (widths are
    // direction-independent).

    /** Chain-item block order: Card first, test row below. Never reversed. */
    fun chainItemBlocks(): List<String> = listOf("card", "test")

    /** Chain items stack vertically (Column) — Box stacking would overlap. */
    fun chainItemStacksVertically(): Boolean = true

    /** Fixed gap between test button and result (dp). */
    const val TEST_ROW_GAP_DP = 8f

    /**
     * True when the test row overflows [rowWidthDp]: with the result
     * weighted, overflow happens only if the button + gap alone
     * exceed the row (the button itself escapes its container).
     * Widths are direction-independent, so LTR and RTL share the
     * verdict. Pure for unit tests.
     */
    fun testRowOverflows(
        rowWidthDp: Float,
        buttonWidthDp: Float,
        gapDp: Float = TEST_ROW_GAP_DP,
    ): Boolean = buttonWidthDp + gapDp > rowWidthDp
}
