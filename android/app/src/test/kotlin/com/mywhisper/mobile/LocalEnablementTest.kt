package com.mywhisper.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fail-closed Local enablement rules (see MainActivity STT/LLM rows):
 * a Local entry may only switch on with a verified pack; placeholder
 * (zero) SHA pins never verify, so the toggle stays off with a clear
 * message and the chain falls back to the server entries.
 */
class LocalEnablementTest {

    @Test
    fun stt_presentOnlyWhenPackVerified() {
        assertEquals(
            MainActivity.LocalPackState.PRESENT,
            MainActivity.sttLocalPackState(true),
        )
        assertEquals(
            MainActivity.LocalPackState.MISSING,
            MainActivity.sttLocalPackState(false),
        )
    }

    @Test
    fun placeholderSha_neverEnables() {
        assertTrue(MainActivity.isPlaceholderSha("0".repeat(64)))
        assertTrue(MainActivity.isPlaceholderSha(""))
        assertTrue(MainActivity.isPlaceholderSha("   "))
        assertFalse(
            MainActivity.isPlaceholderSha(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
        )
    }

    @Test
    fun toggle_allowedOnlyWhenPresent() {
        assertTrue(MainActivity.localToggleAllowed(MainActivity.LocalPackState.PRESENT))
        assertFalse(MainActivity.localToggleAllowed(MainActivity.LocalPackState.MISSING))
        assertFalse(MainActivity.localToggleAllowed(MainActivity.LocalPackState.MISMATCH))
    }

    @Test
    fun llm_missingFileIsMissing() {
        val dir = createTempDir("llm-enable")
        try {
            val dest = File(dir, "cleanup.task")
            val assets = listOf(
                LocalLlmManager.ModelAsset("m", "https://example/x.task", "a".repeat(64), 3),
            )
            assertEquals(
                MainActivity.LocalPackState.MISSING,
                MainActivity.llmLocalPackState(dest, assets),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun llm_placeholderPinsFailClosed() {
        // File present but only zero pins exist -> MISSING (never enable).
        val f = File.createTempFile("llm-enable", ".task")
        try {
            f.writeBytes("abc".toByteArray())
            val assets = listOf(
                LocalLlmManager.ModelAsset("m", "https://example/x.task", "0".repeat(64), 3),
            )
            assertEquals(
                MainActivity.LocalPackState.MISSING,
                MainActivity.llmLocalPackState(f, assets),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun llm_matchingShaIsPresent_mismatchIsMismatch() {
        val f = File.createTempFile("llm-enable", ".task")
        try {
            f.writeBytes("abc".toByteArray())
            // "abc" -> ba7816bf... (FIPS 180-4 vector).
            val real = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            val ok = listOf(LocalLlmManager.ModelAsset("m", "https://example/x.task", real, 3))
            assertEquals(
                MainActivity.LocalPackState.PRESENT,
                MainActivity.llmLocalPackState(f, ok),
            )
            val bad = listOf(
                LocalLlmManager.ModelAsset("m", "https://example/x.task", "c".repeat(64), 3),
            )
            assertEquals(
                MainActivity.LocalPackState.MISMATCH,
                MainActivity.llmLocalPackState(f, bad),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun llmManifest_parsesTwoKnownAssets() {
        val assets = LocalLlmManager.parseManifest(MainActivity.LLM_MANIFEST_JSON)
        assertEquals(
            listOf("gemma3-1b-it-int4", "gemma2-2b-it-q8"),
            assets.map { it.id },
        )
        // Real pins: verified by full download + sha256sum (public
        // repos, no auth) — downloads are live, fail-closed otherwise.
        assertTrue(assets.none { MainActivity.isPlaceholderSha(it.sha256) })
        assertTrue(assets.all { it.sizeBytes > 0 })
        assertTrue(assets.all { it.minRamMb > 0 })
    }

    @Test
    fun llmManifest_matchesScriptsFile() {
        // Drift guard: the embedded manifest must stay in sync with
        // scripts/local-llm-models.json. Skipped when the repo layout
        // is unavailable (CI artifact runs).
        val manifest = File(System.getProperty("user.dir"), "../../scripts/local-llm-models.json")
        assumeTrue(manifest.isFile)
        val fromConst = LocalLlmManager.parseManifest(MainActivity.LLM_MANIFEST_JSON)
        val fromFile = LocalLlmManager.parseManifest(manifest.readText())
        assertEquals(fromFile, fromConst)
    }
}
