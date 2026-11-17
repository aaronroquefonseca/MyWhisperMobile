package com.mywhisper.mobile

/**
 * JNI surface of `libmwlocalai.so` (android/localai/jni.cpp). The library
 * lives in the downloaded runtime pack, never in the APK: only call these
 * after [LocalAiRuntime.load] returned true. Failures throw
 * RuntimeException with the native message.
 */
object LocalAiNative {
    const val STT_WHISPER = 0
    const val STT_PARAKEET = 1

    external fun init(backendDir: String): Int
    external fun devicesJson(): String

    external fun llmLoad(modelPath: String, device: String, nCtx: Int, threads: Int): Long
    external fun llmGenerate(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Int,
    ): String
    external fun llmFree(handle: Long)

    external fun sttLoad(modelPath: String, kind: Int, device: String, threads: Int): Long
    external fun sttTranscribe(handle: Long, pcm: FloatArray, language: String, prompt: String): String
    external fun sttFree(handle: Long)

    /** This thread's last call: [promptTokens, cachedTokens, genTokens, prefillMs, genMs]. */
    external fun lastStats(): DoubleArray
}
