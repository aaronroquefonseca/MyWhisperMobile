package com.mywhisper.mobile

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-device LLM cleanup (text polish only — never STT).
 *
 * Runner: llama.cpp (GGUF) from the downloadable runtime pack
 * ([LocalAiRuntime]), GPU first. Manifest, SHA-256, status and prompt
 * logic here is pure JVM; native calls only happen in [generate].
 *
 * Model files live under `<filesDir>/models`, downloaded by
 * [downloadModel] with SHA-256 verification against
 * `scripts/local-llm-models.json`. Binaries are NEVER committed.
 * When no model is ready the engine's cleanup chain falls through
 * to Ollama/cloud (default order unchanged).
 */
object LocalLlmManager {
    const val PROVIDER = "llama.cpp"
    const val DEFAULT_TASK_NAME = "cleanup.gguf"
    const val MODEL_EXT = "gguf"

    data class ModelAsset(
        val id: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        /** Minimum device RAM (MB) for the selector warning; 0 = unknown. */
        val minRamMb: Long = 0,
        /** Model has an optional thinking mode (Gemma 4). */
        val thinking: Boolean = false,
    )

    data class Status(
        val provider: String = PROVIDER,
        val modelPath: String,
        val ready: Boolean,
        val sizeBytes: Long?,
    )

    /** Legacy single-model slot: `<dir>/models/cleanup.gguf`. */
    fun defaultTaskFile(dir: File): File = File(File(dir, "models"), DEFAULT_TASK_NAME)

    /**
     * Parse `scripts/local-llm-models.json` (`{"models":[...]}`).
     * Hand-rolled (no org.json: `android.jar` stubs throw "not
     * mocked" under plain JVM unit tests).
     */
    fun parseManifest(json: String): List<ModelAsset> {
        val arrStart = json.indexOf('[')
        val arrEnd = json.lastIndexOf(']')
        if (arrStart < 0 || arrEnd <= arrStart) throw IllegalArgumentException("manifest has no models[]")
        val body = json.substring(arrStart + 1, arrEnd)
        if (body.isBlank()) return emptyList()
        val entries = body.split(Regex("\\},\\s*\\{")).mapIndexed { i, part ->
            var p = part.trim()
            if (i > 0) p = "{$p"
            if (!p.endsWith("}")) p += "}"
            p
        }
        return entries.map { entry ->
            fun strField(name: String): String =
                Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(entry)?.groupValues?.get(1)
                    ?: throw IllegalArgumentException("manifest entry lacks $name")
            fun longField(name: String): Long =
                Regex("\"$name\"\\s*:\\s*(\\d+)").find(entry)?.groupValues?.get(1)?.toLong()
                    ?: throw IllegalArgumentException("manifest entry lacks $name")
            fun optLongField(name: String): Long =
                Regex("\"$name\"\\s*:\\s*(\\d+)").find(entry)?.groupValues?.get(1)?.toLong() ?: 0
            ModelAsset(
                id = strField("id"),
                url = strField("url"),
                sha256 = strField("sha256"),
                sizeBytes = longField("size_bytes"),
                minRamMb = optLongField("min_ram_mb"),
                thinking = Regex("\"thinking\"\\s*:\\s*true").containsMatchIn(entry),
            )
        }
    }

    /** Byte-stable prompt packing — must match `local_llm::build_cleanup_prompt`. */
    fun buildPrompt(system: String, transcript: String): String =
        "$system\n\n<transcript>$transcript</transcript>"

    // -- Local LLM self-test (in-row "Run test", local-only) ---

    /** Fixed raw transcription injected for the Local self-test. */
    const val SELFTEST_TRANSCRIPT = "this is a test"

    /** PASS requires a non-empty reply within this budget. */
    const val SELFTEST_TIMEOUT_MS = 60_000L

    /** Output excerpt cap for the inline row result. */
    const val SELFTEST_EXCERPT_MAX = 120

    /**
     * Local-only self-test outcome (fail-closed, no fallback masking:
     * any throw or blank/timeout reply is FAIL).
     */
    data class SelfTestResult(
        val passed: Boolean,
        val latencyMs: Long,
        val excerpt: String,
        val error: String?,
        val timedOut: Boolean,
    )

    /** Fixed cleanup prompt for the self-test (system + fixed transcript). */
    fun selfTestPrompt(system: String): String =
        buildPrompt(system, SELFTEST_TRANSCRIPT)

