package com.mywhisper.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM rules for the on-device LLM pack plumbing (see
 * LocalLlmManager): manifest parsing, prompt packing (core
 * byte-stable), status and SHA-256 verification. No inference runner,
 * no JNI — safe on the host.
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
            {"id":"gemma4-e2b-it","url":"https://example/x.litertlm",
             "sha256":"abc","size_bytes":2588147712}]}"""
        val models = LocalLlmManager.parseManifest(json)
        assertEquals(1, models.size)
        assertEquals("gemma4-e2b-it", models[0].id)
        assertEquals(2588147712L, models[0].sizeBytes)
    }

    @Test(expected = Exception::class)
    fun manifest_rejectsMissingModels() {
        LocalLlmManager.parseManifest("{}")
    }

    @Test
    fun status_missingFileIsNotReady() {
        val dir = createTempDir("llm-status")
        try {
            val model = LocalLlmManager.defaultModelFile(dir)
            assertEquals("cleanup.litertlm", model.name)
            val s = LocalLlmManager.statusFor(model)
            assertFalse(s.ready)
            assertEquals(null, s.sizeBytes)
            assertEquals(LocalLlmManager.PROVIDER, s.provider)
            assertEquals("litertlm", s.provider)
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
            val dest = File(dir, "cleanup.litertlm")
            val a = LocalLlmManager.partFileFor(dest, "gemma4-e2b-it")
            val b = LocalLlmManager.partFileFor(dest, "gemma4-e4b-it")
            assertFalse(a == b)
            assertEquals(File(dir, "cleanup.litertlm.part.gemma4-e2b-it"), a)
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
    fun download_skipsWhenDestAlreadyVerifies() {
        val dest = File.createTempFile("llm-dl", ".litertlm")
        try {
            dest.writeBytes("abc".toByteArray())
            val asset = LocalLlmManager.ModelAsset(
                id = "v",
                url = "https://127.0.0.1:1/unreachable.litertlm",
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sizeBytes = 3,
            )
            // No network touched: dest already verifies.
            assertEquals(dest, LocalLlmManager.downloadModel(asset, dest))
        } finally {
            dest.delete()
        }
    }

    @Test
    fun packProbe_neverThrows() {
        // Classpath probe (LiteRT-LM AAR absent on the JVM test
        // classpath, same as lite builds) must never throw — it gates
        // download offers only, never inference.
        LocalLlmManager.isPackSupported()
    }
}
