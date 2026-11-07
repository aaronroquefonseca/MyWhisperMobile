package com.mywhisper.mobile

/**
 * Background-download scheduling policy for on-device model packs.
 *
 * Choice: WorkManager (not a hand-rolled foreground service).
 * Rationale: packs are 44MB–2.5GB and must survive app-kill/Doze.
 * On Android 10+ (esp. 12+ FGS-start denial, 14+ FGS-type gates) a
 * background `startForegroundService` from an alarm/boot/worker is
 * denied; WorkManager owns Doze-aware scheduling, the
 * unmetered/battery-not-low constraints, and exponential-backoff
 * retry + kill-resume. The worker still promotes itself to a
 * foreground notification (own `mw-downloads` channel) so the user
 * sees per-model progress + Cancel while the bytes move.
 *
 * This file is pure JVM (no Android imports) so the policy —
 * unique-work naming, backoff, partial-file handling, progress math —
 * is unit-tested. Android wiring lives in [ModelDownloadWorker],
 * [ModelDownloadManager] and [ModelDownloadNotifications].
 *
 * Safety invariants (kill/cancel must never corrupt packs):
 * - Final files are promoted by atomic `.part` rename ONLY after a
 *   per-file SHA-256 verify (see `LocalSttModels.download` /
 *   `LocalLlmManager.downloadModel`); a kill mid-flight leaves only
 *   `.part` files behind.
 * - Restart after kill is ALWAYS a clean restart: delete partials,
 *   re-enqueue, never promote a partial to PRESENT.
 * - Cancel deletes partials, keeps already-verified files, and the
 *   row falls back to MISSING/MISMATCH via the existing pack-state
 *   derivation (no separate error pin needed).
 */
object ModelDownloadSchedule {
    /** WorkManager input keys (also read by the worker). */
    const val KEY_KIND = "mw_dl_kind"
    const val KEY_MODEL_ID = "mw_dl_model_id"
    const val KEY_LABEL = "mw_dl_label"
    const val KEY_URL = "mw_dl_url"
    const val KEY_SHA = "mw_dl_sha"
    const val KEY_SIZE = "mw_dl_size"
    const val KEY_RUNTIME = "mw_dl_runtime"

    /** Progress output keys (observed by the row progress hooks). */
    const val PROG_RECEIVED = "mw_dl_received"
    const val PROG_TOTAL = "mw_dl_total"

    const val KIND_STT = "stt"
    const val KIND_LLM = "llm"

    /** Notification tap target: which page the tap deep-links to. */
    const val EXTRA_TARGET = "mw_dl_target"
    const val TARGET_STT = "stt"
    const val TARGET_LLM = "llm"

    /**
     * Deep-link target for a download [kind]: LLM packs open the
     * Text-cleanup page, everything else the Transcription page.
     * Pure for unit tests.
     */
    @JvmStatic
    fun targetForKind(kind: String?): String =
        if (kind == KIND_LLM) TARGET_LLM else TARGET_STT

    const val WORK_PREFIX = "mw-dl"

    /** Initial backoff for WorkManager (exponential from here). */
    const val BACKOFF_MS = 10_000L

    /** Backoff cap: 15 minutes. */
    const val BACKOFF_MAX_MS = 15 * 60_000L

    /** Max attempts before the row pins FAILED (Retry re-enqueues). */
    const val MAX_ATTEMPTS = 5

    /** Pure constraint descriptor; mapped 1:1 to WorkManager Constraints. */
    data class BgConstraints(
        val requireUnmetered: Boolean = true,
        val requireBatteryNotLow: Boolean = true,
        val requireStorageNotLow: Boolean = true,
    ) {
        companion object {
            val DEFAULT = BgConstraints()
        }
    }