    /** Single-line excerpt, capped at [maxChars] with an ellipsis. */
    fun selfTestExcerpt(output: String, maxChars: Int = SELFTEST_EXCERPT_MAX): String {
        val flat = output.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        return flat.take(maxChars).trimEnd() + "…"
    }

    /**
     * Pure PASS/FAIL mapping: PASS = ran + non-empty + under timeout
     * with no error. Excerpt carries the reply, else the error text.
     */
    fun mapSelfTestResult(
        output: String?,
        latencyMs: Long,
        timeoutMs: Long,
        error: String?,
    ): SelfTestResult {
        val timedOut = latencyMs > timeoutMs
        val excerpt = when {
            !output.isNullOrBlank() -> selfTestExcerpt(output)
            !error.isNullOrBlank() -> selfTestExcerpt(error)
            else -> ""
        }
        val passed = error == null && !timedOut && !output.isNullOrBlank()
        return SelfTestResult(
            passed = passed,
            latencyMs = latencyMs,
            excerpt = excerpt,
            error = error,
            timedOut = timedOut,
        )
    }

    /**
     * Local-path-only self-test: fixed prompt through [generate]
     * (default = on-device [generate], never Ollama/cloud). Measures
     * wall-clock latency and maps via [mapSelfTestResult].
     */
    fun runSelfTest(
        taskFile: File,
        systemPrompt: String,
        timeoutMs: Long = SELFTEST_TIMEOUT_MS,
        generateFn: (File, String) -> String,
    ): SelfTestResult {
        val start = System.nanoTime()
        return try {
            val out = generateFn(taskFile, selfTestPrompt(systemPrompt))
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapSelfTestResult(out, latencyMs, timeoutMs, null)
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapSelfTestResult(null, latencyMs, timeoutMs, rootMessage(e))
        }
    }

    /**
     * Deepest non-blank message in the cause chain. Reflection wraps
     * runner failures in InvocationTargetException (null message),
     * which otherwise surfaces as a bare "failed" with no detail.
     * Pure for unit tests.
     */
    @JvmStatic
    fun rootMessage(e: Throwable): String {
        var msg: String? = e.message
        var t = e.cause
        while (t != null) {
            if (!t.message.isNullOrBlank()) msg = t.message
            t = t.cause
        }
        return msg?.ifBlank { null } ?: "failed"
    }

    fun statusFor(taskFile: File): Status = Status(
        modelPath = taskFile.absolutePath,
        ready = taskFile.isFile,
        sizeBytes = if (taskFile.isFile) taskFile.length() else null,
    )

