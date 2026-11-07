package com.mywhisper.mobile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background pack download (WorkManager CoroutineWorker).
 *
 * Reuses the existing verified download paths — `LocalSttModels.download`
 * for STT packs, `LocalLlmManager.downloadModel` for the LLM `.task` —
 * so SHA-verify + fail-closed + atomic `.part` rename semantics are
 * identical to the in-Activity path. The Activity path is untouched;
 * this worker is the app-open-not-required alternative the rows can
 * observe via `WorkManager.getWorkInfosForUniqueWorkLiveData`.
 *
 * - Foreground progress on the OWN `mw-downloads` channel (never the
 *   unified FGS row): per-model bar + Cancel action.
 * - Constraints/backoff come from [ModelDownloadManager] (unmetered,
 *   battery-not-low, exponential backoff).
 * - Cancel/stop: the in-flight worker aborts at the next 64KB
 *   chunk (both download callbacks throw on `isStopped`); partials
 *   are KEPT here — explicit Cancel wipes them synchronously in
 *   [ModelDownloadManager.cancel], so cancel truly resets while
 *   kills/errors keep resumable partials for the HTTP-Range path.
 *   Transient errors: `Result.retry` until
 *   [ModelDownloadSchedule.MAX_ATTEMPTS], then failure (row pins
 *   FAILED, Retry re-enqueues and resumes). Explicit model Delete
 *   (trash row) is the only other path that wipes files + partials.
 * - App-kill: WorkManager re-runs; surviving `.part` files resume
 *   via HTTP Range (both download fns), verified finals are skipped
 *   — a kill can never promote an unverified pack (trailing
 *   SHA-verify + atomic rename hold on every path).
 */
class ModelDownloadWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val kind = inputData.getString(ModelDownloadSchedule.KEY_KIND)
        val modelId = inputData.getString(ModelDownloadSchedule.KEY_MODEL_ID)
        val label = inputData.getString(ModelDownloadSchedule.KEY_LABEL).orEmpty()
        val workName = ModelDownloadSchedule.workNameFor(kind.orEmpty(), modelId.orEmpty())
        val notifId = ModelDownloadSchedule.notifIdFor(workName)
        // Deep-link target: the tap opens the right page (STT models
        // vs LLM models) from cold start via MainActivity tab routing.
        val target = ModelDownloadSchedule.targetForKind(kind)
        ModelDownloadNotifications.ensureChannel(applicationContext)
        // Resume-first: surviving `.part` files from a kill or a
        // cancel continue via HTTP Range in the download fns below;
        // verified finals are skipped. Nothing is deleted here.
        // Foreground promotion can throw on API 31+ when the work
        // starts while the app is backgrounded
        // (ForegroundServiceStartNotAllowedException). That must
        // degrade to notification-only progress — never escape
        // doWork into a silent retry loop (see
        // ModelDownloadSchedule.degradeToNotificationOnly).
        try {
            setForegroundAsync(foregroundInfo(label.ifBlank { modelId.orEmpty() }, 0L, null, workName, notifId, target, kind.orEmpty(), modelId.orEmpty())).get()
        } catch (e: Exception) {
            if (!ModelDownloadSchedule.degradeToNotificationOnly(e)) throw e
        }
        try {
            when (kind) {
                ModelDownloadSchedule.KIND_STT -> downloadStt(modelId, label, workName, notifId, target)
                ModelDownloadSchedule.KIND_LLM -> downloadLlm(label, workName, notifId, target)
                else -> return@withContext Result.failure()
            }
            if (isStopped) {
                return@withContext Result.failure()
            }
            ModelDownloadNotifications.notify(
                applicationContext, notifId,
                ModelDownloadNotifications.buildComplete(applicationContext, label.ifBlank { modelId.orEmpty() }, target),
            )
            Result.success()
        } catch (e: CancellationException) {
            ModelDownloadNotifications.cancel(applicationContext, notifId)
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                ModelDownloadNotifications.cancel(applicationContext, notifId)
                return@withContext Result.failure()
            }
            // User-cancel surfaces as failure (no retry); transient
            // errors retry with backoff until the attempt cap.
            if (runAttemptCount >= ModelDownloadSchedule.MAX_ATTEMPTS - 1) {
                val detail = e.message.orEmpty()
                ModelDownloadNotifications.notify(
                    applicationContext, notifId,
                    ModelDownloadNotifications.buildFailed(
                        applicationContext,
                        label.ifBlank { modelId.orEmpty() },
                        detail,
                        target,
                    ),
                )
                return@withContext Result.failure(
                    workDataOf(ModelDownloadSchedule.OUTPUT_ERROR to detail),
                )
            }
            return@withContext Result.retry()
        }
    }

    private suspend fun downloadStt(
        modelId: String?,
        label: String,
        workName: String,
        notifId: Int,
        target: String,
    ) {
        val entry = LocalSttModels.MODELS.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("unknown stt model $modelId")
        LocalSttModels.download(applicationContext.filesDir, entry) { r, t ->
            if (isStopped) throw CancellationException("cancelled")
            reportProgress(label.ifBlank { entry.name }, r, t, workName, notifId, target, ModelDownloadSchedule.KIND_STT, entry.id)
        }
    }

    private suspend fun downloadLlm(
        label: String,
        workName: String,
        notifId: Int,
        target: String,
    ) {
        val url = inputData.getString(ModelDownloadSchedule.KEY_URL)
            ?: throw IllegalArgumentException("llm asset lacks url")
        val sha = inputData.getString(ModelDownloadSchedule.KEY_SHA)
            ?: throw IllegalArgumentException("llm asset lacks sha")
        val size = inputData.getLong(ModelDownloadSchedule.KEY_SIZE, 0L)
        val assetId = inputData.getString(ModelDownloadSchedule.KEY_MODEL_ID).orEmpty()
        if (MainActivity.isPlaceholderSha(sha)) {
            throw IllegalStateException("no sha pinned for $assetId")
        }
        val asset = LocalLlmManager.ModelAsset(assetId, url, sha, size)
        // Per-asset slot (multi-pack coexist): same dest the row
        // downloads to and verifies (`MainActivity.llmTaskFileFor`),
        // so completion flips the row to PRESENT.
        val dest = MainActivity.llmTaskFileFor(applicationContext.filesDir, assetId)
        LocalLlmManager.downloadModel(asset, dest) { r, t ->
            if (isStopped) throw CancellationException("cancelled")
            reportProgress(label.ifBlank { assetId }, r, t, workName, notifId, target, ModelDownloadSchedule.KIND_LLM, assetId)
        }
    }

    private fun reportProgress(
        label: String,
        received: Long,
        total: Long?,
        workName: String,
        notifId: Int,
        target: String,
        kind: String,
        modelId: String,
    ) {
        try {
            setProgressAsync(workDataOf(ModelDownloadSchedule.PROG_RECEIVED to received, ModelDownloadSchedule.PROG_TOTAL to (total ?: -1L)))
        } catch (_: Exception) {
        }
        try {
            ModelDownloadNotifications.notify(
                applicationContext, notifId,
                ModelDownloadNotifications.buildProgress(applicationContext, label, received, total, workName, notifId, target, kind, modelId),
            )
        } catch (_: Exception) {
        }
    }

    private fun foregroundInfo(
        label: String,
        received: Long,
        total: Long?,
        workName: String,
        notifId: Int,
        target: String,
        kind: String,
        modelId: String,
    ): ForegroundInfo =
        ForegroundInfo(
            notifId,
            ModelDownloadNotifications.buildProgress(applicationContext, label, received, total, workName, notifId, target, kind, modelId),
        )
}
