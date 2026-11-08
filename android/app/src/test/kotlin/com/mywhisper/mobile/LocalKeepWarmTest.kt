package com.mywhisper.mobile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep-warm policies + thinking budgets (see LocalKeepWarm): pure
 * idle math, preset lists, and SharedPreferences round-trips via a
 * hand fake (no Robolectric — JUnit only).
 */
class LocalKeepWarmTest {

    private class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun getLong(key: String, defValue: Long): Long =
            map[key] as? Long ?: defValue

        override fun getInt(key: String, defValue: Int): Int =
            map[key] as? Int ?: defValue

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                    apply { map[key] = value }

                override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                    apply { map[key] = value }

                override fun putString(key: String, value: String?): SharedPreferences.Editor =
                    apply { map[key] = value }

                override fun putStringSet(
                    key: String,
                    values: MutableSet<String>?,
                ): SharedPreferences.Editor = apply { map[key] = values }

                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                    apply { map[key] = value }

                override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                    apply { map[key] = value }

                override fun remove(key: String): SharedPreferences.Editor =
                    apply { map.remove(key) }

                override fun clear(): SharedPreferences.Editor =
                    apply { map.clear() }

                override fun commit(): Boolean = true

                override fun apply() {}
            }

        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (map[key] as? MutableSet<String> ?: defValues)

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            map[key] as? Float ?: defValue

        override fun getAll(): MutableMap<String, *> = map

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }
    }

    @Test
    fun presets_coverImmediateTimedAndIndefinite() {
        assertEquals(
            listOf(0L, 60L, 600L, 1800L, -1L),
            LocalKeepWarm.presets(),
        )
        assertEquals(listOf(0, 512, 1024, 2048), LocalKeepWarm.thinkBudgets())
    }

    @Test
    fun keys_scopeByKindAndId() {
        assertEquals("keep_stt_moonshine", LocalKeepWarm.keepKey("stt", "moonshine"))
        assertEquals("keep_llm_gemma3", LocalKeepWarm.keepKey("llm", "gemma3"))
        assertEquals("think_llm_gemma4-e2b-it", LocalKeepWarm.thinkKey("gemma4-e2b-it"))
    }

    @Test
    fun isExpired_idlePastWindowOnly_indefiniteNever() {
        // Idle 61s of a 60s window → expired.
        assertTrue(LocalKeepWarm.isExpired(0L, 60L, 61_001L))
        // Inside the window (equal counts as inside: strictly greater).
        assertFalse(LocalKeepWarm.isExpired(0L, 60L, 60_000L))
        assertFalse(LocalKeepWarm.isExpired(1_000L, 60L, 30_000L))
        // Immediate expires on any positive idle.
        assertTrue(LocalKeepWarm.isExpired(0L, 0L, 1L))
        assertFalse(LocalKeepWarm.isExpired(1_000L, 0L, 1_000L))
        // Indefinite never expires.
        assertFalse(LocalKeepWarm.isExpired(0L, -1L, Long.MAX_VALUE))
    }

    @Test
    fun keepSecs_roundTrips_missingFallsToDefault() {
        val prefs = FakePrefs()
        assertEquals(7L, LocalKeepWarm.readKeepSecs(prefs, "llm", "m", 7L))
        LocalKeepWarm.writeKeepSecs(prefs, "llm", "m", 600L)
        assertEquals(600L, LocalKeepWarm.readKeepSecs(prefs, "llm", "m", 7L))
        // Kinds do not collide.
        assertEquals(7L, LocalKeepWarm.readKeepSecs(prefs, "stt", "m", 7L))
        LocalKeepWarm.writeKeepSecs(prefs, "llm", "m", -1L)
        assertEquals(-1L, LocalKeepWarm.readKeepSecs(prefs, "llm", "m", 7L))
    }

    @Test
    fun thinkBudget_roundTrips_defaultOff() {
        val prefs = FakePrefs()
        assertEquals(0, LocalKeepWarm.readThinkBudget(prefs, "e2b"))
        LocalKeepWarm.writeThinkBudget(prefs, "e2b", 1024)
        assertEquals(1024, LocalKeepWarm.readThinkBudget(prefs, "e2b"))
    }
}
