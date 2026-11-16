package com.mywhisper.mobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** File choice for the dictation-path on-device LLM ([LocalLlmCleanupRunner]). */
class LocalLlmCleanupRunnerTest {
    private val asset = LocalLlmManager.ModelAsset(
        id = "gemma-test",
        url = "https://example/x.litertlm",
        sha256 = "ab".repeat(32),
        sizeBytes = 1,
        runtime = LocalLlmManager.RUNTIME_LITERTLM,
    )

    @Test
    fun prefersPerAssetSlotThenLegacyThenFails() {
        val dir = Files.createTempDirectory("mw-llm-runner").toFile()
        try {
            File(dir, "models").mkdirs()
            assertThrows(IllegalStateException::class.java) {
                LocalLlmCleanupRunner.modelFile(dir, asset)
            }
            val legacy = LocalLlmManager.defaultTaskFile(dir).apply { writeText("x") }
            assertEquals(legacy, LocalLlmCleanupRunner.modelFile(dir, asset))
            val slot = MainActivity.llmTaskFileFor(dir, asset).apply { writeText("x") }
            assertEquals(slot, LocalLlmCleanupRunner.modelFile(dir, asset))
        } finally {
            dir.deleteRecursively()
        }
    }
}
