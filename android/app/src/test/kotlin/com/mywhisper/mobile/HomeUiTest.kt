package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HomeUiTest {
    @Test
    fun cleanupAllFailed_onlyWhenEveryAttemptFailed() {
        assertTrue(DictationIssue.cleanupAllFailed(listOf("Ollama: request: connection refused", "Cloud: empty reply")))
        assertFalse(DictationIssue.cleanupAllFailed(listOf("Ollama: request: timeout", "Cloud: ok")))
        // Not attempts: disabled, no entries, snippet fallback notes.
        assertFalse(DictationIssue.cleanupAllFailed(listOf("cleanup disabled")))
        assertFalse(DictationIssue.cleanupAllFailed(listOf("no enabled cleanup entries")))
        assertFalse(DictationIssue.cleanupAllFailed(listOf("snippets: marker-like input; expanded without cleanup")))
        assertFalse(DictationIssue.cleanupAllFailed(emptyList()))
        assertTrue(DictationIssue.cleanupAllFailed(listOf("Local: not ready", "snippets: placeholder contract broken; expanded without cleanup")))
    }

    @Test
    fun causeOf_mapsCommonFailures() {
        assertEquals(DictationIssue.Cause.NO_PROVIDER,
            DictationIssue.causeOf(userFacingFinishError("no server STT configured: add a Self-hosted or Cloud entry")))
        assertEquals(DictationIssue.Cause.SIGN_IN, DictationIssue.causeOf("Groq: HTTP 401 Unauthorized"))
        assertEquals(DictationIssue.Cause.UNREACHABLE, DictationIssue.causeOf("Home: request: reqwest::Error { kind: Connect }"))
        assertEquals(DictationIssue.Cause.MISSING_ADDRESS, DictationIssue.causeOf("self-hosted entry has no URL"))
        assertEquals(DictationIssue.Cause.OTHER, DictationIssue.causeOf("parse json: expected value"))
    }

    @Test
    fun historyDayLabel_todayYesterdayThenDate() {
        val zone: ZoneId = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 24)
        fun secs(d: LocalDate) = d.atTime(15, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals("T", historyDayLabel(secs(today), today, zone, "T", "Y"))
        assertEquals("Y", historyDayLabel(secs(today.minusDays(1)), today, zone, "T", "Y"))
        val older = historyDayLabel(secs(today.minusDays(5)), today, zone, "T", "Y")
        assertTrue(older, older.contains("19"))
        val lastYear = historyDayLabel(secs(LocalDate.of(2025, 3, 2)), today, zone, "T", "Y")
        assertTrue(lastYear, lastYear.contains("2025"))
    }
}
