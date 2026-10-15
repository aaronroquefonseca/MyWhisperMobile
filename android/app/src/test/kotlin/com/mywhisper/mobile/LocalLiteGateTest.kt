package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lite-flavor gate for the Local STT/LLM rows (see MainActivity
 * SttScreen/CleanupScreen): lite builds ship no on-device packs
 * (sherpa / LiteRT-LM are fullImplementation-only), so the rows must
 * offer no download that could never run — NEED_FULL_FLAVOR with the
 * full-APK pointer instead. Full flavor (pack support present)
 * defers to the verified-pack row machine. Fail-closed in both
 * flavors: only PRESENT enables the toggle.
 */
class LocalLiteGateTest {

    @Test
    fun absentPackSupport_alwaysNeedFullFlavor() {
        // Every pack/download combination gates to NEED_FULL_FLAVOR
        // when pack support is absent (lite flavor) — no Download offered.
        val combos = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
            Triple(true, true, true),
        )
        for ((present, mismatch, downloading) in combos) {
            for (error in listOf(null, "", "boom")) {
                assertEquals(
                    "present=$present mismatch=$mismatch downloading=$downloading error=$error",
                    MainActivity.GatedRowState.NEED_FULL_FLAVOR,
                    MainActivity.gatedRowState(present, mismatch, downloading, error, false),
                )
            }
        }
    }

    @Test
    fun presentPackSupport_defersToPackRowMachine() {
        // Full flavor (pack support present): same outcomes as the underlying
        // verified-pack resolver.
        assertEquals(
            MainActivity.GatedRowState.MISSING,
            MainActivity.gatedRowState(false, false, false, null, true),
        )
        assertEquals(
            MainActivity.GatedRowState.DOWNLOADING,
            MainActivity.gatedRowState(false, false, true, null, true),
        )
        assertEquals(
            MainActivity.GatedRowState.PRESENT,
            MainActivity.gatedRowState(true, false, false, null, true),
        )
        assertEquals(
            MainActivity.GatedRowState.MISMATCH,
            MainActivity.gatedRowState(false, true, false, null, true),
        )
        assertEquals(
            MainActivity.GatedRowState.FAILED,
            MainActivity.gatedRowState(false, false, false, "boom", true),
        )
    }

    @Test
    fun fullFlavor_runnerAbsent_reportsRunnerMissingNotPointer() {
        // 2026-10-15 incident: a full APK built without the sherpa AAR
        // (silent fileTree miss) reported NEED_FULL_FLAVOR — the full-APK
        // pointer on a full build. Absent stack on FULL must separate
        // into RUNNER_MISSING (broken build; the gradle gate refuses it)
        // across every pack/download combination, and stay fail-closed.
        val combos = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
            Triple(true, true, true),
        )
        for ((present, mismatch, downloading) in combos) {
            for (error in listOf(null, "", "boom")) {
                val state = MainActivity.gatedRowState(
                    present, mismatch, downloading, error,
                    runnerSupported = false, isFullFlavor = true,
                )
                assertEquals(
                    "present=$present mismatch=$mismatch downloading=$downloading error=$error",
                    MainActivity.GatedRowState.RUNNER_MISSING,
                    state,
                )
                assertFalse(MainActivity.gatedToggleAllowed(state))
            }
        }
    }

    @Test
    fun fullFlavor_runnerPresent_defersToPackRowMachine() {
        // Healthy full build: same pack-machine outcomes as lite-present.
        assertEquals(
            MainActivity.GatedRowState.MISSING,
            MainActivity.gatedRowState(false, false, false, null, true, isFullFlavor = true),
        )
        assertEquals(
            MainActivity.GatedRowState.PRESENT,
            MainActivity.gatedRowState(true, false, false, null, true, isFullFlavor = true),
        )
    }

    @Test
    fun toggle_liveOnlyWhenPresent() {
        // Fail-closed in both flavors: NEED_FULL_FLAVOR never enables.
        assertFalse(
            MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.NEED_FULL_FLAVOR),
        )
        assertTrue(
            MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.PRESENT),
        )
        assertFalse(MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.MISSING))
        assertFalse(MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.DOWNLOADING))
        assertFalse(MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.MISMATCH))
        assertFalse(MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.FAILED))
        assertFalse(MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.RUNNER_MISSING))
    }

    @Test
    fun gatedToggle_agreesWithLegacyPackGate() {
        // PRESENT is the only live state under both gates.
        assertEquals(
            MainActivity.localToggleAllowed(MainActivity.LocalPackState.PRESENT),
            MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.PRESENT),
        )
        assertEquals(
            MainActivity.localToggleAllowed(MainActivity.LocalPackState.MISSING),
            MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.MISSING),
        )
        assertEquals(
            MainActivity.localToggleAllowed(MainActivity.LocalPackState.MISMATCH),
            MainActivity.gatedToggleAllowed(MainActivity.GatedRowState.MISMATCH),
        )
    }

    @Test
    fun runnerProbes_neverThrow() {
        // Classpath probes (no sherpa/LiteRT-LM on the JVM test
        // classpath, same as lite builds) must degrade to false, never
        // throw — callers keep the server fallback.
        MainActivity.sttRunnerSupported()
        MainActivity.llmPackSupported()
    }
}
