package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * Reflection-shim tests for [LocalStt.SherpaRunner]: the sherpa-onnx AAR
 * is full-flavor-only, so every bridge entry must degrade to null /
 * no-op when classes or files are absent — never throw. These run on
 * plain JVM unit tests with no AAR on the classpath, and must ALSO
 * pass with the AAR present (CI fetches it before unit tests), so no
 * test asserts [LocalStt.SherpaRunner.isSupported].
 */
class LocalSttReflectionTest {
    @Test
    fun missingClass_loadsNull() {
        assertNull(LocalStt.SherpaRunner.loadClass("com.mywhisper.mobile.NoSuchClassXyz"))
    }

    @Test
    fun createRecognizer_missingFiles_returnsNull() {
        val dir = Files.createTempDirectory("mw-sherpa-missing").toFile()
        try {
            // No model files on disk: null whether the AAR classes are
            // present (native create fails) or absent (CNFE path).
            assertNull(
                LocalStt.SherpaRunner.createRecognizer(
                    dir,
                    LocalStt.moonshineEntry(),
                    "en",
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decodeChunkText_wrongReceiver_returnsNull() {
        // Plain Object has no createStream: null, no throw — with or
        // without the AAR on the test classpath.
        assertNull(LocalStt.SherpaRunner.decodeChunkText(Any(), FloatArray(160), 16000))
    }

    @Test
    fun release_isUnloadSafe() {
        LocalStt.SherpaRunner.release(null)
        LocalStt.SherpaRunner.release(Any())
        LocalStt.unload()
    }

    @Test
    fun chunkPcm_stillPureJvm() {
        // Shim refactor must not break the pure chunker.
        assertEquals(emptyList<IntRange>(), LocalStt.chunkPcm(ShortArray(0)))
    }
}
