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
 * Chosen provider: MediaPipe LLM Inference `.task`
 * (`com.google.mediapipe:tasks-genai`, GPU delegate; successor API:
 * LiteRT-LM). Inference itself is loaded lazily via reflection so
 * plain JVM unit tests never touch the native runner; all logic
 * here (manifest, SHA-256, status, prompt packing) is pure JVM.
 *
 * Model files live under `<filesDir>/models` (file `cleanup.task`), downloaded by
 * [downloadModel] with SHA-256 verification against
 * `scripts/local-llm-models.json`. Binaries are NEVER committed.
 * When no model is ready the engine's cleanup chain falls through
 * to Ollama/cloud (default order unchanged).
 */
object LocalLlmManager {
    const val PROVIDER = "mediapipe"
    const val DEFAULT_TASK_NAME = "cleanup.task"

    /** On-device runner backends (manifest `runtime`, one per entry). */
    const val RUNTIME_MEDIAPIPE = "mediapipe"
    const val RUNTIME_LITERTLM = "litertlm"

    data class ModelAsset(
        val id: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        /** Minimum device RAM (MB) for the selector warning; 0 = unknown. */
        val minRamMb: Long = 0,
        /** Runner backend id ([RUNTIME_MEDIAPIPE]/[RUNTIME_LITERTLM]). */
        val runtime: String = RUNTIME_MEDIAPIPE,
    )

    data class Status(
        val provider: String = PROVIDER,
        val modelPath: String,
        val ready: Boolean,
        val sizeBytes: Long?,
    )

