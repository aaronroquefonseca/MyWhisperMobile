package com.mywhisper.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local LLM self-test (see CleanupScreen Local row "Run test"):
 * fixed cleanup prompt + fixed transcript through the Local path
 * only. Fail-closed, no fallback masking. Pure JVM (no runner).
 */
class LocalLlmSelfTestTest {

    @Test
    fun prompt_usesFixedTranscript() {
        assertEquals(
            "SYS\n\n<transcript>this is a test</transcript>",
            LocalLlmManager.selfTestPrompt("SYS"),
        )
        assertEquals("this is a test", LocalLlmManager.SELFTEST_TRANSCRIPT)
    }

    @Test
    fun mapping_passOnNonEmptyUnderTimeout() {
        val r = LocalLlmManager.mapSelfTestResult("This is a test.", 123L, 60_000L, null)
        assertTrue(r.passed)
        assertFalse(r.timedOut)
        assertEquals(123L, r.latencyMs)
        assertEquals("This is a test.", r.excerpt)
        assertEquals(null, r.error)
    }

    @Test
    fun mapping_failOnBlank() {
        val r = LocalLlmManager.mapSelfTestResult("   ", 10L, 60_000L, null)
        assertFalse(r.passed)
        assertFalse(r.timedOut)
    }

    @Test
    fun mapping_failOnErrorReportsErrorExcerpt() {
        val r = LocalLlmManager.mapSelfTestResult(null, 5L, 60_000L, "boom")
        assertFalse(r.passed)
        assertEquals("boom", r.excerpt)
        assertEquals("boom", r.error)
    }

    @Test
    fun mapping_failOnTimeoutEvenWithOutput() {
        val r = LocalLlmManager.mapSelfTestResult("ok", 60_001L, 60_000L, null)
        assertFalse(r.passed)
        assertTrue(r.timedOut)
    }

    @Test
    fun excerpt_collapsesWhitespaceAndCaps() {
        val long = "a  b\nc ".repeat(50)
        val e = LocalLlmManager.selfTestExcerpt(long)
        assertTrue(e.length <= LocalLlmManager.SELFTEST_EXCERPT_MAX + 1)
        assertFalse(e.contains("\n"))
    }

    @Test
    fun runSelfTest_localPathOnlyNoFallback() {
        // generate throws -> FAIL, error preserved, no cloud fallback.
        val r = LocalLlmManager.runSelfTest(
            File("/nonexistent/cleanup.task"),
            "SYS",
            timeoutMs = 60_000L,
            generateFn = { _, _ -> throw IllegalStateException("local down") },
        )
        assertFalse(r.passed)
        assertEquals("local down", r.error)
    }

    @Test
    fun runSelfTest_passThroughInjectedGenerate() {
        val r = LocalLlmManager.runSelfTest(
            File("/nonexistent/cleanup.task"),
            "SYS",
            timeoutMs = 60_000L,
            generateFn = { _, prompt ->
                assertTrue(prompt.contains("this is a test"))
                "This is a test."
            },
        )
        assertTrue(r.passed)
    }

    // -- Disabled states (button gate) -------------------------------

    @Test
    fun gate_enabledOnlyWithRunnerAndVerifiedPack() {
        assertTrue(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.PRESENT),
        )
    }

    @Test
    fun gate_disabledWithoutPackOrRunner() {
        assertFalse(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.MISSING),
        )
        assertFalse(
            MainActivity.llmSelfTestAllowed(true, MainActivity.LocalPackState.MISMATCH),
        )
        assertFalse(
            MainActivity.llmSelfTestAllowed(false, MainActivity.LocalPackState.PRESENT),
        )
        assertFalse(
            MainActivity.llmSelfTestAllowed(false, MainActivity.LocalPackState.MISSING),
        )
    }
}
