package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** STT chain split around the on-device decode ([LocalStt.remotesAroundLocal]). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LocalSttOrderTest {
    @Test
    fun remotesAroundLocal_followsChainOrder() {
        fun entries(vararg e: Pair<String, Boolean>) = org.json.JSONArray().apply {
            for ((kind, on) in e) put(org.json.JSONObject().put("kind", kind).put("enabled", on))
        }
        fun around(vararg e: Pair<String, Boolean>) = LocalStt.remotesAroundLocal(entries(*e))
        assertEquals(false to true, around("Local" to true, "SelfHosted" to true))
        assertEquals(true to false, around("SelfHosted" to true, "Local" to true))
        assertEquals(true to true, around("SelfHosted" to true, "Local" to true, "Cloud" to true))
        assertEquals(false to true, around("SelfHosted" to false, "Local" to true, "Cloud" to true))
        assertEquals(true to false, around("Local" to false, "Cloud" to true))
        assertEquals(false to false, around("Local" to true))
        assertEquals(false to false, LocalStt.remotesAroundLocal(null))
    }
}