    /** Lowercase hex SHA-256 of [file] (streamed). */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Verification cancelled")
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean =
        file.isFile && sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)

    /** Resume decision for an interrupted download (pure, unit-tested). */
    enum class ResumeAction {
        /** Server honored Range: append to the partial. */
        APPEND,
        /** Server ignored Range (200) or 416 on a short partial: truncate, full download. */
        RESTART,
        /** 416 with a surviving partial: SHA-verify it (hit = done). */
        VERIFY,
        /** Non-2xx/416 status: fail, never write. */
        FAIL,
    }

    /**
     * Pure resume matrix shared by both download paths (STT packs +
     * LLM tasks): [tmpLen] is the surviving `.part` size (0 = none),
     * [httpCode] the status after following redirects.
     */
    @JvmStatic
    fun resumeAction(tmpLen: Long, httpCode: Int): ResumeAction {
        if (httpCode == 416) {
            // Range unsatisfiable: the partial usually holds the whole
            // file already — verify (hit = done, miss = one restart).
            // Only an empty partial restarts outright.
            return if (tmpLen > 0) ResumeAction.VERIFY else ResumeAction.RESTART
        }
        if (httpCode !in 200..299) return ResumeAction.FAIL
        if (httpCode == 206) return ResumeAction.APPEND
        return ResumeAction.RESTART
    }

    /**
     * Per-asset partial-download slot: concurrent downloads of
     * different assets into the same directory must not clobber
     * each other, so the temp name carries the asset id.
     */
    fun partFileFor(dest: File, assetId: String): File =
        File(dest.parentFile, dest.name + ".part." + assetId)

    /** Pure free-space gate (StatFs itself is Android-only; kept out for JVM tests). */
    fun hasFreeSpace(availableBytes: Long, requiredBytes: Long): Boolean =
        availableBytes >= requiredBytes

    /**
     * Download [asset] to [dest] (atomic rename), verifying SHA-256.
     * Returns the file on success, throws otherwise. Skips the
     * download when [dest] already verifies.
     */
    fun downloadModel(asset: ModelAsset, dest: File): File =
        downloadModel(asset, dest) { _, _ -> }

    /**
     * Progress-reporting overload: [onProgress] gets
     * (receivedBytes, totalBytes?) per chunk; total is the
     * response content-length when the server sends one, else the
     * manifest size. Partial downloads land in [partFileFor] and are
     * removed on failure; [deleteModel] clears them too.
     */
    fun downloadModel(
        asset: ModelAsset,
        dest: File,
        onProgress: (receivedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        dest.parentFile?.mkdirs()
        if (verifySha256(dest, asset.sha256)) return dest
        if (asset.sizeBytes > 0) {
            val dir = dest.parentFile
            if (dir != null) {
                val avail = StatFs(dir.absolutePath).availableBytes
                if (!hasFreeSpace(avail, asset.sizeBytes)) {
                    throw IllegalStateException(
                        "local-llm insufficient storage for ${asset.id}: " +
                            "need ${asset.sizeBytes}, have $avail",
                    )
                }
            }
        }
        val tmp = partFileFor(dest, asset.id)
        // Resume loop (single restart allowed): a surviving `.part`
        // continues via HTTP Range; servers that ignore Range (200)
        // or a short 416 fall back to one clean full download. The
        // trailing SHA-verify makes a stale/corrupt partial fail
        // closed either way.
        var existing = if (tmp.isFile) tmp.length() else 0L
        var allowRestart = true
        while (true) {
            if (existing <= 0) {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
            }
            val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                when (resumeAction(existing, code)) {
                    ResumeAction.FAIL -> throw IllegalStateException(
                        "local-llm download http $code",
                    )
                    ResumeAction.VERIFY -> {
                        if (verifySha256(tmp, asset.sha256)) {
                            onProgress(asset.sizeBytes, asset.sizeBytes)
                            break
                        }
                        if (!allowRestart) {
                            try {
                                tmp.delete()
                            } catch (_: Exception) {
                            }
                            throw IllegalStateException("local-llm sha256 mismatch for ${asset.id}")
                        }
                        allowRestart = false
                        existing = 0
                        continue
                    }
                    ResumeAction.RESTART -> {
                        if (!allowRestart) {
                            // Second sighting: just download fresh below.
                            existing = 0
                        } else {
                            allowRestart = false
                            existing = 0
                            continue
                        }
                    }
                    ResumeAction.APPEND -> Unit
                }
                val total = conn.contentLengthLong.takeIf { it > 0 }
                    ?: asset.sizeBytes.takeIf { it > 0 }
                // APPEND with existing bytes continues the file; a
                // fresh RESTART truncates (existing is 0 here).
                var received = existing
                onProgress(received, total)
                conn.inputStream.buffered().use { ins ->
                    FileOutputStream(tmp, existing > 0).buffered().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                    }
                }
                if (!verifySha256(tmp, asset.sha256)) {
                    tmp.delete()
                    throw IllegalStateException("local-llm sha256 mismatch for ${asset.id}")
                }
                break
            } finally {
                conn.disconnect()
            }
        }
        if (dest.exists() && !dest.delete()) throw IllegalStateException("local-llm replace failed")
        if (!tmp.renameTo(dest)) throw IllegalStateException("local-llm rename failed")
        return dest
    }

    /** Remove the model file + any per-asset partials (delete / retry cleanup). */
    fun deleteModel(dest: File, assets: List<ModelAsset> = emptyList()) {
        try { dest.delete() } catch (_: Exception) {}
        // A deleted pack must not linger resident (stale engine).
        try { dropCachedLlm(dest) } catch (_: Exception) {}
        for (a in assets) {
            try { partFileFor(dest, a.id).delete() } catch (_: Exception) {}
        }
        // Legacy/unknown partials with the same prefix.
        try {
            dest.parentFile?.listFiles { f ->
                f.name.startsWith(dest.name + ".part.")
            }?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** Cleanup output budget: roughly the transcript again plus headroom. Pure. */
    @JvmStatic
    fun maxTokensFor(transcript: String): Int = (transcript.length / 2 + 64).coerceIn(128, 1024)

    /**
     * Gemma 4 thinking switch: the system turn opens with `<|think|>`.
     * Off (budget 0) keeps answers direct; reasoning blocks are always
     * stripped natively. Pure.
     */
    @JvmStatic
    fun systemWithThinking(system: String, asset: ModelAsset?, thinkBudget: Int): String =
        if (asset?.thinking == true && thinkBudget > 0) "<|think|>\n$system" else system

    /**
     * Synchronous cleanup on the best device (GPU first, see
     * [LocalAiRuntime.runOnBestDevice]). Throws when the runtime or model
     * is missing or every device failed — callers fall back to the next
     * cleanup entry. The model stays resident per its keep-warm policy.
     */
    fun generate(
        context: android.content.Context,
        modelFile: File,
        system: String,
        transcript: String,
        asset: ModelAsset? = null,
    ): String {
        require(modelFile.isFile) { "local llm model not ready at ${modelFile.absolutePath}" }
        val app = context.applicationContext
        check(LocalAiRuntime.load(app.filesDir)) { "on-device AI runtime not installed" }
        val prefs = app.getSharedPreferences("mw_state", android.content.Context.MODE_PRIVATE)
        val keepSecs = asset?.let {
            LocalKeepWarm.readKeepSecs(prefs, LocalKeepWarm.KIND_LLM, it.id, LocalKeepWarm.IMMEDIATE)
        } ?: LocalKeepWarm.IMMEDIATE
        val think = asset?.let { LocalKeepWarm.readThinkBudget(prefs, it.id) } ?: 0
        val sys = systemWithThinking(system, asset, think)
        val user = "<transcript>$transcript</transcript>"
        return LocalAiRuntime.runOnBestDevice(prefs, "llm") { device ->
            Engine.generate(prefs, modelFile.absolutePath, device, sys, user, maxTokensFor(transcript), keepSecs)
        }
    }

    /** Close every resident LLM engine (service destroy). Never throws. */
    @JvmStatic
    fun releaseLocalEngines() {
        try {
            Engine.releaseAll()
        } catch (_: Throwable) {
        }
    }

    /** Drop the resident engine backing [modelFile] (delete/policy-immediate). */
    @JvmStatic
    fun dropCachedLlm(modelFile: File) {
        try {
            Engine.dropCached(modelFile.absolutePath)
        } catch (_: Throwable) {
        }
    }

    /** One resident llama.cpp model (memory bound), keyed by path + device. */
    internal object Engine {
        private const val N_CTX = 4096
        private const val TIMEOUT_MS = 120_000
        private var handle = 0L
        private var key: String? = null
        private var lastUsedMs = 0L
        private val lock = Any()

        fun generate(
            prefs: android.content.SharedPreferences,
            path: String,
            device: String,
            system: String,
            user: String,
            maxTokens: Int,
            keepSecs: Long,
        ): String =
            synchronized(lock) {
                val k = "$path|$device"
                val now = LocalKeepWarm.nowMs()
                if (handle != 0L && (key != k || LocalKeepWarm.isExpired(lastUsedMs, keepSecs, now))) closeLocked()
                val warm = handle != 0L
                if (handle == 0L) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    handle = LocalAiNative.llmLoad(path, device, N_CTX, LocalAiRuntime.threads())
                    key = k
                    android.util.Log.i("MwLlm", "loaded ${File(path).name} on $device in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                }
                try {
                    val out = LocalAiNative.llmGenerate(handle, system, user, maxTokens, 0f, TIMEOUT_MS)
                    // Cold calls include one-time GPU shader compiles; only
                    // they exist when the model is never kept resident.
                    if (warm || keepSecs == LocalKeepWarm.IMMEDIATE) {
                        costOf(LocalAiNative.lastStats())?.let { LocalAiRuntime.recordCost(prefs, "llm", device, it) }
                    }
                    out
                } catch (e: Exception) {
                    closeLocked() // a failed context may be poisoned; reload next time
                    throw e
                } finally {
                    if (keepSecs == LocalKeepWarm.IMMEDIATE) closeLocked() else lastUsedMs = LocalKeepWarm.nowMs()
                }
            }

        fun releaseAll() = synchronized(lock) { closeLocked() }

        /**
         * Estimated ms for a typical cleanup (400 prompt + 60 output
         * tokens) from one call's [LocalAiNative.lastStats]. Pure.
         */
        @JvmStatic
        fun costOf(st: DoubleArray): Double? {
            val fresh = st[0] - st[1]
            if (fresh < 1 || st[2] < 1) return null
            return st[3] / fresh * 400 + st[4] / st[2] * 60
        }

        fun dropCached(path: String) = synchronized(lock) {
            if (key?.startsWith("$path|") == true) closeLocked()
        }

        private fun closeLocked() {
            if (handle != 0L) LocalAiNative.llmFree(handle)
            handle = 0L
            key = null
            lastUsedMs = 0L
        }
    }
}
