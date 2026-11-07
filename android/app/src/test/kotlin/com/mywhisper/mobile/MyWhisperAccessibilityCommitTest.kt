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
    fun withoutHint_hintLeak_treatedAsEmpty() {
        // Owner repro: placeholder "Search…" leaks into node.text on
        // an empty field — strip to "" so the payload is the bare
        // word, never "Search… word".
        assertEquals("", MyWhisperAccessibility.withoutHint("Search…", "Search…"))
        assertEquals("X", MyWhisperAccessibility.pasteInsert(
            MyWhisperAccessibility.withoutHint("Search…", "Search…"), -1, -1, "X",
        ))
    }

    @Test
    fun withoutHint_realText_untouched() {
        assertEquals("hello", MyWhisperAccessibility.withoutHint("hello", "Search…"))
        assertEquals("hello", MyWhisperAccessibility.withoutHint("hello", null))
        assertEquals("hello", MyWhisperAccessibility.withoutHint("hello", ""))
        assertEquals("", MyWhisperAccessibility.withoutHint("", "Search…"))
        // Prefix match is not a leak: only exact equality strips.
        assertEquals("Search…x", MyWhisperAccessibility.withoutHint("Search…x", "Search…"))
    }

    @Test
    fun sensitive_on33plus_only() {        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(33))
        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(34))
        assertTrue(MyWhisperAccessibility.shouldMarkSensitive(36))
        assertFalse(MyWhisperAccessibility.shouldMarkSensitive(32))
        assertFalse(MyWhisperAccessibility.shouldMarkSensitive(29))
    }

    @Test
    fun clipRoundTrip_restoresPriorClip() {
        // Review finding: the transcription must not survive as the
        // live clip. Prior clip with items -> restored verbatim.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip(
            capturePrior = { "user-clip" },
            priorCount = { 1 },
            publish = { calls += "publish" },
            doPaste = { calls += "paste"; true },
            restore = { calls += "restore:$it" },
            clear = { calls += "clear" },
        )
        assertTrue(ok)
        assertEquals(listOf("publish", "paste", "restore:user-clip"), calls)
    }

    @Test
    fun clipRoundTrip_clearsWhenNoPrior() {
        // No prior clip -> clearPrimaryClip, never restore.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip<String>(
            capturePrior = { null },
            priorCount = { error("unused") },
            publish = { calls += "publish" },
            doPaste = { calls += "paste"; true },
            restore = { calls += "restore" },
            clear = { calls += "clear" },
        )
        assertTrue(ok)
        assertEquals(listOf("publish", "paste", "clear"), calls)
    }

    @Test
    fun clipRoundTrip_clearsWhenPriorEmpty() {
        // Prior handle with zero items -> clear, never restore.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip(
            capturePrior = { "empty" },
            priorCount = { 0 },
            publish = { calls += "publish" },
            doPaste = { calls += "paste"; true },
            restore = { calls += "restore" },
            clear = { calls += "clear" },
        )
        assertTrue(ok)
        assertEquals(listOf("publish", "paste", "clear"), calls)
    }

    @Test
    fun clipRoundTrip_pasteThrow_clearFallback() {
        // Exception after overwrite -> clear fallback runs, false out.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip(
            capturePrior = { "user-clip" },
            priorCount = { 1 },
            publish = { calls += "publish" },
            doPaste = { throw IllegalStateException("paste boom") },
            restore = { calls += "restore" },
            clear = { calls += "clear" },
        )
        assertFalse(ok)
        assertEquals(listOf("publish", "clear"), calls)
    }

    @Test
    fun clipRoundTrip_publishThrow_noClear() {
        // Publish itself threw -> nothing overwritten, so no clear:
        // clearing would destroy the user's untouched clip.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip(
            capturePrior = { "user-clip" },
            priorCount = { 1 },
            publish = { throw SecurityException("clip denied") },
            doPaste = { calls += "paste"; true },
            restore = { calls += "restore" },
            clear = { calls += "clear" },
        )
        assertFalse(ok)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun clipRoundTrip_captureThrow_stillPastesAndClears() {
        // Unreadable prior (background-read denial) -> paste proceeds,
        // then clear since there is nothing to restore.
        val calls = mutableListOf<String>()
        val ok = MyWhisperAccessibility.withTranscriptionClip<String>(
            capturePrior = { throw SecurityException("read denied") },
            priorCount = { error("unused") },
            publish = { calls += "publish" },
            doPaste = { calls += "paste"; true },
            restore = { calls += "restore" },
            clear = { calls += "clear" },
        )
        assertTrue(ok)
        assertEquals(listOf("publish", "paste", "clear"), calls)
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle, ignoreCase = false, limit = 0).size - 1
}
