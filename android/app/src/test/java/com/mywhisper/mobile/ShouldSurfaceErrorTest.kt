package com.mywhisper.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only failures that matter surface as a bubble toast. */
class ShouldSurfaceErrorTest {

    @Test fun surfacesRealErrors() {
        assertTrue(shouldSurfaceError("mic unavailable"))
    }

    @Test fun silencesTooShort() {
        assertFalse(shouldSurfaceError("utterance too short"))
    }

    @Test fun silencesSilence() {
        assertFalse(shouldSurfaceError("silence, nothing captured"))
    }

    @Test fun emptyMessageSurfaces() {
        assertTrue(shouldSurfaceError(""))
    }
}
