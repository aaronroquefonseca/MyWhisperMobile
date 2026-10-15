package com.mywhisper.mobile

import android.os.StatFs
import java.io.File
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

    /** Remove the model file + any per-asset partials (delete / retry cleanup). */
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
    }

    /**
     * Synchronous single-turn generation via the MediaPipe runner.
     * Loaded by reflection (`com.google.mediapipe.tasks.genai.llminference.LlmInference`)
     * so the tasks-genai AAR is optional at unit-test time.
     * Throws when the model file is missing or the runner is unavailable —
     * callers fall back to the next cleanup entry.
     */
    fun generate(taskFile: File, prompt: String, maxTokens: Int = 512): String {
        require(taskFile.isFile) { "local llm model not ready at ${taskFile.absolutePath}" }
        return MediaPipeRunner.generate(taskFile.absolutePath, prompt, maxTokens)
    }

    /** Reflection bridge to `LlmInference` (GPU delegate, temp 0). */
    internal object MediaPipeRunner {
        fun generate(modelPath: String, prompt: String, maxTokens: Int): String {
            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions",
            )
            val llmClass =
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
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
            val create = llmClass.getMethod("createFromOptions", optionsClass)
            val session = create.invoke(null, options)
            try {
                return llmClass.getMethod("generateResponse", String::class.java)
                    .invoke(session, prompt) as String
            } finally {
                try {
                    llmClass.getMethod("close").invoke(session)
                } catch (_: Exception) {
                }
            }
        }
    }
}
