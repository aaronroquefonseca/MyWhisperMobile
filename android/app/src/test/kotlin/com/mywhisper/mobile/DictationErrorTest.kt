package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bubble copy for failed transcriptions: "configure a provider first" only
 * when none exists; any configured chain that fails reads "all failed".
 * The raw core message stays in logs and on the Activity card.
 */
class DictationErrorTest {
    private val noServer = "no server STT configured: add a Self-hosted or Cloud entry " +
        "(Local/on-device is not supported yet)"

    @Test
    fun noProviderAtAll_asksToConfigure() {
        assertTrue(DictationIssue.needsProviderSetup(noServer, anyProviderEnabled = false))
    }

    @Test
    fun configuredChainThatFails_isAllFailed() {
        // Local enabled but found nothing: providers exist, they failed.
        assertFalse(DictationIssue.needsProviderSetup(noServer, anyProviderEnabled = true))
        assertFalse(DictationIssue.needsProviderSetup("Groq: request: timeout", anyProviderEnabled = true))
        assertFalse(DictationIssue.needsProviderSetup("Groq: request: timeout", anyProviderEnabled = false))
    }

    @Test
    fun providerFailuresAreNotRoutineDrops() {
        assertTrue(shouldSurfaceError(noServer))
        assertFalse(shouldSurfaceError("too short"))
    }

    @Test
    fun cleanupOutcome_fromNotes() {
        assertEquals(DictationIssue.Cleanup.UNCONFIGURED, DictationIssue.cleanupOutcome(listOf("no enabled cleanup entries")))
        assertEquals(DictationIssue.Cleanup.FAILED, DictationIssue.cleanupOutcome(listOf("Ollama: request: refused")))
        assertEquals(DictationIssue.Cleanup.OK, DictationIssue.cleanupOutcome(listOf("Ollama: ok")))
        assertEquals(DictationIssue.Cleanup.OK, DictationIssue.cleanupOutcome(listOf("cleanup disabled")))
    }
}
