package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider tests: ONE [ProviderTest.Result] pattern for every chain
 * row (STT selected + LLM selected, local and remote kinds).
 * Pure JVM (probes injected, no runner/network).
 */
class ProviderTestTest {

    // -- ONE mapping for all kinds ----------------------------------

    @Test
    fun mapping_passOnNonEmptyUnderTimeout() {
        val r = ProviderTest.mapResult("HTTP 200 · whisper-1", 123L, 10_000L, null)
        assertTrue(r.passed)
        assertFalse(r.timedOut)
        assertEquals(123L, r.latencyMs)
        assertEquals("HTTP 200 · whisper-1", r.excerpt)
    }

    @Test
    fun mapping_failOnBlank() {
        val r = ProviderTest.mapResult("   ", 10L, 10_000L, null)
        assertFalse(r.passed)
    }

    @Test
    fun mapping_failOnErrorReportsErrorExcerpt() {
        val r = ProviderTest.mapResult(null, 5L, 10_000L, "boom")
        assertFalse(r.passed)
        assertEquals("boom", r.excerpt)
        assertEquals("boom", r.error)
    }

    @Test
    fun mapping_failOnTimeoutEvenWithOutput() {
        val r = ProviderTest.mapResult("ok", 10_001L, 10_000L, null)
        assertFalse(r.passed)
        assertTrue(r.timedOut)
    }

    @Test
    fun perKindMappers_shareOneRule() {
        val local = ProviderTest.mapLocalSttResult("pack ok · 2 chunks", 50L)
        val server = ProviderTest.mapServerSttResult("HTTP 200 · m", 50L)
        val remote = ProviderTest.mapRemoteLlmResult("model ok · m", 50L)
        assertTrue(local.passed)
        assertTrue(server.passed)
        assertTrue(remote.passed)
        // Same FAIL shape on error.
        assertFalse(ProviderTest.mapLocalSttResult(null, 1L, error = "x").passed)
        assertFalse(ProviderTest.mapServerSttResult(null, 1L, error = "x").passed)
        assertFalse(ProviderTest.mapRemoteLlmResult(null, 1L, error = "x").passed)
    }

    @Test
    fun fromSelfTest_unitesLocalLlmHarness() {
        val self = LocalLlmManager.mapSelfTestResult("This is a test.", 123L, 60_000L, null)
        val r = ProviderTest.fromSelfTest(self)
        assertTrue(r.passed)
        assertEquals(self.latencyMs, r.latencyMs)
        assertEquals(self.excerpt, r.excerpt)
        assertEquals(self.timedOut, r.timedOut)
        val failed = ProviderTest.fromSelfTest(
            LocalLlmManager.mapSelfTestResult(null, 5L, 60_000L, "down"),
        )
        assertFalse(failed.passed)
        assertEquals("down", failed.error)
    }

    @Test
    fun runProbe_passThroughInjectedProbe() {
        val r = ProviderTest.runProbe(10_000L) { "HTTP 200 · m" }
        assertTrue(r.passed)
    }

    @Test
    fun runProbe_throwIsFailNoMasking() {
        val r = ProviderTest.runProbe(10_000L) { throw IllegalStateException("net down") }
        assertFalse(r.passed)
        assertEquals("net down", r.error)
    }

    @Test
    fun excerpt_collapsesWhitespaceAndCaps() {
        val e = ProviderTest.excerpt("a  b\nc ".repeat(50))
        assertTrue(e.length <= ProviderTest.EXCERPT_MAX + 1)
        assertFalse(e.contains("\n"))
    }

    @Test
    fun samplePcm_isOneSecond16k() {
        val pcm = ProviderTest.samplePcm16()
        assertEquals(16000, pcm.size)
    }

    // -- STT validation (SelfHosted / Cloud / Local) ----------------

    @Test
    fun stt_selfHostedNeedsUrlAndModel() {
        assertEquals(
            ProviderTest.SttError.NEED_URL,
            ProviderTest.validateStt("SelfHosted", "", "whisper-1"),
        )
        assertEquals(
            ProviderTest.SttError.NEED_MODEL,
            ProviderTest.validateStt("SelfHosted", "http://s:8000", ""),
        )
        assertEquals(
            ProviderTest.SttError.NONE,
            ProviderTest.validateStt("SelfHosted", "http://s:8000", "whisper-1"),
        )
        assertEquals(
            ProviderTest.SttError.NONE,
            ProviderTest.validateStt("SelfHosted", "ws://s:8000", "whisper-1"),
        )
    }

    @Test
    fun stt_selfHostedRejectsBadScheme() {
        assertEquals(
            ProviderTest.SttError.BAD_URL,
            ProviderTest.validateStt("SelfHosted", "ftp://s", "m"),
        )
        assertEquals(
            ProviderTest.SttError.BAD_URL,
            ProviderTest.validateStt("SelfHosted", "no-scheme", "m"),
        )
    }

    @Test
    fun stt_cloudAllowsBlankUrlVendorDefault() {
        assertEquals(
            ProviderTest.SttError.NONE,
            ProviderTest.validateStt("Cloud", "", "whisper-1"),
        )
        assertEquals(
            ProviderTest.SttError.NEED_MODEL,
            ProviderTest.validateStt("Cloud", "", ""),
        )
        assertEquals(
            ProviderTest.SttError.BAD_BASE,
            ProviderTest.validateStt("Cloud", "ws://s", "m"),
        )
    }

