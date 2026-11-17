package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalAiRuntimeTest {
    private fun dev(name: String, desc: String, type: String, backend: String) =
        LocalAiRuntime.Device(name, desc, type, backend)

    private val cpu = dev("CPU", "CPU", "cpu", "CPU")

    @Test
    fun deviceOrder_fastGpusFirstSlowOnesAfterCpu() {
        val adreno750 = listOf(
            cpu,
            dev("Vulkan0", "Adreno (TM) 750", "igpu", "Vulkan"),
            dev("GPUOpenCL", "QUALCOMM Adreno(TM) 750", "gpu", "OpenCL"),
        )
        assertEquals(listOf("GPUOpenCL", "Vulkan0", "CPU"), LocalAiRuntime.deviceOrder(adreno750))
        // Speech never runs on OpenCL.
        assertEquals(listOf("Vulkan0", "CPU"), LocalAiRuntime.deviceOrder(adreno750, "stt"))
        // Adreno 6xx and Mali are failover only (slower than the CPU / crash).
        val adreno650 = listOf(
            cpu,
            dev("Vulkan0", "Adreno (TM) 650", "igpu", "Vulkan"),
            dev("GPUOpenCL", "QUALCOMM Adreno(TM) 650", "gpu", "OpenCL"),
        )
        assertEquals(listOf("CPU", "Vulkan0", "GPUOpenCL"), LocalAiRuntime.deviceOrder(adreno650))
        val mali = listOf(dev("Vulkan0", "Mali-G77", "igpu", "Vulkan"), cpu)
        assertEquals(listOf("CPU", "Vulkan0"), LocalAiRuntime.deviceOrder(mali))
        // Unknown GPUs get explored.
        assertEquals(listOf("Vulkan0", "CPU"), LocalAiRuntime.deviceOrder(listOf(dev("Vulkan0", "Samsung Xclipse 940", "igpu", "Vulkan"), cpu)))
        assertEquals(listOf("CPU"), LocalAiRuntime.deviceOrder(listOf(cpu)))
    }

    @Test
    fun isSlowGpu_byVendorAndAdrenoGeneration() {
        assertTrue(LocalAiRuntime.isSlowGpu("Mali-G715"))
        assertTrue(LocalAiRuntime.isSlowGpu("Adreno (TM) 650"))
        assertTrue(LocalAiRuntime.isSlowGpu("QUALCOMM Adreno(TM) 619"))
        assertFalse(LocalAiRuntime.isSlowGpu("Adreno (TM) 740"))
        assertFalse(LocalAiRuntime.isSlowGpu("QUALCOMM Adreno(TM) 830"))
        assertFalse(LocalAiRuntime.isSlowGpu("Samsung Xclipse 940"))
    }

    @Test
    fun pickDevice_measuresEachOnceThenCheapestWins() {
        val order = listOf("GPUOpenCL", "Vulkan0", "CPU")
        // Nothing measured: GPU-first order.
        assertEquals(order, LocalAiRuntime.pickDevice(order, emptySet(), emptyMap()))
        // Unmeasured devices still get their turn before the measured ones.
        assertEquals(
            listOf("Vulkan0", "CPU", "GPUOpenCL"),
            LocalAiRuntime.pickDevice(order, emptySet(), mapOf("GPUOpenCL" to 900f)),
        )
        // All measured: cheapest first (a slow GPU loses to the CPU).
        assertEquals(
            listOf("CPU", "GPUOpenCL", "Vulkan0"),
            LocalAiRuntime.pickDevice(order, emptySet(), mapOf("GPUOpenCL" to 900f, "Vulkan0" to 5000f, "CPU" to 400f)),
        )
        // Devices after the CPU are failover only, even when unmeasured.
        assertEquals(listOf("CPU", "Vulkan0"), LocalAiRuntime.pickDevice(listOf("CPU", "Vulkan0"), emptySet(), emptyMap()))
        // Bad GPUs are skipped; the CPU is never dropped.
        assertEquals(listOf("Vulkan0", "CPU"), LocalAiRuntime.pickDevice(order, setOf("GPUOpenCL", "CPU"), emptyMap()))
    }

    private fun zip(dir: File, vararg names: String): File {
        val z = File(dir, "p.zip")
        ZipOutputStream(z.outputStream()).use { out ->
            for (n in names) {
                out.putNextEntry(ZipEntry(n))
                out.write(n.toByteArray())
                out.closeEntry()
            }
        }
        return z
    }

    @Test
    fun extractZip_flattensIntoLibDir() {
        val dir = Files.createTempDirectory("localai-zip").toFile()
        try {
            LocalAiRuntime.extractZip(zip(dir, "libllama.so", "lib/libggml.so"), File(dir, "out"))
            assertEquals("libllama.so", File(dir, "out/lib/libllama.so").readText())
            assertEquals("lib/libggml.so", File(dir, "out/lib/libggml.so").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractZip_rejectsTraversalNames() {
        val dir = Files.createTempDirectory("localai-zip").toFile()
        try {
            LocalAiRuntime.extractZip(zip(dir, "../.."), File(dir, "out"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun removeLegacyModels_dropsOldFormatsOnly() {
        val dir = Files.createTempDirectory("localai-legacy").toFile()
        try {
            val models = File(dir, "models").apply { mkdirs() }
            val cache = File(dir, "cache").apply { mkdirs() }
            val keep = listOf(File(models, "llm-gemma3-1b-it-q4_0.gguf"), File(models, "ggml-whisper-base"))
            val drop = listOf(
                File(models, "llm-gemma3-1b-it-int4.task"),
                File(models, "llm-gemma4-e2b-it.litertlm"),
                File(models, "llm-x.task.part.x"),
                File(models, "sherpa-moonshine-v2-tiny.en"),
                File(cache, "llm-a.task.xnnpack_cache"),
            )
            keep[0].writeText("x")
            keep[1].mkdirs()
            drop[3].mkdirs()
            for (f in drop) if (!f.exists()) f.writeText("x")
            LocalAiRuntime.removeLegacyModels(dir, cache)
            assertTrue(keep.all { it.exists() })
            for (f in drop) assertFalse(f.name, f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
