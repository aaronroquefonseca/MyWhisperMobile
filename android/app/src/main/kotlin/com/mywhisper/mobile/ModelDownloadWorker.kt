package com.mywhisper.mobile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
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
 * - Cancel/stop: delete partials, `Result.failure` (no retry on
 *   user cancel). Transient errors: `Result.retry` until
 *   [ModelDownloadSchedule.MAX_ATTEMPTS], then failure (row pins
 *   FAILED, Retry re-enqueues).
 * - App-kill: WorkManager re-runs; partials are deleted on start
 *   (clean restart) and verified finals are skipped — a kill can
 *   never promote an unverified pack.
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
        ModelDownloadNotifications.ensureChannel(applicationContext)
        // Clean restart first: drop stale partials from a kill, then
        // the download fns skip already-verified finals.
        try {
            deletePartials(kind, modelId)
        } catch (_: Exception) {
        }
        // Foreground promotion can throw on API 31+ when the work
        // starts while the app is backgrounded
        // (ForegroundServiceStartNotAllowedException). That must
        // degrade to notification-only progress — never escape
        // doWork into a silent retry loop (see
        // ModelDownloadSchedule.degradeToNotificationOnly).
        try {
            setForegroundAsync(foregroundInfo(label.ifBlank { modelId.orEmpty() }, 0L, null, workName, notifId)).get()
        } catch (e: Exception) {
            if (!ModelDownloadSchedule.degradeToNotificationOnly(e)) throw e
        }
        try {
            when (kind) {
                ModelDownloadSchedule.KIND_STT -> downloadStt(modelId, label, workName, notifId)
                ModelDownloadSchedule.KIND_LLM -> downloadLlm(label, workName, notifId)
                else -> return@withContext Result.failure()
            }
            if (isStopped) {
                try {
                    deletePartials(kind, modelId)
                } catch (_: Exception) {
                }
                return@withContext Result.failure()
            }
            ModelDownloadNotifications.notify(
                applicationContext, notifId,
                ModelDownloadNotifications.buildComplete(applicationContext, label.ifBlank { modelId.orEmpty() }),
            )
            Result.success()
        } catch (e: CancellationException) {
            try {
                deletePartials(kind, modelId)
            } catch (_: Exception) {
            }
            ModelDownloadNotifications.cancel(applicationContext, notifId)
            throw e
        } catch (e: Exception) {
            if (isStopped) {
                try {
                    deletePartials(kind, modelId)
                } catch (_: Exception) {
                }
                ModelDownloadNotifications.cancel(applicationContext, notifId)
                return@withContext Result.failure()
            }
            // User-cancel surfaces as failure (no retry); transient
            // errors retry with backoff until the attempt cap.
            if (runAttemptCount >= ModelDownloadSchedule.MAX_ATTEMPTS - 1) {
                try {
                    deletePartials(kind, modelId)
                } catch (_: Exception) {
                }
                val detail = e.message.orEmpty()
                ModelDownloadNotifications.notify(
                    applicationContext, notifId,
                    ModelDownloadNotifications.buildFailed(
                        applicationContext,
                        label.ifBlank { modelId.orEmpty() },
                        detail,
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
    ) {
        val entry = LocalSttModels.MODELS.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("unknown stt model $modelId")
        LocalSttModels.download(applicationContext.filesDir, entry) { r, t ->
            if (isStopped) throw CancellationException("cancelled")
            reportProgress(label.ifBlank { entry.name }, r, t, workName, notifId)
        }
    }

    private suspend fun downloadLlm(
        label: String,
        workName: String,
        notifId: Int,
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
            reportProgress(label.ifBlank { assetId }, r, t, workName, notifId)
        }
    }

    private fun reportProgress(
        label: String,
        received: Long,
        total: Long?,
        workName: String,
        notifId: Int,
    ) {
        try {
            setProgressAsync(workDataOf(ModelDownloadSchedule.PROG_RECEIVED to received, ModelDownloadSchedule.PROG_TOTAL to (total ?: -1L)))
        } catch (_: Exception) {
        }
        try {
            ModelDownloadNotifications.notify(
                applicationContext, notifId,
                ModelDownloadNotifications.buildProgress(applicationContext, label, received, total, workName, notifId),
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
    ): ForegroundInfo =
        ForegroundInfo(
            notifId,
            ModelDownloadNotifications.buildProgress(applicationContext, label, received, total, workName, notifId),
        )

    /** Best-effort partial cleanup (cancel + kill-restart paths). */
    private fun deletePartials(kind: String?, modelId: String?) {
        val filesDir = applicationContext.filesDir
        when (kind) {
            ModelDownloadSchedule.KIND_STT -> {
                // Partial-only sweep: cancel/kill-restart must KEEP
                // already-verified finals (the download fns skip them
                // on resume). Never delete finals here.
                val entry = LocalSttModels.MODELS.firstOrNull { it.id == modelId } ?: return
                val dir = LocalSttModels.dirFor(filesDir, entry)
                dir.listFiles { f -> ModelDownloadSchedule.isPartialName(f.name) }
                    ?.forEach { try { it.delete() } catch (_: Exception) {} }
            }
            ModelDownloadSchedule.KIND_LLM -> {
                // Per-asset slot (multi-pack coexist): sweep THIS
                // asset's partial only — a dir-wide sweep would wipe a
                // concurrently-downloading sibling's `.part.<other-id>`.
                val assetId = modelId.orEmpty()
                val dest = MainActivity.llmTaskFileFor(filesDir, assetId)
                try {
                    val dir = File(dest.parent ?: return)
                    dir.listFiles { f ->
                        ModelDownloadSchedule.isOwnLlmPart(f.name, dest.name, assetId)
                    }?.forEach { try { it.delete() } catch (_: Exception) {} }
                } catch (_: Exception) {
                }
            }
        }
    }
}
