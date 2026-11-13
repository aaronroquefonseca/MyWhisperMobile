package com.mywhisper.mobile

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * LiteRT-LM runner (Gemma 4 `.litertlm`; MediaPipe tasks-genai is
 * maintenance-only with no Gemma 4 artifact). Full-flavor only:
 * lives in `src/full`, so lite builds and JVM unit tests never see
 * litertlm classes. Discovered from common code by class NAME
 * (stable, ours) — a missing class throws ClassNotFoundException
 * and callers fall back to the next cleanup entry.
 *
 * Synchronous single-turn over the sync `sendMessage` (Gallery
 * streams async; revisited when a chat UI needs it). Keep-warm:
 * one resident engine (memory bound) governed by the caller's
 * policy; conversations stay per-call (fresh state each turn).
 * Backend follows the FILE, not preference: base builds are
 * CPU-only — a GPU attempt on one aborts natively (uncatchable),
 * so GPU runs only for `*-gpu.litertlm` builds (else CPU direct,
 * no wasted double-load).
 * Thinking: optional per-call budget (Gemma 4 supports it;
 * default off). Throws on failure — callers map to FAIL.
 */
object LiteRtLmBridge {
    @Volatile private var engine: Engine? = null
    @Volatile private var engineKey: String? = null
    @Volatile private var lastUsedMs: Long = 0L
    private val lock = Any()

    /**
     * Single-turn cleanup generation. [maxTokens] null = model
     * default. [cacheDirPath] backs the engine cache — pass the
     * models dir (quota-free), not the app cache dir.
     * [thinkingBudget] null/0 = plain mode.
     */
    @JvmStatic
    fun generate(
        modelPath: String,
        system: String,
        transcript: String,
        maxTokens: Int?,
        cacheDirPath: String,
        keepSecs: Long,
        thinkingBudget: Int?,
    ): String {
        require(File(modelPath).isFile) { "local llm model not ready at $modelPath" }
        val now = android.os.SystemClock.elapsedRealtime()
        val eng: Engine = synchronized(lock) {
            if (engine != null && engineKey != modelPath) {
                android.util.Log.i("MwLlm", "keep: model switch, closing resident litertlm engine")
                closeEngineLocked()
            }
            if (engine != null &&
                LocalKeepWarm.isExpired(lastUsedMs, keepSecs, now)
            ) {
                android.util.Log.i(
                    "MwLlm",
                    "keep: idle-expired idle=${(now - lastUsedMs) / 1000}s " +
                        "keep=${keepSecs}s, closing resident litertlm engine",
                )
                closeEngineLocked()
            }
            val cached = engine?.takeIf { engineKey == modelPath }
            if (cached != null) {
                android.util.Log.i("MwLlm", "keep: reusing resident litertlm engine")
                cached
            } else {
                val created = loadEngine(modelPath, maxTokens, cacheDirPath)
                engine = created
                engineKey = modelPath
                android.util.Log.i("MwLlm", "keep: litertlm engine loaded for $modelPath")
                created
            }
        }
        try {
            return runOnce(eng, system, transcript, thinkingBudget)
        } finally {
            synchronized(lock) {
                if (keepSecs == LocalKeepWarm.IMMEDIATE) {
                    android.util.Log.i("MwLlm", "keep: immediate policy, closing litertlm engine")
                    closeEngineLocked()
                } else if (engineKey == modelPath) {
                    lastUsedMs = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }

    /** Close the resident engine (service destroy). Never throws. */
    @JvmStatic
    fun releaseAll() {
        synchronized(lock) {
            closeEngineLocked()
        }
    }

    /** Drop the resident engine when it backs [modelPath]. Never throws. */
    @JvmStatic
    fun dropCached(modelPath: String) {
        synchronized(lock) {
            if (engineKey == modelPath) {
                android.util.Log.i("MwLlm", "keep: dropping cached litertlm engine for $modelPath")
                closeEngineLocked()
            }
        }
    }

    private fun closeEngineLocked() {
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        engineKey = null
        lastUsedMs = 0L
    }

    private fun loadEngine(
        modelPath: String,
        maxTokens: Int?,
        cacheDirPath: String,
    ): Engine {
        // Backend follows the FILE: base builds are CPU-only (a GPU
        // attempt aborts natively — uncatchable — owner repro on
        // Mali); `*-gpu.litertlm` builds try GPU, then CPU.
        val makers: List<() -> Backend> =
            if (LocalLlmManager.preferGpuFor(modelPath)) {
                listOf({ Backend.GPU() }, { Backend.CPU() })
            } else {
                android.util.Log.i("MwLlm", "litertlm base build: CPU direct (no GPU attempt)")
                listOf({ Backend.CPU() })
            }
        var lastError: Exception? = null
        for (makeBackend in makers) {
            try {
                val engine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = makeBackend(),
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDirPath,
                    ),
                )
                engine.initialize()
                android.util.Log.i("MwLlm", "litertlm engine initialized")
                return engine
            } catch (e: Exception) {
                android.util.Log.w("MwLlm", "litertlm backend init failed, trying next: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("litertlm init failed for $modelPath")
    }

    private fun runOnce(
        engine: Engine,
        system: String,
        transcript: String,
        thinkingBudget: Int?,
    ): String {
        val thinking = if (thinkingBudget != null && thinkingBudget > 0) {
            ThinkingConfig(true, thinkingBudget)
        } else {
            null
        }
        val convo = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(64, 0.95, 1.0, 0),
                thinkingConfig = thinking,
            ),
        )
        android.util.Log.i("MwLlm", "litertlm conversation created")
        try {
            val reply = convo.sendMessage(Contents.of(transcript))
            android.util.Log.i("MwLlm", "litertlm sendMessage returned")
            return reply.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } finally {
            try {
                convo.close()
            } catch (_: Exception) {
            }
        }
    }
}

