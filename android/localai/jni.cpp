// JNI surface for com.mywhisper.mobile.LocalAiNative (Kotlin, in the APK).
// Errors surface as RuntimeException with the native message.
#include "mwlocalai.h"

#include <android/log.h>
#include <jni.h>

#include <string>

namespace {

// Timing of this thread's last llm/stt call, for device selection.
thread_local mw_stats g_last = {};

void to_logcat(int level, const char * text) {
    int prio = level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_write(prio, "MwLocalAi", text);
}

std::string str(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c;
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void throw_err(JNIEnv * env, char * err, const char * fallback) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), err ? err : fallback);
    mw_free_str(err);
}

jstring take(JNIEnv * env, char * s) {
    jstring out = env->NewStringUTF(s);
    mw_free_str(s);
    return out;
}

} // namespace

#define FN(name) Java_com_mywhisper_mobile_LocalAiNative_##name

extern "C" {

JNIEXPORT jint JNICALL FN(init)(JNIEnv * env, jobject, jstring dir) {
    mw_set_log(to_logcat);
    return mw_init(str(env, dir).c_str());
}

JNIEXPORT jstring JNICALL FN(devicesJson)(JNIEnv * env, jobject) {
    return take(env, mw_devices_json());
}

JNIEXPORT jlong JNICALL FN(llmLoad)(JNIEnv * env, jobject, jstring path, jstring device, jint n_ctx, jint threads) {
    char * err = nullptr;
    void * h = mw_llm_load(str(env, path).c_str(), str(env, device).c_str(), n_ctx, threads, &err);
    if (!h) throw_err(env, err, "llm load failed");
    return (jlong) h;
}

JNIEXPORT jstring JNICALL FN(llmGenerate)(JNIEnv * env, jobject, jlong h, jstring system, jstring user,
                                          jint max_tokens, jfloat temperature, jint timeout_ms) {
    char * err = nullptr;
    mw_stats st;
    char * out = mw_llm_generate((void *) h, str(env, system).c_str(), str(env, user).c_str(), max_tokens,
                                 temperature, timeout_ms, &st, &err);
    g_last = out ? st : mw_stats{};
    if (!out) {
        throw_err(env, err, "llm generate failed");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "MwLocalAi",
                        "llm prompt=%d cached=%d prefill=%.0fms gen=%d in %.0fms", st.prompt_tokens,
                        st.cached_tokens, st.prefill_ms, st.gen_tokens, st.gen_ms);
    return take(env, out);
}

JNIEXPORT void JNICALL FN(llmFree)(JNIEnv *, jobject, jlong h) { mw_llm_free((void *) h); }

JNIEXPORT jlong JNICALL FN(sttLoad)(JNIEnv * env, jobject, jstring path, jint kind, jstring device, jint threads) {
    char * err = nullptr;
    void * h = mw_stt_load(str(env, path).c_str(), kind, str(env, device).c_str(), threads, &err);
    if (!h) throw_err(env, err, "stt load failed");
    return (jlong) h;
}

JNIEXPORT jstring JNICALL FN(sttTranscribe)(JNIEnv * env, jobject, jlong h, jfloatArray pcm, jstring language,
                                            jstring prompt) {
    char * err = nullptr;
    mw_stats st;
    jsize n = env->GetArrayLength(pcm);
    jfloat * data = env->GetFloatArrayElements(pcm, nullptr);
    std::string lang = str(env, language), pr = str(env, prompt);
    char * out = mw_stt_transcribe((void *) h, data, n, lang.c_str(), pr.c_str(), &st, &err);
    g_last = out ? st : mw_stats{};
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    if (!out) {
        throw_err(env, err, "stt failed");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "MwLocalAi", "stt %.1fs audio in %.0fms", n / 16000.0, st.gen_ms);
    return take(env, out);
}

// [prompt_tokens, cached_tokens, gen_tokens, prefill_ms, gen_ms] of this thread's last call.
JNIEXPORT jdoubleArray JNICALL FN(lastStats)(JNIEnv * env, jobject) {
    const jdouble v[5] = {(double) g_last.prompt_tokens, (double) g_last.cached_tokens, (double) g_last.gen_tokens,
                          g_last.prefill_ms, g_last.gen_ms};
    jdoubleArray out = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(out, 0, 5, v);
    return out;
}

JNIEXPORT void JNICALL FN(sttFree)(JNIEnv *, jobject, jlong h) { mw_stt_free((void *) h); }

} // extern "C"
