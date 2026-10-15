package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pack-gating tests for [LocalStt] (moonshine v2): missing/partial
 * packs report unavailable (callers keep the server fallback); only a
 * complete non-empty pack reports available. No native code is loaded
 * here.
 */
class LocalSttTest {
    @Test
    fun missingPack_isUnavailable() {
        val dir = Files.createTempDirectory("mw-local-missing").toFile()
        try {
            assertFalse(LocalStt.isAvailable(dir))
            assertEquals(File(dir, LocalStt.PACK_REL_DIR), LocalStt.packDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun partialPack_isUnavailable() {
        val dir = Files.createTempDirectory("mw-local-partial").toFile()
        try {
            val pack = LocalStt.packDir(dir)
            pack.mkdirs()
            File(pack, LocalStt.ENCODER).writeBytes(ByteArray(16))
            assertFalse(LocalStt.isAvailable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun completePack_isAvailable() {
        val dir = Files.createTempDirectory("mw-local-full").toFile()
        try {
            val pack = LocalStt.packDir(dir)
            pack.mkdirs()
            File(pack, LocalStt.ENCODER).writeBytes(ByteArray(16))
            File(pack, LocalStt.MERGED_DECODER).writeBytes(ByteArray(16))
            File(pack, LocalStt.TOKENS).writeBytes(ByteArray(16))
            assertTrue(LocalStt.isAvailable(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
