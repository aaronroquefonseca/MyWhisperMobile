package com.mywhisper.mobile

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipInputStream

/**
 * On-device AI runtime pack: llama.cpp + whisper.cpp built from source
 * (scripts/build-localai.sh), downloaded on first local-model download and
 * loaded from app storage. The APK carries none of it.
 *
 * Layout: `<filesDir>/localai/<packId>/lib/` (the .so files). A pack is only used once
 * its `.ok` marker exists (written after a SHA-verified, fully extracted
 * zip). Files are read-only (Android 14 dynamic-code-loading rule).
 *
 * Devices: GPU first, CPU only when every GPU failed on this phone. A GPU
 * that throws is marked bad for that task kind; one that kills the process
 * natively is caught on the next start by a leftover "attempt" marker.
 * Marks are per pack id, so a new pack (driver workarounds) retries.
 */
object LocalAiRuntime {
    private const val TAG = "MwLocalAi"
    const val CPU = "CPU"

    data class Pack(val id: String, val url: String, val sha256: String, val sizeBytes: Long)

    val PACK = Pack(BuildConfig.LOCALAI_ID, BuildConfig.LOCALAI_URL, BuildConfig.LOCALAI_SHA256, BuildConfig.LOCALAI_SIZE)

    /** Dependency order for System.load (sonames resolve to already-loaded libs). */
    internal val LOAD_ORDER = listOf("libc++_shared.so", "libggml-base.so", "libggml.so", "libllama.so", "libmwlocalai.so")

    fun root(filesDir: File): File = File(filesDir, "localai")
    fun packDir(filesDir: File, id: String = PACK.id): File = File(root(filesDir), id)
    private fun libDir(filesDir: File) = File(packDir(filesDir), "lib")

    fun isInstalled(filesDir: File): Boolean =
        PACK.id.isNotBlank() &&
            File(packDir(filesDir), ".ok").isFile &&
            LOAD_ORDER.all { File(libDir(filesDir), it).isFile }

    /** This build ships a pack URL and the device is arm64 (the only pack ABI). Never throws. */
    fun supported(): Boolean =
        try {
            PACK.url.isNotBlank() && android.os.Build.SUPPORTED_ABIS?.contains("arm64-v8a") == true
        } catch (_: Throwable) {
            false
        }

    /**
     * Download + verify + extract the pack if missing; no-op when installed.
     * Removes packs of other ids (older app versions). Throws on failure.
     */
    @Synchronized
    fun ensureDownloaded(filesDir: File, onProgress: (Long, Long?) -> Unit) {
        if (isInstalled(filesDir)) return
        check(PACK.url.isNotBlank() && PACK.sha256.isNotBlank()) { "this build has no on-device AI runtime" }
        val root = root(filesDir).apply { mkdirs() }
        val zip = File(root, "${PACK.id}.zip")
        LocalLlmManager.downloadModel(
            LocalLlmManager.ModelAsset(PACK.id, PACK.url, PACK.sha256, PACK.sizeBytes),
            zip,
            onProgress,
        )
        val tmp = File(root, "${PACK.id}.tmp")
        tmp.deleteRecursively()
        extractZip(zip, tmp)
        tmp.walkTopDown().filter { it.isFile }.forEach { it.setReadOnly() }
        File(tmp, ".ok").writeText(PACK.sha256)
        val dest = packDir(filesDir)
        dest.deleteRecursively()
        check(tmp.renameTo(dest)) { "runtime install rename failed" }
        zip.delete()
        removeStalePacks(filesDir)
    }