    /** Default model slot: `<dir>/models/cleanup.task`. */
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
            fun optStrField(name: String, default: String): String =
                Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(entry)?.groupValues?.get(1) ?: default
            ModelAsset(
                id = strField("id"),
                url = strField("url"),
                sha256 = strField("sha256"),
                sizeBytes = longField("size_bytes"),
                minRamMb = optLongField("min_ram_mb"),
                runtime = optStrField("runtime", RUNTIME_MEDIAPIPE),
            )
        }
    }

    /** Model-file extension for a runner backend (`task` vs `litertlm`). Pure. */
    @JvmStatic
    fun extForRuntime(runtime: String): String =
        if (runtime == RUNTIME_LITERTLM) "litertlm" else "task"

    /**
     * True when [modelPath] is a GPU-targeted LiteRT-LM build
     * (`*-gpu.litertlm`). Backend and file must match: base builds
     * are CPU-only, and a GPU attempt on one does not fail closed —
     * it aborts the process natively (owner repro: Mali GPU +
     * base E2B file dies in nativeCreateConversation, while the
     * same file on CPU generates fine). Pure filename rule.
     */
    @JvmStatic
    fun preferGpuFor(modelPath: String): Boolean {
        val name = modelPath.substringAfterLast('/').substringAfterLast('\\')
        return name.contains("-gpu.", ignoreCase = true)
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

    /**
     * XNNPack weight-cache redirect: MediaPipe derives a
     * `<task>.xnnpack_cache` file in the app cache dir and aborts the
     * process when Android's per-app cache quota (~100MB here) trips
     * mid-write — every GB-scale model crashes on first load
     * otherwise (owner repros, phone + emulator).
     * Point it at the quota-free models dir via symlink before each
     * run (idempotent; replaces a real file, which could only ever
     * grow into the same crash). Pure path math is unit-tested; the
     * symlink step itself never throws (false = old behavior).
     */
    fun xnnpackCacheName(taskFileName: String): String = "$taskFileName.xnnpack_cache"

    @JvmStatic
    fun ensureWeightCacheLink(cacheDir: File, modelsDir: File, taskName: String): Boolean {
        return try {
            val link = File(cacheDir, xnnpackCacheName(taskName))
            val target = File(modelsDir, xnnpackCacheName(taskName))
            val linkPath = link.toPath()
            if (java.nio.file.Files.isSymbolicLink(linkPath)) return true
            try {
                link.delete()
            } catch (_: Exception) {
            }
            target.parentFile?.mkdirs()
            java.nio.file.Files.createSymbolicLink(linkPath, target.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

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

    /**
     * Synchronous single-turn generation via the MediaPipe runner.
     * Loaded by reflection (`com.google.mediapipe.tasks.genai.llminference.LlmInference`)
     * so the tasks-genai AAR is optional at unit-test time.
     * Throws when the model file is missing or the runner is unavailable —
     * callers fall back to the next cleanup entry. The Android Context
     * is mandatory (`createFromOptions` requires one); a null context
     * fails closed here instead of crashing in reflection.
     */
    fun generate(
        taskFile: File,
        prompt: String,
        // Sample used 1024, but our prompt alone is ~752 tokens and
        // grows with the transcript (system ~700 + dictation), so
        // 1024 fails real dictations. 2048 = the files' baked context
        // (int4 card: ctx 2048; mpseq: ekv2048) — the highest value
        // the conversions physically support. Longer inputs fail
        // closed (OUT_OF_RANGE) and the chain falls through.
        maxTokens: Int = 2048,
        context: android.content.Context? = null,
        // Keep-warm asset id (null = immediate, today's behavior).
        assetId: String? = null,
    ): String {
        require(taskFile.isFile) { "local llm model not ready at ${taskFile.absolutePath}" }
        if (context != null) {
            try {
                val ok = ensureWeightCacheLink(context.cacheDir, taskFile.parentFile ?: taskFile, taskFile.name)
                android.util.Log.i("MwLlm", "weight-cache link ok=$ok file=${taskFile.name}")
            } catch (_: Exception) {
            }
        }
        return MediaPipeRunner.generate(taskFile.absolutePath, prompt, maxTokens, context, assetId)
    }

    /** Close every resident LLM engine (service destroy). Never throws. */
    @JvmStatic
    fun releaseLocalEngines() {
        try {
            MediaPipeRunner.releaseAll()
        } catch (_: Exception) {
        }
        try {
            val bridge = Class.forName("com.mywhisper.mobile.LiteRtLmBridge")
            bridge.getMethod("releaseAll").invoke(null)
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
    }

    /** Drop the resident engine backing [taskFile] (delete/policy-immediate). */
    @JvmStatic
    fun dropCachedLlm(taskFile: File) {
        try {
            MediaPipeRunner.dropCached(taskFile.absolutePath)
        } catch (_: Exception) {
        }
        try {
            val bridge = Class.forName("com.mywhisper.mobile.LiteRtLmBridge")
            bridge.getMethod("dropCached", String::class.java)
                .invoke(null, taskFile.absolutePath)
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
    }

    /**
     * LiteRT-LM generation (Gemma 4 `.litertlm`) via the full-flavor
     * `LiteRtLmBridge`, discovered by class NAME (stable, ours) so
     * lite builds and JVM unit tests (no litertlm classes) fail
     * closed with ClassNotFoundException and callers fall back.
     * Split system/transcript (the API models them separately);
     * [maxTokens] null = model default. The engine cache lives in
     * the models dir (quota-free), never the app cache dir.
     * Throws on failure — callers map to FAIL, never crash.
     */
    fun generateLitertlm(
        taskFile: File,
        system: String,
        transcript: String,
        maxTokens: Int? = null,
        cacheDir: File? = null,
        keepSecs: Long = LocalKeepWarm.IMMEDIATE,
        thinkBudget: Int? = null,
    ): String {
        require(taskFile.isFile) { "local llm model not ready at ${taskFile.absolutePath}" }
        val bridge = Class.forName("com.mywhisper.mobile.LiteRtLmBridge")
        val dir = (cacheDir ?: taskFile.parentFile)?.absolutePath
            ?: throw IllegalStateException("local llm needs a cache dir")
        val boxed = maxTokens?.let { Integer.valueOf(it) }
        val thinkBoxed = thinkBudget?.let { Integer.valueOf(it) }
        return bridge.getMethod(
            "generate",
            String::class.java,
            String::class.java,
            String::class.java,
            Integer::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
            Integer::class.java,
        ).invoke(null, taskFile.absolutePath, system, transcript, boxed, dir, keepSecs, thinkBoxed) as String
    }

    /**
     * Reflection bridge to `LlmInference` (GPU default,
     * vendor-recipe sampling). Verified truths, learned the hard
     * way: the top-level `LlmInferenceOptions` class has NEVER
     * existed (checked via javap on every runtime) — the only
     * options class is NESTED (`LlmInference$...Options`) and
     * `createFromOptions` takes `(Context, options)`. An earlier
     * top-level-lookup bridge could never have worked; its
     * "passes" were the vacuous sine probe. Recipe parity with
     * the (deleted) official Android sample: tasks-genai 0.10.29,
     * multi-prefill-seq model file, maxTokens=1024, explicit
     * session sampler, ASYNC generate — the sync path segfaults
     * in nativePredictSync past ~32 prompt tokens on every
     * runtime tried (0.10.27/0.10.32/0.10.35), while async fails
     * closed. An earlier CPU-force + tokenizer-sizing diagnostic
     * was fully reverted (nothing of it remains).
     */
    internal object MediaPipeRunner {
        @Volatile private var engine: Any? = null
        @Volatile private var engineKey: String? = null
        @Volatile private var lastUsedMs: Long = 0L
        private val lock = Any()

        fun generate(
            modelPath: String,
            prompt: String,
            maxTokens: Int,
            context: android.content.Context?,
            assetId: String? = null,
        ): String {
            val ctx = context
                ?: throw IllegalStateException("local llm needs an Android context")
            val prefs = try {
                ctx.getSharedPreferences("mw_state", android.content.Context.MODE_PRIVATE)
            } catch (_: Exception) {
                null
            }
            // Keep-warm: one resident engine (memory bound) governed
            // by this asset's policy (default immediate = today's
            // load-per-call behavior when the id is unknown).
            val keepSecs = assetId?.let { id ->
                prefs?.let {
                    LocalKeepWarm.readKeepSecs(it, LocalKeepWarm.KIND_LLM, id, LocalKeepWarm.IMMEDIATE)
                }
            } ?: LocalKeepWarm.IMMEDIATE
            val now = LocalKeepWarm.nowMs()
            val eng: Any = synchronized(lock) {
                if (engine != null && engineKey != modelPath) {
                    android.util.Log.i("MwLlm", "keep: model switch, closing resident engine")
                    closeEngineLocked()
                }
                if (engine != null &&
                    LocalKeepWarm.isExpired(lastUsedMs, keepSecs, now)
                ) {
                    android.util.Log.i(
                        "MwLlm",
                        "keep: idle-expired idle=${(now - lastUsedMs) / 1000}s " +
                            "keep=${keepSecs}s, closing resident engine",
                    )
                    closeEngineLocked()
                }
                val cached = engine?.takeIf { engineKey == modelPath }
                if (cached != null) {
                    android.util.Log.i("MwLlm", "keep: reusing resident engine")
                    cached
                } else {
                    val created = createEngine(ctx, modelPath, maxTokens)
                    engine = created
                    engineKey = modelPath
                    android.util.Log.i("MwLlm", "keep: engine loaded for $modelPath")
                    created
                }
            }
            try {
                return runSession(eng, prompt)
            } finally {
                synchronized(lock) {
                    if (keepSecs == LocalKeepWarm.IMMEDIATE) {
                        android.util.Log.i("MwLlm", "keep: immediate policy, closing engine")
                        closeEngineLocked()
                    } else if (engineKey == modelPath) {
                        lastUsedMs = LocalKeepWarm.nowMs()
                    }
                }
            }
        }

        /** Close the resident engine (service destroy / policy drop). */
        fun releaseAll() {
            synchronized(lock) {
                closeEngineLocked()
            }
        }

        /** Drop the resident engine when it backs [modelPath] (delete/policy). */
        fun dropCached(modelPath: String) {
            synchronized(lock) {
                if (engineKey == modelPath) {
                    android.util.Log.i("MwLlm", "keep: dropping cached engine for $modelPath")
                    closeEngineLocked()
                }
            }
        }

        private fun closeEngineLocked() {
            try {
                val llmClass =
                    Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                engine?.let { llmClass.getMethod("close").invoke(it) }
            } catch (_: Exception) {
            }
            engine = null
            engineKey = null
            lastUsedMs = 0L
        }

        private fun createEngine(
            ctx: android.content.Context,
            modelPath: String,
            maxTokens: Int,
        ): Any {
            val llmClass =
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions",
            )
            val builder = optionsClass.getMethod("builder").invoke(null)
            val builderClass = builder.javaClass
            builderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelPath)
            try {
                builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                    .invoke(builder, maxTokens)
            } catch (_: NoSuchMethodException) {
                // Older tasks-genai: no setMaxTokens — defaults apply.
            }
            val options = builderClass.getMethod("build").invoke(builder)
            val create = llmClass.getMethod(
                "createFromOptions",
                android.content.Context::class.java,
                optionsClass,
            )
            return create.invoke(null, ctx, options)
        }

        /**
         * One fresh explicit session per call (never the implicit
         * generateResponse path: it segfaults in nativePredictSync
         * with a null handle on these runtimes). Sessions accumulate
         * conversation state, so reuse is incorrect — only the
         * ENGINE is resident; sessions open and close per call.
         */
        private fun runSession(session: Any, prompt: String): String {
            val llmClass =
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            try {
                val sized = llmClass.getMethod("sizeInTokens", String::class.java)
                    .invoke(session, prompt) as? Int
                android.util.Log.i("MwLlm", "prompt tokens=$sized len=${prompt.length}")
            } catch (_: Exception) {
                android.util.Log.w("MwLlm", "sizeInTokens failed")
            }
            // Sample parity (deleted official Android sample,
            // verified via git history): explicit session sampler
            // topK=64 / temp=1.0 / topP=0.95. Runtime defaults
            // (40/0.8/1.0) are left alone — the vendor recipe is
            // the tested configuration.
            val sessOptsClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession\$LlmInferenceSessionOptions",
            )
            val sessBuilder = sessOptsClass.getMethod("builder").invoke(null)
            val sessBuilderClass = sessBuilder.javaClass
            sessBuilderClass.getMethod("setTopK", Int::class.javaPrimitiveType)
                .invoke(sessBuilder, 64)
            sessBuilderClass.getMethod("setTemperature", Float::class.javaPrimitiveType)
                .invoke(sessBuilder, 1.0f)
            sessBuilderClass.getMethod("setTopP", Float::class.javaPrimitiveType)
                .invoke(sessBuilder, 0.95f)
            val sessOpts = sessBuilderClass.getMethod("build").invoke(sessBuilder)
            val sessionClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession",
            )
            val createSession = sessionClass.getMethod(
                "createFromOptions",
                llmClass,
                sessOptsClass,
            )
            val convo = createSession.invoke(null, session, sessOpts)
            try {
                sessionClass.getMethod("addQueryChunk", String::class.java)
                    .invoke(convo, prompt)
                // Async generate (sample parity — the sample never
                // calls sync generateResponse): the sync path
                // segfaults in nativePredictSync on long prompts
                // (all runtimes tried); the async path fails closed
                // with OUT_OF_RANGE instead. Block for the result;
                // timeout/cancel surfaces as FAIL, never a crash.
                @Suppress("UNCHECKED_CAST")
                val future = sessionClass.getMethod("generateResponseAsync")
                    .invoke(convo) as java.util.concurrent.Future<String>
                return try {
                    future.get(8, java.util.concurrent.TimeUnit.MINUTES)
                } catch (e: Exception) {
                    try {
                        future.cancel(true)
                    } catch (_: Exception) {
                    }
                    throw e
                }
            } finally {
                try {
                    sessionClass.getMethod("close").invoke(convo)
                } catch (_: Exception) {
                }
            }
        }
    }
}
