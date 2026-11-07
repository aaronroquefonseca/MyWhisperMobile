package com.mywhisper.mobile

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueue/cancel entry points for background pack downloads.
 *
 * - One unique work per model ([ModelDownloadSchedule.workNameFor],
 *   `ExistingWorkPolicy.REPLACE`): at most one writer ever touches a
 *   pack's `.part` files, and Retry/re-download after a terminal
 *   state actually re-runs (KEEP would keep the finished work and
 *   silently ignore the new request). Double-taps from the row
 *   cannot fork writers: the row guards with its busy flag.
 * - Constraints: unmetered + battery-not-low + storage-not-low —
 *   packs are 44MB–2.5GB; metered mobile data must never be burned
 *   silently. Retry rides WorkManager exponential backoff from
 *   [ModelDownloadSchedule.BACKOFF_MS].
 * - Rows drive the Download/Retry buttons straight into [enqueueSttModel]/
 *   [enqueueLlmAsset] and track progress via
 *   `WorkManager.getWorkInfosForUniqueWorkLiveData(workName)` plus
 *   the `mw_dl_received/mw_dl_total` keys (see
 *   `MainActivity.observeDownloadWork`); completion/deletion still
 *   flow through the SHA-verified pack derivation.
 */
object ModelDownloadManager {
    private const val TAG = "MwDl"
    private fun baseConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

    /** Enqueue an STT catalog-model download (replaces any prior work). */
    @JvmStatic
    fun enqueueSttModel(ctx: Context, modelId: String) {
        val entry = LocalSttModels.MODELS.firstOrNull { it.id == modelId } ?: return
        // Fail-closed lite gate: no runner → no download offered.
        try {
            if (!MainActivity.sttRunnerSupported()) return
        } catch (_: Exception) {
            return
        }
        val workName = ModelDownloadSchedule.workNameFor(ModelDownloadSchedule.KIND_STT, entry.id)
        val input = Data.Builder()
            .putString(ModelDownloadSchedule.KEY_KIND, ModelDownloadSchedule.KIND_STT)
            .putString(ModelDownloadSchedule.KEY_MODEL_ID, entry.id)
            .putString(ModelDownloadSchedule.KEY_LABEL, entry.name)
            .build()
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(input)
            .setConstraints(baseConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                ModelDownloadSchedule.BACKOFF_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(workName)
            .build()
        try {
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) {
        }
    }

    /** Enqueue an LLM `.task` download (fail-closed on placeholder SHA). */
    @JvmStatic
    fun enqueueLlmAsset(ctx: Context, asset: LocalLlmManager.ModelAsset) {
        if (MainActivity.isPlaceholderSha(asset.sha256)) return
        try {
            if (!MainActivity.llmRunnerSupported()) return
        } catch (_: Exception) {
            return
        }
        val workName = ModelDownloadSchedule.workNameFor(ModelDownloadSchedule.KIND_LLM, asset.id)
        val input = Data.Builder()
            .putString(ModelDownloadSchedule.KEY_KIND, ModelDownloadSchedule.KIND_LLM)
            .putString(ModelDownloadSchedule.KEY_MODEL_ID, asset.id)
            .putString(ModelDownloadSchedule.KEY_LABEL, asset.id)
            .putString(ModelDownloadSchedule.KEY_URL, asset.url)
            .putString(ModelDownloadSchedule.KEY_SHA, asset.sha256)
            .putLong(ModelDownloadSchedule.KEY_SIZE, asset.sizeBytes)
            .build()
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(input)
            .setConstraints(baseConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                ModelDownloadSchedule.BACKOFF_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(workName)
            .build()
        try {
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) {
        }
    }

    /**
     * Cancel one download AND wipe its partials synchronously: cancel
     * means reset (next Download starts from zero), while
     * interruptions (kill, error, timeout) keep resumable partials
     * for the HTTP-Range resume path. Finals are never touched here.
     * Pure file math (JVM-testable); the WorkManager cancel runs
     * alongside, best-effort.
     */
    internal fun deletePartialsFor(filesDir: java.io.File, kind: String, id: String) {
        when (kind) {
            ModelDownloadSchedule.KIND_STT -> {
                val entry = LocalSttModels.MODELS.firstOrNull { it.id == id } ?: return
                val dir = LocalSttModels.dirFor(filesDir, entry)
                dir.listFiles { f -> ModelDownloadSchedule.isPartialName(f.name) }
                    ?.forEach { try { it.delete() } catch (_: Exception) {} }
            }
            ModelDownloadSchedule.KIND_LLM -> {
                val dest = MainActivity.llmTaskFileFor(filesDir, id)
                try {
                    val dir = java.io.File(dest.parent ?: return)
                    dir.listFiles { f ->
                        ModelDownloadSchedule.isOwnLlmPart(f.name, dest.name, id)
                    }?.forEach { try { it.delete() } catch (_: Exception) {} }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Cancel one download (wipes partials: cancel means reset, see above). */
    @JvmStatic
    fun cancel(ctx: Context, kind: String, id: String) {
        android.util.Log.i(TAG, "cancel $kind/$id (wipes partials)")
        try {
            deletePartialsFor(ctx.applicationContext.filesDir, kind, id)
        } catch (_: Exception) {
        }
        try {
            WorkManager.getInstance(ctx.applicationContext)
                .cancelUniqueWork(ModelDownloadSchedule.workNameFor(kind, id))
        } catch (_: Exception) {
        }
        try {
            ModelDownloadNotifications.cancel(
                ctx,
                ModelDownloadSchedule.notifIdFor(ModelDownloadSchedule.workNameFor(kind, id)),
            )
        } catch (_: Exception) {
        }
    }
}
