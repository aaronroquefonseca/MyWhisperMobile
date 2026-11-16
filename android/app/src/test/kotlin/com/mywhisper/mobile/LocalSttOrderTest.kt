package com.mywhisper.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** STT chain order gate for the on-device decode ([LocalStt.isLocalFirst]). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LocalSttOrderTest {
    @Test
    fun localSttFirst_followsChainOrder() {
        fun entries(vararg e: Pair<String, Boolean>) = org.json.JSONArray().apply {
            for ((kind, on) in e) put(org.json.JSONObject().put("kind", kind).put("enabled", on))
        }
        assertTrue(LocalStt.localSttFirst(entries("Local" to true, "SelfHosted" to true)))
        assertFalse(LocalStt.localSttFirst(entries("SelfHosted" to true, "Local" to true)))
        assertTrue(LocalStt.localSttFirst(entries("SelfHosted" to false, "Local" to true)))
        assertFalse(LocalStt.localSttFirst(entries("Local" to false, "Cloud" to true)))
        assertFalse(LocalStt.localSttFirst(null))
    }
}
