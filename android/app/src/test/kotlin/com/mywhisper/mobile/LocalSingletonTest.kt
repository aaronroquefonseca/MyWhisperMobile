package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local singleton (see MainActivity STT/LLM dialogs): only one Local
 * entry per list, its name locked to "Local". Both chains share the
 * identical pattern. Pure JVM (no Robolectric): helpers take injected
 * values — org.json stays at the Compose call sites.
 */
class LocalSingletonTest {

    // -- Fixed name ---------------------------------------------------

    @Test
    fun name_fixedToLocal() {
        assertEquals("Local", MainActivity.LOCAL_ENTRY_NAME)
    }

    @Test
    fun name_lockedOnlyWhenKindIsLocal() {
        assertTrue(MainActivity.localNameLocked("Local"))
        assertFalse(MainActivity.localNameLocked("SelfHosted"))
        assertFalse(MainActivity.localNameLocked("Ollama"))
        assertFalse(MainActivity.localNameLocked("Cloud"))
        assertTrue(MainActivity.isLocalKind("Local"))
        assertFalse(MainActivity.isLocalKind("Ollama"))
    }

    @Test
    fun save_forcesLocalName() {
        // Rename-migrate on save: Local-kind rows persist as "Local"
        // even when the draft carries a custom name.
        assertEquals("Local", MainActivity.normalizedEntryName("Local", "My chip"))
        assertEquals("Local", MainActivity.normalizedEntryName("Local", ""))
        assertEquals("Local", MainActivity.normalizedEntryName("Local", "local"))
        assertEquals("Server", MainActivity.normalizedEntryName("SelfHosted", "Server"))
        assertEquals("Cloud", MainActivity.normalizedEntryName("Cloud", "Cloud"))
    }

    @Test
    fun migrate_flagsNonLocalNames() {
        assertTrue(MainActivity.needsLocalRename("Local", "My chip"))
        assertTrue(MainActivity.needsLocalRename("Local", ""))
        assertTrue(MainActivity.needsLocalRename("Local", "local"))
        assertFalse(MainActivity.needsLocalRename("Local", "Local"))
        assertFalse(MainActivity.needsLocalRename("SelfHosted", "Server"))
        assertFalse(MainActivity.needsLocalRename("Ollama", "Local"))
    }

    // -- Singleton refuse ----------------------------------------------

    @Test
    fun hasLocal_detectsExisting() {
        assertTrue(MainActivity.hasLocalEntry(listOf("SelfHosted", "Local")))
        assertFalse(MainActivity.hasLocalEntry(listOf("SelfHosted", "Cloud")))
        assertFalse(MainActivity.hasLocalEntry(emptyList()))
    }

    @Test
    fun singleton_addLocalRefusedWhenOneExists() {
        // Add flow: kind Local + existing Local -> blocked.
        assertTrue(
            MainActivity.localSingletonBlocked(listOf("SelfHosted", "Local"), "Local"),
        )
        assertFalse(
            MainActivity.localSingletonBlocked(listOf("SelfHosted", "Cloud"), "Local"),
        )
        assertFalse(
            MainActivity.localSingletonBlocked(emptyList(), "Local"),
        )
    }

    @Test
    fun singleton_nonLocalKindsNeverBlocked() {
        assertFalse(
            MainActivity.localSingletonBlocked(listOf("Local"), "SelfHosted"),
        )
        assertFalse(
            MainActivity.localSingletonBlocked(listOf("Local"), "Ollama"),
        )
        assertFalse(
            MainActivity.localSingletonBlocked(listOf("Local"), "Cloud"),
        )
    }

    @Test
    fun singleton_editExcludesSelf() {
        // Edit flow passes kinds minus the row being edited: editing
        // the existing Local itself must not self-block.
        assertFalse(MainActivity.localSingletonBlocked(emptyList(), "Local"))
        // Retargeting a second row to Local while one exists blocks.
        assertTrue(MainActivity.localSingletonBlocked(listOf("Local"), "Local"))
    }

    // -- Edit hides kind picker -----------------------------------------

    @Test
    fun edit_localHidesKindPickerOnBothScreens() {
        // Same pattern STT + LLM: editing a Local entry hides the kind
        // picker (no change-away footgun); other kinds keep it.
        assertTrue(MainActivity.localEditHidesKindPicker("Local"))
        assertFalse(MainActivity.localEditHidesKindPicker("SelfHosted"))
        assertFalse(MainActivity.localEditHidesKindPicker("Ollama"))
        assertFalse(MainActivity.localEditHidesKindPicker("Cloud"))
    }
}