    /** Delete every pack dir/zip whose id is not the current one. Never throws. */
    fun removeStalePacks(filesDir: File) {
        try {
            root(filesDir).listFiles()?.forEach { f ->
                if (f.name != PACK.id && !(f.name.startsWith(PACK.id) && f.name.endsWith(".zip"))) {
                    f.deleteRecursively()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Remove model files from the MediaPipe/LiteRT-LM/sherpa-onnx era
     * (`.task`, `.litertlm`, XNNPack caches, `sherpa-*` STT dirs): no
     * runner can read them any more. Never throws.
     */
    fun removeLegacyModels(filesDir: File, cacheDir: File) {
        try {
            File(filesDir, "models").listFiles()?.forEach { f ->
                val n = f.name
                val legacy = n.startsWith("sherpa-") ||
                    n.endsWith(".task") || n.contains(".litertlm") || n.contains(".xnnpack_cache") ||
                    n.contains(".task.part.")
                if (legacy) f.deleteRecursively()
            }
            cacheDir.listFiles()?.forEach { if (it.name.contains(".xnnpack_cache")) it.delete() }
        } catch (_: Exception) {
        }
    }

    /**
     * After an app update ships a new pack id, local models are still on
     * disk but their runtime is not: queue the runtime download through
     * the first installed model's own download job (its files verify and
     * are skipped). Cheap existence checks only. Never throws.
     */
    fun ensureForInstalledModels(ctx: android.content.Context) {
        try {
            val dir = ctx.filesDir
            if (!supported() || isInstalled(dir)) return
            LocalSttModels.MODELS.firstOrNull { e ->
                e.files.all { File(LocalSttModels.dirFor(dir, e), it.name).isFile }
            }?.let { ModelDownloadManager.enqueueSttModel(ctx, it.id); return }
            LocalLlmManager.parseManifest(MainActivity.LLM_MANIFEST_JSON).firstOrNull { a ->
                MainActivity.llmTaskFileFor(dir, a).isFile
            }?.let { ModelDownloadManager.enqueueLlmAsset(ctx, it) }
        } catch (e: Exception) {
            Log.w(TAG, "runtime re-fetch not queued: ${e.message}")
        }
    }

    /** Delete the installed runtime (no local model left). Never throws. */
    fun delete(filesDir: File) {
        try {
            root(filesDir).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Zip entries flattened into [dest] by file name; rejects anything that is not a plain name. */
    internal fun extractZip(zip: File, dest: File) {
        val lib = File(dest, "lib").apply { mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                require(name.matches(Regex("[A-Za-z0-9._+-]+")) && name != "." && name != "..") {
                    "bad runtime entry ${e.name}"
                }
                File(lib, name).outputStream().use { zin.copyTo(it) }
            }
        }
    }

    @Volatile private var loaded = false

    /** Load the native libs once per process. False when not installed or loading failed. */
    @Synchronized
    fun load(filesDir: File): Boolean {
        if (loaded) return true
        if (!isInstalled(filesDir)) return false
        return try {
            val dir = libDir(filesDir)
            for (name in LOAD_ORDER) System.load(File(dir, name).absolutePath)
            val n = LocalAiNative.init(dir.absolutePath)
            Log.i(TAG, "runtime ${PACK.id} loaded, $n devices: ${LocalAiNative.devicesJson()}")
            loaded = true
            true
        } catch (e: Throwable) {
            Log.w(TAG, "runtime load failed: ${e.message}")
            false
        }
    }

    data class Device(val name: String, val desc: String, val type: String, val backend: String)

    fun devices(): List<Device> {
        val arr = JSONArray(LocalAiNative.devicesJson())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(o.optString("name"), o.optString("desc"), o.optString("type"), o.optString("backend"))
        }
    }

    /**
     * Candidate devices for [kind] ("llm"/"stt") in preference order. GPUs
     * that measured slower than the CPU (or crashed) on llama.cpp/whisper.cpp
     * come after the CPU, so they are only a failover and never explored:
     * - ARM Mali: 10-20x slower than the CPU (Exynos 990 / Mali-G77).
     * - Adreno 6xx and older: llama.cpp's OpenCL kernels target Adreno
     *   7xx+; on Adreno 650 OpenCL generated 6x slower than the CPU and
     *   Vulkan aborted on a shader the driver rejects.
     * Speech never uses OpenCL (ggml-opencl segfaults on whisper graphs).
     * Among the rest, Adreno prefers OpenCL and other GPUs Vulkan. Pure.
     */
    @JvmStatic
    internal fun deviceOrder(devices: List<Device>, kind: String = "llm"): List<String> {
        val gpus = devices.filter {
            (it.type == "gpu" || it.type == "igpu") &&
                !(kind == "stt" && it.backend.contains("OpenCL", ignoreCase = true))
        }
        val adreno = gpus.any { it.desc.contains("Adreno", ignoreCase = true) }
        val (slow, fast) = gpus.partition { isSlowGpu(it.desc) }
        val ranked = fast.sortedBy { d ->
            val openCl = d.backend.contains("OpenCL", ignoreCase = true)
            if (openCl == adreno) 0 else 1
        }
        return ranked.map { it.name } + CPU + slow.map { it.name }
    }

    /** Mali, or Adreno below the 7xx generation (e.g. "Adreno (TM) 650"). Pure. */
    @JvmStatic
    internal fun isSlowGpu(desc: String): Boolean {
        if (desc.contains("Mali", ignoreCase = true)) return true
        val adreno = Regex("Adreno[^0-9]*([0-9]{3})", RegexOption.IGNORE_CASE).find(desc) ?: return false
        return adreno.groupValues[1].toInt() < 700
    }

    // -- per-device failure + speed memory ----------------------------------

    private fun badKey(kind: String, device: String) = "localai_bad_${PACK.id}_${kind}_$device"
    private fun attemptKey(kind: String) = "localai_attempt_${PACK.id}_$kind"
    private fun costKey(kind: String, device: String) = "localai_cost_${PACK.id}_${kind}_$device"

    /**
     * Devices to try, in order, from [order], skipping [bad] ones. Every
     * device up to the CPU is measured once (first unmeasured wins), then
     * the cheapest measured one leads: a GPU that works but is slower
     * than the CPU on this phone loses. Devices after the CPU in [order]
     * are failover only. Pure.
     */
    @JvmStatic
    internal fun pickDevice(order: List<String>, bad: Set<String>, costs: Map<String, Float>): List<String> {
        val usable = order.filter { it == CPU || it !in bad }
        // Devices ranked after the CPU are failover only: never explored.
        val cpuAt = usable.indexOf(CPU)
        val explore = if (cpuAt < 0) usable else usable.subList(0, cpuAt + 1)
        val failover = if (cpuAt < 0) emptyList() else usable.subList(cpuAt + 1, usable.size)
        val unmeasured = explore.filter { costs[it] == null }
        val measured = explore.filter { costs[it] != null }.sortedBy { costs.getValue(it) }
        return unmeasured + measured + failover
    }

    /** Store the latest measured cost (lower is better) of [kind] on [device]. */
    fun recordCost(prefs: SharedPreferences, kind: String, device: String, cost: Double) {
        if (cost.isFinite() && cost > 0) prefs.edit().putFloat(costKey(kind, device), cost.toFloat()).apply()
    }

    /**
     * Run [block] on the best device for [kind] ("llm"/"stt"), falling
     * through to the next on failure. A leftover attempt marker (process
     * died natively mid-call) marks that device bad before choosing.
     */
    fun <T> runOnBestDevice(prefs: SharedPreferences, kind: String, block: (String) -> T): T {
        prefs.getString(attemptKey(kind), null)?.let { crashed ->
            Log.w(TAG, "$kind: previous attempt on $crashed died natively, marking bad")
            prefs.edit().putBoolean(badKey(kind, crashed), true).remove(attemptKey(kind)).commit()
        }
        val order = deviceOrder(devices(), kind)
        val bad = order.filter { prefs.getBoolean(badKey(kind, it), false) }.toSet()
        val costs = order.mapNotNull { d ->
            if (prefs.contains(costKey(kind, d))) d to prefs.getFloat(costKey(kind, d), 0f) else null
        }.toMap()
        var last: Throwable? = null
        for (dev in pickDevice(order, bad, costs)) {
            prefs.edit().putString(attemptKey(kind), dev).commit()
            try {
                val out = block(dev)
                prefs.edit().remove(attemptKey(kind)).apply()
                return out
            } catch (e: Exception) {
                prefs.edit().remove(attemptKey(kind)).apply()
                if (dev == CPU) throw e
                Log.w(TAG, "$kind on $dev failed (${e.message}), marking bad")
                prefs.edit().putBoolean(badKey(kind, dev), true).apply()
                last = e
            }
        }
        throw last ?: IllegalStateException("no device for $kind")
    }

    /** Worker threads for CPU-side work: half the cores, 2..4. */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
