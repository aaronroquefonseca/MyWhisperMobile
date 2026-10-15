package com.mywhisper.mobile

import java.io.File
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
            assertEquals("cleanup.task", task.name)
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

    @Test(expected = RuntimeException::class)
    fun generate_throwsWhenModelMissing() {
        LocalLlmManager.generate(File("/nonexistent/cleanup.task"), "prompt")
    }
}
