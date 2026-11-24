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
            DictationIssue.causeOf("no server STT configured: add a Self-hosted or Cloud entry"))
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

    @Test
    fun responsiveRules() {
        // Phone portrait: bottom bar. Phone landscape (short) and tablets: rail.
        assertFalse(useNavigationRail(widthDp = 411, heightDp = 891))
        assertTrue(useNavigationRail(widthDp = 891, heightDp = 411))
        assertTrue(useNavigationRail(widthDp = 800, heightDp = 1280))
        assertEquals(24, heatmapWeeks(328f))
        assertEquals(40, heatmapWeeks(760f))
        assertEquals(53, heatmapWeeks(2000f))
    }

    @Test
    fun historyReloadKeepsLoadedPages() {
        assertEquals(100, historyReloadCount(0))
        assertEquals(100, historyReloadCount(37))
        assertEquals(100, historyReloadCount(100))
        assertEquals(300, historyReloadCount(201))
    }

    @Test
    fun fastScrollerMapsBothWays() {
        assertEquals(0, scrollerRowAt(0f, 101))
        assertEquals(50, scrollerRowAt(0.5f, 101))
        assertEquals(100, scrollerRowAt(1.4f, 101))
        assertEquals(0, scrollerRowAt(0.7f, 1))
        assertEquals(0.5f, scrollerFractionOf(50, 101), 0.0001f)
        assertEquals(0f, scrollerFractionOf(-3, 101), 0.0001f)
        assertEquals(scrollerRowAt(scrollerFractionOf(37, 101), 101), 37)
    }

    @Test
    fun fastScrollerRowsCoverUnloadedDays() {
        val d1 = LocalDate.of(2026, 11, 24)
        val d2 = d1.minusDays(1)
        val d3 = d1.minusDays(3)
        // Loaded: all 2 entries of d1, 1 of d2's 3 entries (header + entries per day).
        val loaded = listOf("a", "a", "a", "b", "b")
        val counts = listOf(d3 to 2, d1 to 2, d2 to 3, d1.minusDays(2) to 0)
        val rows = historyScrollerRows(loaded, d2, 1, counts) { if (it == d3) "c" else "?" }
        assertEquals(listOf("a", "a", "a", "b", "b", "b", "b", "c", "c", "c"), rows)
        // Nothing loaded or no counts: loaded rows only.
        assertEquals(emptyList<String>(), historyScrollerRows(emptyList(), null, 0, counts) { "x" })
        assertEquals(loaded, historyScrollerRows(loaded, d2, 1, emptyList()) { "x" })
    }
}
