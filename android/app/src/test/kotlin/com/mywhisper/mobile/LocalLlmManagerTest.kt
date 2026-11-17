package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM rules for the on-device LLM manager (see LocalLlmManager):
 * manifest parsing, prompt packing (core byte-stable), status and
 * SHA-256 verification. No MediaPipe runner, no JNI — safe on the
 * host (the API targets physical GPUs, not emulators).
 */
class LocalLlmManagerTest {

    @Test
    fun prompt_packsSystemAndTranscriptTags() {
        assertEquals(
            "SYS\n\n<transcript>hola mundo</transcript>",
            LocalLlmManager.buildPrompt("SYS", "hola mundo"),
        )
    }

    @Test
    fun manifest_parsesModels() {
        val json = """{"models":[
            {"id":"smollm2-360m","url":"https://example/x.task",
             "sha256":"abc","size_bytes":412000000}]}"""
        val models = LocalLlmManager.parseManifest(json)
        assertEquals(1, models.size)
        assertEquals("smollm2-360m", models[0].id)
        assertEquals(412000000L, models[0].sizeBytes)
    }

    @Test(expected = Exception::class)
    fun manifest_rejectsMissingModels() {
        LocalLlmManager.parseManifest("{}")
    }

    @Test
    fun status_missingFileIsNotReady() {
        val dir = createTempDir("llm-status")
        try {
            val task = LocalLlmManager.defaultTaskFile(dir)
            assertEquals("cleanup.gguf", task.name)
            val s = LocalLlmManager.statusFor(task)
            assertFalse(s.ready)
            assertEquals(null, s.sizeBytes)
            assertEquals(LocalLlmManager.PROVIDER, s.provider)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sha256_knownVectorAndMismatch() {
        // "abc" -> ba7816bf... (FIPS 180-4 vector).
        val f = File.createTempFile("llm-sha", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertTrue(
                LocalLlmManager.verifySha256(
                    f,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
            )
            assertFalse(LocalLlmManager.verifySha256(f, "0".repeat(64)))
        } finally {
            f.delete()
        }
    }

    @Test
    fun partFiles_areUniquePerAsset() {
        val dir = createTempDir("llm-part")
        try {
            val dest = File(dir, "cleanup.task")
            val a = LocalLlmManager.partFileFor(dest, "smollm2-360m")
            val b = LocalLlmManager.partFileFor(dest, "gemma3-1b")
            assertFalse(a == b)
            assertEquals(File(dir, "cleanup.task.part.smollm2-360m"), a)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun freeSpace_gateRejectsShortfall() {
        assertTrue(LocalLlmManager.hasFreeSpace(412_000_000L, 412_000_000L))
        assertFalse(LocalLlmManager.hasFreeSpace(1L, 412_000_000L))
    }

    @Test
    fun manifest_ggufCatalogWithThinkingFlags() {
        val assets = LocalLlmManager.parseManifest(MainActivity.LLM_MANIFEST_JSON)
        assertEquals(
            listOf("gemma3-1b-it-q4_0", "gemma4-e2b-it-qat-q4_0", "gemma4-e4b-it-qat-q4_0"),
            assets.map { it.id },
        )
        assertTrue(assets.all { it.url.endsWith(".gguf") && it.sha256.length == 64 && it.sizeBytes > 0 })
        assertEquals(listOf(false, true, true), assets.map { it.thinking })
        // App copy and script copy stay identical.
        assertEquals(
            assets,
            LocalLlmManager.parseManifest(File("../../scripts/local-llm-models.json").readText()),
        )
    }

    @Test
    fun maxTokensFor_scalesWithTranscriptWithinBounds() {
        assertEquals(128, LocalLlmManager.maxTokensFor("hi"))
        assertEquals(564, LocalLlmManager.maxTokensFor("x".repeat(1000)))
        assertEquals(1024, LocalLlmManager.maxTokensFor("x".repeat(10_000)))
    }

    @Test
    fun systemWithThinking_onlyForThinkingModelsWithBudget() {
        val g4 = LocalLlmManager.ModelAsset("g4", "u", "s", 1, thinking = true)
        val g3 = LocalLlmManager.ModelAsset("g3", "u", "s", 1)
        assertEquals("<|think|>\nsys", LocalLlmManager.systemWithThinking("sys", g4, 512))
        assertEquals("sys", LocalLlmManager.systemWithThinking("sys", g4, 0))
        assertEquals("sys", LocalLlmManager.systemWithThinking("sys", g3, 512))
        assertEquals("sys", LocalLlmManager.systemWithThinking("sys", null, 512))
    }

    @Test
    fun engineCost_weightsPrefillAndDecode() {
        // 100 fresh prompt tokens in 100 ms, 10 output tokens in 200 ms.
        val cost = LocalLlmManager.Engine.costOf(doubleArrayOf(120.0, 20.0, 10.0, 100.0, 200.0))!!
        assertEquals(400.0 + 1200.0, cost, 1e-9)
        assertEquals(null, LocalLlmManager.Engine.costOf(doubleArrayOf(10.0, 10.0, 5.0, 1.0, 1.0)))
    }

    @Test
    fun resumeAction_matrix() {
        val A = LocalLlmManager.ResumeAction.APPEND
        val R = LocalLlmManager.ResumeAction.RESTART
        val V = LocalLlmManager.ResumeAction.VERIFY
        val F = LocalLlmManager.ResumeAction.FAIL
        // 206 with a partial appends; 200 means the server ignored
        // Range → truncate and start over.
        assertEquals(A, LocalLlmManager.resumeAction(123L, 206))
        assertEquals(A, LocalLlmManager.resumeAction(0L, 206))
        assertEquals(R, LocalLlmManager.resumeAction(123L, 200))
        assertEquals(R, LocalLlmManager.resumeAction(0L, 200))
        // 416 with a surviving partial verifies (hit = done);
        // only an empty partial restarts outright.
        assertEquals(V, LocalLlmManager.resumeAction(1000L, 416))
        assertEquals(V, LocalLlmManager.resumeAction(123L, 416))
        assertEquals(R, LocalLlmManager.resumeAction(0L, 416))
        // Anything else fails without writing.
        for (code in listOf(301, 302, 400, 403, 404, 500, 503)) {
            assertEquals("code $code", F, LocalLlmManager.resumeAction(123L, code))
        }
    }

    @Test
    fun download_skipsWhenDestAlreadyVerifies() {
        val dest = File.createTempFile("llm-dl", ".task")
        try {
            dest.writeBytes("abc".toByteArray())
            val asset = LocalLlmManager.ModelAsset(
                id = "v",
                url = "https://127.0.0.1:1/unreachable.task",
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sizeBytes = 3,
            )
            // No network touched: dest already verifies.
            assertEquals(dest, LocalLlmManager.downloadModel(asset, dest))
        } finally {
            dest.delete()
        }
    }
}
