package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Background-download policy ([ModelDownloadSchedule]): unique work per
 * model, backoff/attempt cap, partial-file handling (cancel + kill
 * resume), progress math, and the row-state contract (in-flight =
 * DOWNLOADING, verified completion = PRESENT, deletion = MISSING).
 */
class ModelDownloadBgTest {

    @Test
    fun workNames_uniquePerModel_safeChars() {
        val a = ModelDownloadSchedule.workNameFor("stt", LocalSttModels.MOONSHINE_ID)
        val b = ModelDownloadSchedule.workNameFor("stt", LocalSttModels.WHISPER_TINY_ID)
        val c = ModelDownloadSchedule.workNameFor("llm", "gemma3-1b-it-int4")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        for (w in listOf(a, b, c)) {
            assertTrue(w, w.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertTrue(w.startsWith("mw-dl-"))
        }
        // Same input → same name (one unique work per pack; REPLACE
        // keeps a single writer and lets Retry re-run).
        assertEquals(
            a,
            ModelDownloadSchedule.workNameFor("stt", LocalSttModels.MOONSHINE_ID),
        )
    }

    @Test
    fun notifIds_stableAndDistinct() {
        val a = ModelDownloadSchedule.workNameFor("stt", LocalSttModels.MOONSHINE_ID)
        val b = ModelDownloadSchedule.workNameFor("stt", LocalSttModels.WHISPER_TINY_ID)
        assertEquals(
            ModelDownloadSchedule.notifIdFor(a),
            ModelDownloadSchedule.notifIdFor(a),
        )
        assertNotEquals(ModelDownloadSchedule.notifIdFor(a), ModelDownloadSchedule.notifIdFor(b))
    }

    @Test
    fun backoff_exponentialThenCapped() {
        assertEquals(10_000L, ModelDownloadSchedule.backoffDelayMs(0))
        assertEquals(20_000L, ModelDownloadSchedule.backoffDelayMs(1))
        assertEquals(40_000L, ModelDownloadSchedule.backoffDelayMs(2))
        assertEquals(15 * 60_000L, ModelDownloadSchedule.backoffDelayMs(30))
        assertTrue(ModelDownloadSchedule.backoffDelayMs(10) <= 15 * 60_000L)
    }

    @Test
    fun giveUp_atAttemptCap() {
        assertFalse(ModelDownloadSchedule.shouldGiveUp(0))
        assertFalse(ModelDownloadSchedule.shouldGiveUp(ModelDownloadSchedule.MAX_ATTEMPTS - 2))
        assertTrue(ModelDownloadSchedule.shouldGiveUp(ModelDownloadSchedule.MAX_ATTEMPTS - 1))
        assertTrue(ModelDownloadSchedule.shouldGiveUp(ModelDownloadSchedule.MAX_ATTEMPTS))
    }

    @Test
    fun partials_detectedAndListed() {
        val parts = ModelDownloadSchedule.sttPartNames(listOf("encoder_model.ort", "tokens.txt"))
        assertEquals(listOf("encoder_model.ort.part", "tokens.txt.part"), parts)
        assertTrue(parts.all { ModelDownloadSchedule.isPartialName(it) })
        val llm = ModelDownloadSchedule.llmPartName("cleanup.task", "gemma3-1b-it-int4")
        assertEquals("cleanup.task.part.gemma3-1b-it-int4", llm)
        assertTrue(ModelDownloadSchedule.isPartialName(llm))
        assertFalse(ModelDownloadSchedule.isPartialName("cleanup.task"))
        assertFalse(ModelDownloadSchedule.isPartialName("encoder_model.ort"))
    }

    @Test
    fun killRestart_alwaysClean_neverPromotesPartials() {
        // A kill mid-flight must re-download, never flip a `.part`
        // into a usable pack: restart is unconditional.
        assertTrue(ModelDownloadSchedule.restartAfterKill())
        // ... and the row stays out of PRESENT while only partials
        // exist (nothing verified → not present, not downloading).
        assertEquals(
            LocalModelDownload.RowState.MISSING,
            LocalModelDownload.resolve(false, false, false, null),
        )
    }

    @Test
    fun cancel_keepsVerifiedFinals_rowFallsBack() {
        // Cancel deletes partials but keeps verified files: a fully
        // verified pack still resolves PRESENT after a cancel.
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, false, false, null),
        )
        // A pack with only unverified leftovers resolves MISMATCH
        // (re-download offered), never PRESENT.
        assertEquals(
            LocalModelDownload.RowState.MISMATCH,
            LocalModelDownload.resolve(false, true, false, null),
        )
    }

    @Test
    fun rowContract_runningCompletionDeletion() {
        // In-flight background work paints DOWNLOADING in place.
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            ModelDownloadSchedule.rowWhileRunning(false, false),
        )
        assertEquals(
            LocalModelDownload.RowState.DOWNLOADING,
            ModelDownloadSchedule.rowWhileRunning(true, false),
        )
        // Completion (verified pack) → PRESENT; deletion → MISSING.
        // Both flow through the existing derivation, no new bus.
        assertEquals(
            LocalModelDownload.RowState.PRESENT,
            LocalModelDownload.resolve(true, false, false, null),
        )
        assertEquals(
            LocalModelDownload.RowState.MISSING,
            LocalModelDownload.resolve(false, false, false, null),
        )
        // Terminal failure pins FAILED until Retry/Delete clears it.
        assertEquals(
            LocalModelDownload.RowState.FAILED,
            LocalModelDownload.resolve(false, false, false, "boom"),
        )
    }

    @Test
    fun progressPercent_nullWhenUnknown_clamped() {
        assertNull(ModelDownloadSchedule.progressPercent(10, null))
        assertNull(ModelDownloadSchedule.progressPercent(10, 0))
        assertNull(ModelDownloadSchedule.progressPercent(-1, 100))
        assertEquals(50, ModelDownloadSchedule.progressPercent(50, 100))
        assertEquals(100, ModelDownloadSchedule.progressPercent(200, 100))
        assertEquals(0, ModelDownloadSchedule.progressPercent(0, 100))
    }

    @Test
    fun progressKeys_matchWorkerContract() {
        // Row hooks observe these keys; the worker writes them.
        assertEquals("mw_dl_received", ModelDownloadSchedule.PROG_RECEIVED)
        assertEquals("mw_dl_total", ModelDownloadSchedule.PROG_TOTAL)
        assertEquals("mw_dl_error", ModelDownloadSchedule.OUTPUT_ERROR)
    }

    // -- Review fixes: foreground-denial degrade ----------------------

    /** Shape of the API 31+ background-start denial (name-matched, no Android dep). */
    class ForegroundServiceStartNotAllowed : RuntimeException("denied")

    @Test
    fun foregroundDenial_alwaysDegrades_neverFailsWork() {
        // The must-hold: NO throw from setForeground may ever fail the
        // download — the worker degrades to notification-only progress
        // and the kill-resume-safe download fns keep running.
        assertTrue(
            ModelDownloadSchedule.degradeToNotificationOnly(
                ForegroundServiceStartNotAllowed(),
            ),
        )
        assertTrue(ModelDownloadSchedule.degradeToNotificationOnly(SecurityException("bg")))
        assertTrue(ModelDownloadSchedule.degradeToNotificationOnly(IllegalStateException("fg")))
        assertTrue(ModelDownloadSchedule.degradeToNotificationOnly(RuntimeException("other")))
    }

    // -- Review fixes: wired row actions -------------------------------

    @Test
    fun workRowAction_waitingAndRunningTrack() {
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.TRACK,
            ModelDownloadSchedule.actionForWorkState("ENQUEUED"),
        )
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.TRACK,
            ModelDownloadSchedule.actionForWorkState("BLOCKED"),
        )
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.TRACK,
            ModelDownloadSchedule.actionForWorkState("RUNNING"),
        )
    }

    @Test
    fun workRowAction_terminalStates() {
        // SUCCEEDED refreshes the pack derivation; FAILED pins the
        // worker detail; CANCELLED clears without pinning (cancel
        // must never read as FAILED); unknowns fail safe to clear.
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.DONE,
            ModelDownloadSchedule.actionForWorkState("SUCCEEDED"),
        )
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.FAILED,
            ModelDownloadSchedule.actionForWorkState("FAILED"),
        )
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.CLEARED,
            ModelDownloadSchedule.actionForWorkState("CANCELLED"),
        )
        assertEquals(
            ModelDownloadSchedule.WorkRowAction.CLEARED,
            ModelDownloadSchedule.actionForWorkState("bogus"),
        )
    }

    // -- Review fixes: concurrent-partial isolation --------------------

    @Test
    fun llmPartialSweep_scopedToOwnAsset_siblingSurvives() {
        // Two assets downloading at once: sweeping asset A must leave
        // asset B's partial (and the finished dest) untouched.
        val dir = Files.createTempDirectory("llm-parts").toFile()
        try {
            val destName = "cleanup.task"
            File(dir, destName).writeBytes("final".toByteArray())
            val partA = File(dir, ModelDownloadSchedule.llmPartName(destName, "asset-a"))
            val partB = File(dir, ModelDownloadSchedule.llmPartName(destName, "asset-b"))
            partA.writeBytes("a".toByteArray())
            partB.writeBytes("b".toByteArray())
            // Worker sweep for asset A (mirrors ModelDownloadWorker).
            dir.listFiles { f ->
                ModelDownloadSchedule.isOwnLlmPart(f.name, destName, "asset-a")
            }?.forEach { it.delete() }
            assertFalse(partA.exists())
            assertTrue("sibling partial must survive", partB.exists())
            assertTrue("finished dest must survive", File(dir, destName).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun isOwnLlmPart_exactMatch_blankFallsBackToPrefix() {
        assertTrue(
            ModelDownloadSchedule.isOwnLlmPart(
                "cleanup.task.part.asset-a", "cleanup.task", "asset-a",
            ),
        )
        assertFalse(
            ModelDownloadSchedule.isOwnLlmPart(
                "cleanup.task.part.asset-b", "cleanup.task", "asset-a",
            ),
        )
        assertFalse(
            ModelDownloadSchedule.isOwnLlmPart("cleanup.task", "cleanup.task", "asset-a"),
        )
        // Legacy/unknown-asset cleanup still sweeps the dest prefix.
        assertTrue(
            ModelDownloadSchedule.isOwnLlmPart(
                "cleanup.task.part.whatever", "cleanup.task", "",
            ),
        )
        assertFalse(
            ModelDownloadSchedule.isOwnLlmPart("other.task.part.x", "cleanup.task", ""),
        )
    }

    // -- Review fixes: failure copy ------------------------------------

    private fun stringMap(resFile: File): Map<String, String> {
        val text = resFile.readText()
        return Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun failureTitle_separateFromComplete_enAndEs() {
        // The failure notification must not wear the "complete"
        // title. Skipped when the repo layout is unavailable (CI
        // artifact runs) — same pattern as the manifest sync guard.
        val base = File(System.getProperty("user.dir"), "src/main/res")
        val en = File(base, "values/strings.xml")
        val es = File(base, "values-es/strings.xml")
        assumeTrue(en.isFile)
        assumeTrue(es.isFile)
        for (f in listOf(en, es)) {
            val strings = stringMap(f)
            val failed = strings["download_failed_title"].orEmpty()
            val complete = strings["download_complete_title"].orEmpty()
            assertTrue("${f.name}: failed title present", failed.isNotBlank())
            assertTrue("${f.name}: complete title present", complete.isNotBlank())
            assertNotEquals("${f.name}: titles must differ", complete, failed)
        }
    }
}