    /**
     * Unique work name per model: one in-flight download per pack.
     * Sanitized to `[a-z0-9-_]` so it is safe as a WorkManager unique
     * name and as the Cancel-intent extra.
     */
    @JvmStatic
    fun workNameFor(kind: String, id: String): String {
        fun clean(s: String): String =
            s.lowercase().map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }.joinToString("")
                .trim('-').ifEmpty { "model" }
        return "$WORK_PREFIX-${clean(kind)}-${clean(id)}"
    }

    /** Stable notification id per unit of work (own channel, no clashes). */
    @JvmStatic
    fun notifIdFor(workName: String): Int =
        1000 + (workName.hashCode() and 0x7fffffff) % 8000

    /**
     * Exponential backoff for retry attempt [attempt] (0-based):
     * 10s, 20s, 40s, … capped at 15m. Pure for unit tests; the worker
     * configures the same via `BackoffPolicy.EXPONENTIAL`.
     */
    @JvmStatic
    fun backoffDelayMs(attempt: Int): Long {
        if (attempt < 0) return BACKOFF_MS
        var d = BACKOFF_MS
        repeat(attempt.coerceAtMost(20)) {
            if (d >= BACKOFF_MAX_MS / 2) return BACKOFF_MAX_MS
            d *= 2
        }
        return d.coerceAtMost(BACKOFF_MAX_MS)
    }

    /** True after [MAX_ATTEMPTS] failed attempts: pin FAILED, stop retrying. */
    @JvmStatic
    fun shouldGiveUp(runAttempt: Int): Boolean = runAttempt >= MAX_ATTEMPTS - 1

    /**
     * Foreground-promotion failure policy: ALWAYS degrade to
     * notification-only progress, never fail the download.
     *
     * `setForeground` throws on API 31+ when the work starts while
     * the app is backgrounded (`ForegroundServiceStartNotAllowed…`);
     * an uncaught throw escapes `doWork` and WorkManager silently
     * retries a healthy download in a loop with no notification.
     * Degrading keeps the bytes moving (progress still paints via
     * `NotificationManager` + the `mw_dl_received/mw_dl_total` keys)
     * and preserves the kill-resume must-hold: the download fns are
     * interruption-safe regardless of foreground state.
     * Pure for unit tests (decision only, no Android calls).
     */
    @JvmStatic
    fun degradeToNotificationOnly(@Suppress("UNUSED_PARAMETER") e: Throwable): Boolean = true

    /**
     * Restart policy after an app-kill: ALWAYS clean-restart.
     * The worker deletes `.part` files on start and re-downloads;
     * verified finals are skipped by the existing download fns.
     */
    @JvmStatic
    fun restartAfterKill(): Boolean = true

    /** STT partial names for [entry] (deleted on cancel/kill-restart). */
    @JvmStatic
    fun sttPartNames(fileNames: List<String>): List<String> =
        fileNames.map { "$it.part" }

    /** LLM partial name for one asset (mirrors `partFileFor`). */
    @JvmStatic
    fun llmPartName(destName: String, assetId: String): String =
        "$destName.part.$assetId"

    /** Prefix shared by every asset partial for one LLM dest file. */
    @JvmStatic
    fun llmPartPrefix(destName: String): String =
        "$destName.part."

    /**
     * True when [fileName] is THIS asset's partial. Scoped exact
     * match (blank asset id falls back to the dest prefix for legacy
     * cleanup) so a sweep for one asset can never wipe a
     * concurrently-downloading sibling. Pure for unit tests.
     */
    @JvmStatic
    fun isOwnLlmPart(fileName: String, destName: String, assetId: String): Boolean =
        if (assetId.isEmpty()) fileName.startsWith(llmPartPrefix(destName))
        else fileName == llmPartName(destName, assetId)

    /** True for temp partials (never promoted to PRESENT). */
    @JvmStatic
    fun isPartialName(name: String): Boolean =
        ".part" in name

    /**
     * Reverse [workNameFor]: (kind, modelId) for the notification
     * Cancel path. Fallback-grade only: [clean] maps dots to dashes,
     * so dotted ids (both Nemotron entries) do NOT round-trip — the
     * live Cancel path carries explicit extras instead. Returns null
     * on malformed input. Pure for unit tests.
     */
    @JvmStatic
    fun kindIdFromWorkName(workName: String): Pair<String, String>? {
        val rest = workName.removePrefix("$WORK_PREFIX-")
        if (rest == workName) return null
        val dash = rest.indexOf('-')
        if (dash <= 0) return null
        val kind = rest.substring(0, dash)
        val id = rest.substring(dash + 1)
        if ((kind == KIND_STT || kind == KIND_LLM) && id.isNotEmpty()) return kind to id
        return null
    }

    /**
     * Determinate progress percent, null when the total is unknown
     * (notification + row show indeterminate). Pure for unit tests.
     */
    @JvmStatic
    fun progressPercent(receivedBytes: Long, totalBytes: Long?): Int? {
        if (totalBytes == null || totalBytes <= 0 || receivedBytes < 0) return null
        return ((receivedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) * 100).toInt()
    }

    /**
     * Row state while the background work is in flight: downloading
     * always wins (same precedence as the in-Activity path).
     */
    @JvmStatic
    fun rowWhileRunning(
        packPresent: Boolean,
        packMismatch: Boolean,
    ): LocalModelDownload.RowState =
        LocalModelDownload.resolve(packPresent, packMismatch, true, null)

    /** Terminal-failure detail carried in `Result.failure` output data. */
    const val OUTPUT_ERROR = "mw_dl_error"

    /** Row action the Activity wiring takes per WorkManager state. */
    enum class WorkRowAction { TRACK, DONE, FAILED, CLEARED }

    /**
     * Map a `WorkInfo.State` name to the row action. Matched by name
     * (not the Android enum) so the wiring logic is unit-tested on
     * plain JVM: waiting/running states keep tracking progress,
     * SUCCEEDED refreshes the pack derivation, FAILED pins the
     * error, and CANCELLED (or anything unknown) clears back to the
     * pack derivation without pinning — cancel must never read as
     * FAILED. Pure for unit tests.
     */
    @JvmStatic
    fun actionForWorkState(stateName: String): WorkRowAction = when (stateName) {
        "ENQUEUED", "BLOCKED", "RUNNING" -> WorkRowAction.TRACK
        "SUCCEEDED" -> WorkRowAction.DONE
        "FAILED" -> WorkRowAction.FAILED
        else -> WorkRowAction.CLEARED
    }
}
