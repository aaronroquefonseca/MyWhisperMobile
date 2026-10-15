package com.mywhisper.mobile

import android.os.StatFs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-device LLM cleanup (text polish only — never STT).
 *
 * Chosen provider: LiteRT-LM `.litertlm`
 * (`com.google.ai.edge.litertlm:litertlm-android`, GPU backend with
 * CPU fallback). Inference itself is loaded lazily via reflection so
 * plain JVM unit tests never touch the native runner; all logic
 * here (manifest, SHA-256, status, prompt packing) is pure JVM.
 *
 * Model files live under `<filesDir>/models` (file
 * `cleanup.litertlm`), downloaded by [downloadModel] with SHA-256
 * verification against `scripts/local-llm-models.json`. Binaries are
 * NEVER committed. When no model is ready the engine's cleanup chain
 * falls through to Ollama/cloud (default order unchanged).
 *
 * Migration note: the legacy MediaPipe `.task` slot (`cleanup.task`)
 * does NOT load under LiteRT-LM (different bundle format). Fresh
 * downloads land in `cleanup.litertlm`; [deleteModel] also scrubs the
 * legacy slot and its partials.
 */
object LocalLlmManager {
    const val PROVIDER = "litertlm"
    const val DEFAULT_MODEL_NAME = "cleanup.litertlm"

    /** Legacy MediaPipe slot — never loaded, scrubbed by [deleteModel]. */
    const val LEGACY_TASK_NAME = "cleanup.task"

    data class ModelAsset(
        val id: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        /** Minimum device RAM (MB) for the selector warning; 0 = unknown. */
        val minRamMb: Long = 0,
    )

    data class Status(
        val provider: String = PROVIDER,
        val modelPath: String,
        val ready: Boolean,
        val sizeBytes: Long?,
    )

