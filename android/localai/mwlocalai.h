// MyWhisper on-device AI runtime: one C API over llama.cpp (cleanup LLM)
// and whisper.cpp (Whisper + Parakeet STT). Built into libmwlocalai.so,
// which ships in the downloadable runtime pack, never in the APK.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*mw_log_fn)(int level, const char * text);

// Route llama/whisper/ggml logs. NULL keeps stderr.
void mw_set_log(mw_log_fn fn);

// Load every ggml backend (CPU variants, Vulkan, OpenCL) found in dir.
// Idempotent. Returns the number of devices now registered.
int mw_init(const char * backend_dir);

// JSON array: [{"name","desc","type":"cpu|gpu|igpu|accel","backend","free","total"}].
// Caller frees with mw_free_str.
char * mw_devices_json(void);

typedef struct mw_stats {
    int32_t prompt_tokens;
    int32_t cached_tokens; // prompt tokens reused from the previous call
    int32_t gen_tokens;
    double  load_ms;
    double  prefill_ms;
    double  gen_ms;
} mw_stats;

// ---- LLM -----------------------------------------------------------------
// device: ggml device name ("Vulkan0", "GPUOpenCL", ...) or "CPU".
// On failure returns NULL and sets *err (free with mw_free_str).
void * mw_llm_load(const char * model_path, const char * device, int n_ctx, int n_threads, char ** err);

// Chat-formats {system, user} with the model's own template and generates.
// Greedy when temperature <= 0. Stops at EOG, max_tokens or timeout_ms.
char * mw_llm_generate(void * llm, const char * system, const char * user, int max_tokens,
                       float temperature, int timeout_ms, mw_stats * stats, char ** err);
void mw_llm_free(void * llm);

// ---- STT -----------------------------------------------------------------
enum { MW_STT_WHISPER = 0, MW_STT_PARAKEET = 1 };

void * mw_stt_load(const char * model_path, int kind, const char * device, int n_threads, char ** err);

// pcm: mono float32 at 16 kHz. language: ISO code, "" or "auto" to detect
// (whisper only). prompt: whisper initial prompt (dictionary words), may be NULL.
char * mw_stt_transcribe(void * stt, const float * pcm, int n_samples, const char * language,
                         const char * prompt, mw_stats * stats, char ** err);
void mw_stt_free(void * stt);

void mw_free_str(char * s);

#ifdef __cplusplus
}
#endif