    // -- LLM validation (Ollama / Cloud / Local) --------------------

    @Test
    fun llm_ollamaNeedsUrlAndModel() {
        assertEquals(
            ProviderTest.LlmError.NEED_URL,
            ProviderTest.validateLlm("Ollama", "", "llama3"),
        )
        assertEquals(
            ProviderTest.LlmError.NEED_MODEL,
            ProviderTest.validateLlm("Ollama", "http://127.0.0.1:11434", ""),
        )
        assertEquals(
            ProviderTest.LlmError.NONE,
            ProviderTest.validateLlm("Ollama", "http://127.0.0.1:11434", "llama3"),
        )
    }

    @Test
    fun llm_cloudAllowsBlankUrlVendorDefault() {
        assertEquals(
            ProviderTest.LlmError.NONE,
            ProviderTest.validateLlm("Cloud", "", "gpt-4o-mini"),
        )
        assertEquals(
            ProviderTest.LlmError.NEED_MODEL,
            ProviderTest.validateLlm("Cloud", "", ""),
        )
        assertEquals(
            ProviderTest.LlmError.BAD_URL,
            ProviderTest.validateLlm("Cloud", "notaurl", "m"),
        )
    }

    // -- Disabled states --------------------------------------------

    @Test
    fun gate_localOnlyWithRunnerAndVerifiedPack() {
        assertTrue(ProviderTest.localTestAllowed(true, true))
        assertFalse(ProviderTest.localTestAllowed(true, false))
        assertFalse(ProviderTest.localTestAllowed(false, true))
        assertFalse(ProviderTest.localTestAllowed(false, false))
    }

    @Test
    fun gate_serverSttFollowsValidation() {
        assertTrue(ProviderTest.sttServerTestAllowed("SelfHosted", "http://s:8000", "m"))
        assertFalse(ProviderTest.sttServerTestAllowed("SelfHosted", "", "m"))
        assertFalse(ProviderTest.sttServerTestAllowed("SelfHosted", "http://s:8000", ""))
        assertTrue(ProviderTest.sttServerTestAllowed("Cloud", "", "whisper-1"))
        assertFalse(ProviderTest.sttServerTestAllowed("Cloud", "", ""))
    }

    @Test
    fun gate_remoteLlmFollowsValidation() {
        assertTrue(
            ProviderTest.llmRemoteTestAllowed("Ollama", "http://127.0.0.1:11434", "llama3"),
        )
        assertFalse(ProviderTest.llmRemoteTestAllowed("Ollama", "", "llama3"))
        assertFalse(ProviderTest.llmRemoteTestAllowed("Ollama", "http://h", ""))
        assertTrue(ProviderTest.llmRemoteTestAllowed("Cloud", "", "gpt-4o-mini"))
        assertFalse(ProviderTest.llmRemoteTestAllowed("Cloud", "", ""))
    }

    // -- Disabled-reason rendering ------------------------------------

    @Test
    fun showDisabledReason_disabledAndIdleWithReason() {
        assertTrue(ProviderTest.showDisabledReason(false, false, "need pack"))
        // A stale result must NOT suppress the reason: the blocker
        // stays explicit even under old output (callers always pass the
        // latest result; the reason gate ignores it).
        assertTrue(ProviderTest.showDisabledReason(false, false, "need url"))
        assertFalse(ProviderTest.showDisabledReason(true, false, "need url"))
        assertFalse(ProviderTest.showDisabledReason(false, true, "need url"))
        assertFalse(ProviderTest.showDisabledReason(false, false, null))
    }

    // -- Activity gates mirror the harness (row disabled states) ----

    @Test
    fun activity_sttLocalGateMatchesHarness() {
        assertTrue(
            MainActivity.sttLocalTestAllowed(true, MainActivity.LocalPackState.PRESENT),
        )
        assertFalse(
            MainActivity.sttLocalTestAllowed(true, MainActivity.LocalPackState.MISSING),
        )
        assertFalse(
            MainActivity.sttLocalTestAllowed(true, MainActivity.LocalPackState.MISMATCH),
        )
        assertFalse(
            MainActivity.sttLocalTestAllowed(false, MainActivity.LocalPackState.PRESENT),
        )
    }

    @Test
    fun activity_serverAndRemoteGates() {
        assertTrue(MainActivity.sttServerTestAllowed("SelfHosted", "http://s:8000", "m"))
        assertFalse(MainActivity.sttServerTestAllowed("SelfHosted", "", "m"))
        assertTrue(
            MainActivity.llmRemoteTestAllowed("Ollama", "http://127.0.0.1:11434", "llama3"),
        )
        assertFalse(MainActivity.llmRemoteTestAllowed("Ollama", "", "llama3"))
    }

    // -- Probe bearer auth (bearer-gated proxies) ---------------------

    @Test
    fun bearerToken_returnsKeyWhenPresent() {
        assertEquals("sk-test-123", ProviderTest.bearerToken("sk-test-123"))
    }

    @Test
    fun bearerToken_nullWhenBlank() {
        assertNull(ProviderTest.bearerToken(""))
        assertNull(ProviderTest.bearerToken("   "))
        assertNull(ProviderTest.bearerToken("\n\t "))
    }

    @Test
    fun bearerToken_trimsWhitespace() {
        assertEquals("sk-test-123", ProviderTest.bearerToken("  sk-test-123\n"))
    }
}
