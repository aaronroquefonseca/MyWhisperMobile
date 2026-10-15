package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local sections UX (see MainActivity STT/LLM screens): the on-device
 * model sections are collapsible cards collapsed by default (persisted
 * pref), the setup progress counts an enabled+verified Local entry as
 * speech configured, and both chains render the ONE Local row pattern
 * in the same block order.
 *
 * Pure JVM (no Robolectric): helpers take injected values — org.json
 * stays at the Compose call sites.
 */
class LocalSectionsTest {

    // -- Collapsed by default (persisted pref) ------------------------

    @Test
    fun sections_collapsedByDefault() {
        assertFalse(MainActivity.localSectionDefaultExpanded())
    }

    @Test
    fun expanded_missingPrefReadsCollapsed() {
        assertFalse(MainActivity.localSectionExpanded(null))
        assertTrue(MainActivity.localSectionExpanded(true))
        assertFalse(MainActivity.localSectionExpanded(false))
    }

    @Test
    fun expanded_sttAndLlmPersistSeparately() {
        assertNotEquals(MainActivity.PREF_STT_LOCAL_EXPANDED, MainActivity.PREF_LLM_LOCAL_EXPANDED)
        assertEquals("local_stt_expanded", MainActivity.PREF_STT_LOCAL_EXPANDED)
        assertEquals("local_llm_expanded", MainActivity.PREF_LLM_LOCAL_EXPANDED)
    }

    @Test
    fun summary_lineShowsModelAndState() {
        assertEquals(
            "moonshine tiny-en · enabled",
            MainActivity.localSectionSummary("moonshine tiny-en", true, "enabled", "disabled"),
        )
        assertEquals(
            "moonshine tiny-en · disabled",
            MainActivity.localSectionSummary("moonshine tiny-en", false, "enabled", "disabled"),
        )
        // Localized words flow through untouched.
        assertEquals(
            "gemma3-1b-it-int4 · activado",
            MainActivity.localSectionSummary("gemma3-1b-it-int4", true, "activado", "desactivado"),
        )
    }

    // -- Setup counts an enabled+verified Local entry -----------------

    @Test
    fun speech_serverConfiguredCountsRegardlessOfLocal() {
        assertTrue(MainActivity.isSpeechConfigured(true, false, false))
        assertTrue(MainActivity.isSpeechConfigured(true, true, true))
        assertTrue(MainActivity.isSpeechConfigured(true, false, true))
    }

    @Test
    fun speech_localOnlyCountsWhenEnabledAndVerified() {
        // Local-only setup: no server entry, enabled Local + verified
        // pack must NOT report "no speech configured".
        assertTrue(MainActivity.isSpeechConfigured(false, true, true))
    }

    @Test
    fun speech_unverifiedOrDisabledLocalDoesNotCount() {
        // Fail-closed parity: enabled without a verified pack, or a
        // verified pack on a disabled entry, still counts as missing.
        assertFalse(MainActivity.isSpeechConfigured(false, true, false))
        assertFalse(MainActivity.isSpeechConfigured(false, false, true))
        assertFalse(MainActivity.isSpeechConfigured(false, false, false))
    }

    // -- STT/LLM follow ONE pattern, same order ------------------------

    @Test
    fun rows_sttAndLlmShareOneBlockOrder() {
        val expected = listOf(
            "title", "summary", "status", "using_model", "toggle", "why", "delete",
        )
        assertEquals(expected, MainActivity.localChainRowBlocks())
        assertEquals(expected, MainActivity.sttLocalRowBlocks())
        assertEquals(expected, MainActivity.llmLocalRowBlocks())
        assertEquals(
            "STT and LLM chain rows must follow the identical pattern",
            MainActivity.sttLocalRowBlocks(),
            MainActivity.llmLocalRowBlocks(),
        )
    }

    @Test
    fun rows_downloadAndProgressNeverLiveInTheRow() {
        // Downloads, progress and delete-confirm belong to the
        // collapsible on-device section, never the chain row.
        for (block in MainActivity.localChainRowBlocks()) {
            assertFalse("row must not embed $block", block == "download" || block == "progress")
        }
    }

    @Test
    fun cards_onDeviceSectionsShareOneBlockOrder() {
        assertEquals(
            listOf("title", "size", "use", "badges", "status", "dest", "progress", "actions"),
            MainActivity.localModelCardBlocks(),
        )
    }
}