    /** Default model slot: `<dir>/models/cleanup.litertlm`. */
    fun defaultModelFile(dir: File): File = File(File(dir, "models"), DEFAULT_MODEL_NAME)

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
        generateFn: (File, String) -> String = { f, p -> generate(f, p) },
    ): SelfTestResult {
        val start = System.nanoTime()
        return try {
            val out = generateFn(taskFile, selfTestPrompt(systemPrompt))
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapSelfTestResult(out, latencyMs, timeoutMs, null)
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            mapSelfTestResult(null, latencyMs, timeoutMs, e.message ?: "failed")
        }
    }

    fun statusFor(modelFile: File): Status = Status(
        modelPath = modelFile.absolutePath,
        ready = modelFile.isFile,
        sizeBytes = if (modelFile.isFile) modelFile.length() else null,
    )

    /** Lowercase hex SHA-256 of [file] (streamed). */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean =
        file.isFile && sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)

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
        val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("local-llm download http ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
                ?: asset.sizeBytes.takeIf { it > 0 }
            var received = 0L
            onProgress(0L, total)
            conn.inputStream.buffered().use { ins ->
                tmp.outputStream().buffered().use { out ->
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
        } finally {
            conn.disconnect()
        }
        if (!verifySha256(tmp, asset.sha256)) {
            tmp.delete()
            throw IllegalStateException("local-llm sha256 mismatch for ${asset.id}")
        }
        if (dest.exists() && !dest.delete()) throw IllegalStateException("local-llm replace failed")
        if (!tmp.renameTo(dest)) throw IllegalStateException("local-llm rename failed")
        return dest
    }

    /**
     * Remove the model file + any per-asset partials (delete / retry
     * cleanup), plus the legacy MediaPipe `cleanup.task` slot and its
     * partials (unloadable under LiteRT-LM — never re-downloaded).
     */
    fun deleteModel(dest: File, assets: List<ModelAsset> = emptyList()) {
        try { dest.delete() } catch (_: Exception) {}
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
        // Legacy MediaPipe slot: same directory, old file name.
        try {
            val legacy = File(dest.parentFile, LEGACY_TASK_NAME)
            if (legacy.absolutePath != dest.absolutePath) {
                legacy.delete()
                legacy.parentFile?.listFiles { f ->
                    f.name.startsWith(LEGACY_TASK_NAME + ".part.")
                }?.forEach { it.delete() }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Synchronous single-turn generation via the LiteRT-LM runner.
     * Loaded by reflection (`com.google.ai.edge.litertlm.Engine`)
     * so the litertlm AAR is optional at unit-test time.
     * Throws when the model file is missing or the runner is unavailable —
     * callers fall back to the next cleanup entry.
     */
    fun generate(modelFile: File, prompt: String, maxTokens: Int = 512): String {
        require(modelFile.isFile) { "local llm model not ready at ${modelFile.absolutePath}" }
        return LiteRtLmRunner.generate(modelFile.absolutePath, prompt, maxTokens)
    }

    /** Reflection bridge to LiteRT-LM `Engine` (GPU, CPU fallback; temp defaults). */
    internal object LiteRtLmRunner {
        private const val PKG = "com.google.ai.edge.litertlm"

        /** True when the LiteRT-LM runner is on the classpath. Never throws. */
        fun isSupported(): Boolean =
            try {
                Class.forName("$PKG.Engine")
                true
            } catch (_: Throwable) {
                false
            }

        fun generate(modelPath: String, prompt: String, maxTokens: Int): String {
            // GPU first; ANY GPU-attempt failure (missing delegate,
            // init error) retries once on CPU. A CPU-attempt failure
            // throws — callers fall back to the server chain.
            // A missing runner (CNFE) fails both attempts fail-closed.
            val gpuError = try {
                return tryBackend(modelPath, prompt, maxTokens, gpuFirst = true)
            } catch (e: Throwable) {
                e
            }
            try {
                return tryBackend(modelPath, prompt, maxTokens, gpuFirst = false)
            } catch (cpuError: Throwable) {
                throw IllegalStateException(
                    "local-llm unavailable (gpu: ${gpuError.message}; cpu: ${cpuError.message})",
                    cpuError,
                )
            }
        }

        private fun tryBackend(
            modelPath: String,
            prompt: String,
            maxTokens: Int,
            gpuFirst: Boolean,
        ): String {
            val engineClass: Class<*>
            val configClass: Class<*>
            val backendClass: Class<*>
            try {
                engineClass = Class.forName("$PKG.Engine")
                configClass = Class.forName("$PKG.EngineConfig")
                backendClass = Class.forName("$PKG.Backend")
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("local-llm runner unavailable (litertlm missing)", e)
            }
            val backend = newBackend(backendClass, gpuFirst)
            val engine = try {
                val config = newEngineConfig(configClass, modelPath, backend)
                val ctor = engineClass.constructors.firstOrNull {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(configClass)
                } ?: throw IllegalStateException("local-llm Engine(EngineConfig) missing")
                ctor.newInstance(config)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalStateException("local-llm engine create failed", e)
            }
            try {
                invoke(engine, "initialize")
                val conversation = newConversation(engine, engineClass)
                try {
                    return sendPrompt(conversation, prompt, maxTokens)
                } finally {
                    closeQuietly(conversation)
                }
            } finally {
                closeQuietly(engine)
            }
        }

        /**
         * Smallest constructor whose leading params match [prefix]
         * (types assignable), remaining slots filled with null. The
         * LiteRT-LM config data classes keep every param past the
         * required prefix nullable, and @JvmOverloads coverage varies
         * by SDK version — so prefer the short overload, else the full
         * ctor with null defaults.
         */
        private fun newWithNullPrefix(clazz: Class<*>, prefix: Array<out Any?>): Any {
            val ctor = clazz.constructors
                .filter { c ->
                    val p = c.parameterTypes
                    p.size >= prefix.size && prefix.indices.all { i ->
                        prefix[i] == null || p[i].isInstance(prefix[i])
                    }
                }
                .minByOrNull { it.parameterTypes.size }
                ?: throw IllegalStateException("local-llm ${clazz.simpleName} ctor missing")
            val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
            prefix.copyInto(args)
            try {
                return ctor.newInstance(*args)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalStateException("local-llm ${clazz.simpleName} create failed", e)
            }
        }

        private fun newEngineConfig(configClass: Class<*>, modelPath: String, backend: Any): Any =
            newWithNullPrefix(configClass, arrayOf(modelPath, backend))

        private fun newBackend(backendClass: Class<*>, gpu: Boolean): Any {
            val nested = backendClass.classes
            if (gpu) {
                val gpuClass = nested.firstOrNull { it.simpleName == "GPU" }
                if (gpuClass != null) {
                    try {
                        return gpuClass.getDeclaredConstructor().newInstance()
                    } catch (_: Throwable) {
                        // Fall through to CPU.
                    }
                }
            }
            val cpuClass = nested.firstOrNull { it.simpleName == "CPU" }
                ?: throw IllegalStateException("local-llm Backend.CPU missing")
            // CPU data-class params are all nullable; arity varies by
            // SDK (@JvmOverloads not guaranteed) — nulls for all.
            return newWithNullPrefix(cpuClass, emptyArray())
        }

        private fun newConversation(engine: Any, engineClass: Class<*>): Any {
            val configClass = Class.forName("$PKG.ConversationConfig")
            val emptyConfig = newWithNullPrefix(configClass, emptyArray())
            val create = engineClass.methods.firstOrNull {
                it.name == "createConversation" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(configClass)
            } ?: throw IllegalStateException("local-llm createConversation missing")
            try {
                return create.invoke(engine, emptyConfig)
                    ?: throw IllegalStateException("local-llm createConversation null")
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalStateException("local-llm createConversation failed", e)
            }
        }

        private fun sendPrompt(conversation: Any, prompt: String, maxTokens: Int): String {
            val full = try {
                val m = conversation.javaClass.methods.firstOrNull {
                    it.name == "sendMessage" && it.parameterTypes.size == 8 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[5] == Integer::class.java
                } ?: throw NoSuchMethodException("8-arg sendMessage")
                val args = arrayOfNulls<Any>(8)
                args[0] = prompt
                // Non-null: reflection bypasses Kotlin defaults, and the
                // SDK dereferences extraContext (nullable configs stay null).
                args[1] = emptyMap<String, Any>()
                args[5] = Integer.valueOf(maxTokens)
                m.invoke(conversation, *args)
            } catch (_: NoSuchMethodException) {
                // Older SDK without the maxOutputToken overload.
                invoke(conversation, "sendMessage", prompt)
            } catch (e: Throwable) {
                throw IllegalStateException("local-llm sendMessage failed", e)
            }
            return (full?.toString() ?: throw IllegalStateException("local-llm empty response"))
        }

        private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
            val m = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: throw NoSuchMethodException("$name/${args.size}")
            try {
                return m.invoke(target, *args)
            } catch (e: Throwable) {
                throw IllegalStateException("local-llm $name failed", e)
            }
        }

        private fun closeQuietly(target: Any) {
            try {
                target.javaClass.getMethod("close").invoke(target)
            } catch (_: Throwable) {
            }
        }
    }
}
