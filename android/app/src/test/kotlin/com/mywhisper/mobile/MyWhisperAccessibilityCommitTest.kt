package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the floating-bubble paste commit path:
 * [MyWhisperAccessibility.pasteInsert] builds the exact clipboard
 * payload for ACTION_PASTE (the app inserts at its own cursor — the
 * field's existing text is never re-sent, so duplication is
 * impossible by construction), and [MyWhisperAccessibility
 * .shouldMarkSensitive] gates the API-33 clipboard-history guard.
 */
class MyWhisperAccessibilityCommitTest {

    @Test
    fun repro_refPlusWord_payloadYieldsSingleSpacedOnce() {
        // Owner repro: field='REF', dictate 'X' => payload ' X', so the
        // app's own paste yields exactly 'REF X' once.
        val payload = MyWhisperAccessibility.pasteInsert("REF", 3, 3, "X")
        assertEquals(" X", payload)
        val field = "REF" + payload
        assertEquals("REF X", field)
        assertEquals(1, occurrences(field, "REF"))
        assertEquals(1, occurrences(field, "X"))
    }

    @Test
    fun repro_staleSelection_refPlusWord() {
        // Same repro through the stale-selection fallback (-1/-1):
        // still exactly one REF, one X, one separating space.
        val payload = MyWhisperAccessibility.pasteInsert("REF", -1, -1, "X")
        assertEquals(" X", payload)
        assertEquals("REF X", "REF" + payload)
    }

    @Test
    fun repro_hintOnlyField_bareWord() {
        // Hint-only field (no existing text): bare word, no leading space.
        assertEquals("X", MyWhisperAccessibility.pasteInsert("", 0, 0, "X"))
        assertEquals("X", MyWhisperAccessibility.pasteInsert("", -1, -1, "X"))
    }

    @Test
    fun pasteInsert_neverDoubleSpaces() {
        assertEquals("world", MyWhisperAccessibility.pasteInsert("hello ", 6, 6, "world"))
        assertEquals(" world", MyWhisperAccessibility.pasteInsert("hello", -1, -1, " world"))
    }

    @Test
    fun pasteInsert_punctuationDirect() {
        assertEquals(",", MyWhisperAccessibility.pasteInsert("hello", 5, 5, ","))
    }

    @Test
    fun pasteInsert_midText_byteExact() {
        // Mid-text cursor: no leading space — cursor context, not the
        // field tail, owns the join.
        assertEquals("brave ", MyWhisperAccessibility.pasteInsert("hello world", 6, 6, "brave "))
    }

    @Test
    fun pasteInsert_selectionReplace_byteExact() {
        assertEquals("there", MyWhisperAccessibility.pasteInsert("hello world", 6, 11, "there"))
    }

    @Test
    fun pasteInsert_emptyInsert_passthrough() {
        assertEquals("", MyWhisperAccessibility.pasteInsert("hello", 2, 2, ""))
    }

    @Test
    fun pasteInsert_invalidSelection_endAppendFallback() {
        // Stale node (-1/-1), reversed range, and past-the-end offsets
        // all degrade to the end-append join.
        assertEquals(" world", MyWhisperAccessibility.pasteInsert("hello", -1, -1, " world"))
        assertEquals(" world", MyWhisperAccessibility.pasteInsert("hello", 4, 2, " world"))
        assertEquals(" world", MyWhisperAccessibility.pasteInsert("hello", 5, 99, " world"))
    }

    @Test
    fun pasteInsert_payloadNeverContainsExisting() {
        // Duplication impossible by construction: the payload carries
        // only the insert (plus at most one join space).
        val payload = MyWhisperAccessibility.pasteInsert("hello", 5, 5, "world")
        assertEquals(" world", payload)
        assertEquals(0, occurrences(payload, "hello"))
    }

    @Test
    fun sensitive_on33plus_only() {
        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(33))
        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(34))
        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(36))
        assertFalse(MyWhisperAccessibility.shouldMarkSensitive(32))
        assertFalse(MyWhisperAccessibility.shouldMarkSensitive(29))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle, ignoreCase = false, limit = 0).size - 1
}
