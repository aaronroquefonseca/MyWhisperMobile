package com.mywhisper.mobile

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
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
 * streams async; revisited when a chat UI needs it). GPU first,
 * CPU fallback (emulator GL is software — init fails there;
 * phones run GPU).
 */
object LiteRtLmBridge {
    /**
     * Single-turn cleanup generation. [maxTokens] null = model
     * default (Gemma 4 files' baked context wins; the MediaPipe-side
     * cap does not apply here). [cacheDirPath] backs the engine
     * cache — pass the models dir (quota-free), not the app cache
     * dir. Throws on failure — callers map to FAIL, never crash.
     */
    @JvmStatic
    fun generate(
        modelPath: String,
        system: String,
        transcript: String,
        maxTokens: Int?,
        cacheDirPath: String,
    ): String {
        require(File(modelPath).isFile) { "local llm model not ready at $modelPath" }
        try {
            return generateOnce(modelPath, system, transcript, maxTokens, cacheDirPath, Backend.GPU())
        } catch (e: Exception) {
            android.util.Log.w("MwLlm", "litertlm GPU path failed, trying CPU: ${e.message}")
        }
        return generateOnce(modelPath, system, transcript, maxTokens, cacheDirPath, Backend.CPU())
    }

    private fun generateOnce(
        modelPath: String,
        system: String,
        transcript: String,
        maxTokens: Int?,
        cacheDirPath: String,
        backend: Backend,
    ): String {
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxTokens,
                cacheDir = cacheDirPath,
            ),
        )
        try {
            engine.initialize()
            val convo = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(64, 0.95, 1.0, 0),
                ),
            )
            try {
                val reply = convo.sendMessage(Contents.of(transcript))
                return reply.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            } finally {
                try {
                    convo.close()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                engine.close()
            } catch (_: Exception) {
            }
        }
    }
}
