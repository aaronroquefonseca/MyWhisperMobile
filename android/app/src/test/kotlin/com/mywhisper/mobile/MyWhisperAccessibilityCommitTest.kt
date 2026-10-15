package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the floating-bubble commit path:
 * [MyWhisperAccessibility.spliceAtCursor] must insert at the cursor
 * (never prepend/duplicate the field's existing text).
 */
class MyWhisperAccessibilityCommitTest {

    @Test
    fun insert_middle() {
        assertEquals(
            "hello brave world",
            MyWhisperAccessibility.spliceAtCursor("hello world", 6, 6, "brave "),
        )
    }

    @Test
    fun insert_end() {
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello", 5, 5, " world"),
        )
    }

    @Test
    fun repro_refPlusWord_singleSpacedOnce() {
        // Owner repro: field='REF', dictate 'X' => 'REF X' exactly
        // once — existing text never duplicated before the insert.
        val merged = MyWhisperAccessibility.spliceAtCursor("REF", 3, 3, "X")
        assertEquals("REF X", merged)
        assertEquals(1, occurrences(merged, "REF"))
        assertEquals(1, occurrences(merged, "X"))
    }

    @Test
    fun repro_staleSelection_refPlusWord() {
        // Same repro through the stale-selection fallback (-1/-1):
        // still exactly one REF, one X, one separating space.
        val merged = MyWhisperAccessibility.spliceAtCursor("REF", -1, -1, "X")
        assertEquals("REF X", merged)
        assertEquals(1, occurrences(merged, "REF"))
    }

    @Test
    fun repro_hintOnlyField_bareWord() {
        // Hint-only field (no existing text): bare word, no leading space.
        assertEquals("X", MyWhisperAccessibility.spliceAtCursor("", 0, 0, "X"))
        assertEquals("X", MyWhisperAccessibility.spliceAtCursor("", -1, -1, "X"))
    }

    @Test
    fun appendWord_neverDoubleSpaces() {
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello ", 6, 6, "world"),
        )
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello", -1, -1, " world"),
        )
    }

    @Test
    fun appendWord_punctuationDirect() {
        assertEquals("hello,", MyWhisperAccessibility.spliceAtCursor("hello", 5, 5, ","))
    }

    @Test
    fun insert_replacesSelection() {
        assertEquals(
            "hello there",
            MyWhisperAccessibility.spliceAtCursor("hello world", 6, 11, "there"),
        )
    }

    @Test
    fun insert_emptyField() {
        assertEquals(
            "dictation",
            MyWhisperAccessibility.spliceAtCursor("", 0, 0, "dictation"),
        )
    }

    @Test
    fun invalidSelection_appendsAtEnd() {
        // Stale node (-1/-1), reversed range, and past-the-end offsets
        // must all degrade to append — never drop or duplicate text.
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello", -1, -1, " world"),
        )
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello", 4, 2, " world"),
        )
        assertEquals(
            "hello world",
            MyWhisperAccessibility.spliceAtCursor("hello", 5, 99, " world"),
        )
    }

    @Test
    fun emptyInsert_noop() {
        assertEquals(
            "hello",
            MyWhisperAccessibility.spliceAtCursor("hello", 2, 2, ""),
        )
    }

    @Test
    fun staleRetry_neverDuplicatesExisting() {
        // Double-fire with a stale (pre-commit) cursor must not
        // re-insert the field's existing content: "hello" occurs once.
        val once = MyWhisperAccessibility.spliceAtCursor("hello", 5, 5, " world")
        assertEquals("hello world", once)
        val twice = MyWhisperAccessibility.spliceAtCursor(once, 5, 5, " world")
        assertEquals("hello world world", twice)
        assertEquals(1, occurrences(twice, "hello"))
        // Length invariant: splice only adds the insert.
        assertEquals("hello".length + " world".length, once.length)
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle, ignoreCase = false, limit = 0).size - 1
}
