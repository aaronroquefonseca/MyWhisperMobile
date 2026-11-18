package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [userFacingFinishError]: the stale core fallback copy ("no server
 * STT configured … on-device is not supported yet") is rewritten for
 * Android, where the Kotlin local-first hook runs before the server
 * chain; every other message passes through byte-identical (notably
 * "silence", which the surfaces map to the empty state).
 */
class DictationErrorTest {

    @Test
    fun rewritesStaleNoServerCopy() {
        assertEquals(
            "On-device found nothing and no server is configured — " +
                "check the Local entry and its model, or add a server.",
            userFacingFinishError(
                "no server STT configured: add a Self-hosted or Cloud entry " +
                    "(Local/on-device is not supported yet)",
            ),
        )
    }

    @Test
    fun passesOtherMessagesThrough() {
        assertEquals("silence", userFacingFinishError("silence"))
        assertEquals("transcription failed", userFacingFinishError("transcription failed"))
        assertEquals("", userFacingFinishError(""))
        val long = "no server STT configured".uppercase()
        // Case-sensitive match: only the exact committed core copy rewrites.
        assertEquals(long, userFacingFinishError(long))
    }

    @Test
    fun rewrittenCopyIsNotARoutineDrop() {
        // The bubble toast must SHOW it: it must not match the
        // routine-drop filter.
        val msg = userFacingFinishError("no server STT configured: x")
        assertTrue(shouldSurfaceError(msg))
    }
}
