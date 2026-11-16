#include "mwlocalai.h"

#include "ggml-backend.h"
#include "llama.h"
#include "parakeet.h"
#include "whisper.h"

#include <algorithm>
#include <chrono>
#include <functional>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

mw_log_fn g_log = nullptr;
std::mutex g_init_mutex;
bool g_backends_loaded = false;

void log_cb(ggml_log_level level, const char * text, void *) {
    if (g_log) {
        g_log((int) level, text);
    } else if (level >= GGML_LOG_LEVEL_WARN) {
        fputs(text, stderr);
    }
}

char * dup_str(const std::string & s) {
    char * out = (char *) malloc(s.size() + 1);
    memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

void set_err(char ** err, const std::string & msg) {
    if (err) *err = dup_str(msg);
}

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

std::string json_escape(const char * s) {
    std::string out;
    for (; s && *s; ++s) {
        switch (*s) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            default:
                if ((unsigned char) *s < 0x20) break;
                out += *s;
        }
    }
    return out;
}

const char * type_name(enum ggml_backend_dev_type t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: return "cpu";
        case GGML_BACKEND_DEVICE_TYPE_GPU: return "gpu";
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return "igpu";
        default: return "accel";
    }
}

bool is_cpu(const char * device) {
    return device == nullptr || *device == '\0' || strcmp(device, "CPU") == 0;
}

// whisper/parakeet pick GPUs by index among GPU+IGPU devices.
int gpu_index_of(ggml_backend_dev_t dev) {
    int idx = 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        auto t = ggml_backend_dev_type(d);
        if (t != GGML_BACKEND_DEVICE_TYPE_GPU && t != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        if (d == dev) return idx;
        ++idx;
    }
    return -1;
}

struct Llm {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string tmpl;
    bool gemma4 = false;
    std::vector<llama_token> cached; // tokens currently in the KV cache (seq 0)
    bool no_lookup = false;          // KV tail removal unsupported: plain decoding only
};

// Greedy decoding with prompt-lookup drafts: cleanup output mostly copies
// the transcript, so after each token we look up the last few tokens in
// the prompt and propose what followed there. One batched decode verifies
// all drafts; output is identical to plain greedy decoding, just fewer
// sequential steps. Returns false on a decode failure.
bool generate_lookup(Llm * llm, llama_sampler * smpl, const std::vector<llama_token> & prompt, int max_tokens,
                     int timeout_ms, std::chrono::steady_clock::time_point t0,
                     const std::function<void(llama_token)> & emit) {
    // Tuned on Exynos 990 / Gemma 4 E2B: 7.5 -> 13 tok/s (10/3 gave 12,
    // 16/3 gave 10: long drafts cost more than they save on CPU).
    const int kDraft = 6, kNgram = 2;
    llama_memory_t mem = llama_get_memory(llm->ctx);
    llama_batch batch = llama_batch_init(kDraft + 1, 0, 1);
    llama_token tok = llama_sampler_sample(smpl, llm->ctx, -1);
    int produced = 0;
    bool ok = true;
    while (produced < max_tokens && !llama_vocab_is_eog(llm->vocab, tok)) {
        emit(tok);
        ++produced;
        if (timeout_ms > 0 && ms_since(t0) > timeout_ms) break;
        // Draft: the tokens that followed the latest prompt occurrence of the
        // last kNgram tokens (already decoded ones + tok).
        std::vector<llama_token> draft;
        const auto & h = llm->cached;
        if ((int) h.size() >= kNgram - 1) {
            llama_token key[kNgram];
            for (int k = 0; k < kNgram - 1; ++k) key[k] = h[h.size() - (kNgram - 1) + k];
            key[kNgram - 1] = tok;
            for (int i = (int) prompt.size() - kNgram; i >= 0 && draft.empty(); --i) {
                if (std::equal(key, key + kNgram, prompt.begin() + i)) {
                    for (int j = i + kNgram; j < (int) prompt.size() && (int) draft.size() < kDraft; ++j) {
                        draft.push_back(prompt[j]);
                    }
                }
            }
        }
        const int base = (int) llm->cached.size();
        batch.n_tokens = 0;
        auto add = [&](llama_token t, int pos) {
            const int n = batch.n_tokens++;
            batch.token[n] = t;
            batch.pos[n] = pos;
            batch.n_seq_id[n] = 1;
            batch.seq_id[n][0] = 0;
            batch.logits[n] = 1;
        };
        add(tok, base);
        for (size_t j = 0; j < draft.size(); ++j) add(draft[j], base + 1 + (int) j);
        if (llama_decode(llm->ctx, batch) != 0) {
            ok = false;
            break;
        }
        llm->cached.push_back(tok);
        // Verify drafts greedily: keep each one the model would also pick.
        size_t accepted = 0;
        llama_token next = llama_sampler_sample(smpl, llm->ctx, 0);
        while (accepted < draft.size() && next == draft[accepted] && produced < max_tokens &&
               !llama_vocab_is_eog(llm->vocab, next)) {
            emit(next);
            ++produced;
            llm->cached.push_back(next);
            ++accepted;
            next = llama_sampler_sample(smpl, llm->ctx, (int) accepted);
        }
        if (accepted < draft.size() &&
            !llama_memory_seq_rm(mem, 0, (llama_pos) llm->cached.size(), -1)) {
            // Cache cannot drop the rejected tail: rebuild it and stop drafting.
            llm->no_lookup = true;
            llama_memory_clear(mem, true);
            std::vector<llama_token> keep = llm->cached;
            llm->cached.clear();
            for (size_t i = 0; i < keep.size() && ok; i += 512) {
                int len = (int) std::min<size_t>(512, keep.size() - i);
                ok = llama_decode(llm->ctx, llama_batch_get_one(keep.data() + i, len)) == 0;
            }
            if (ok) llm->cached = keep;
            next = ok ? llama_sampler_sample(smpl, llm->ctx, -1) : next;
        }
        tok = next;
        if (!ok) break;
    }
    llama_batch_free(batch);
    return ok;
}

std::string format_prompt(const Llm & llm, const char * system, const char * user) {
    const std::string sys = system ? system : "";
    const std::string usr = user ? user : "";
    if (llm.gemma4) {
        // Gemma 4 turn markers; llama_chat_apply_template has no built-in for them.
        std::string p;
        if (!sys.empty()) p += "<|turn>system\n" + sys + "<turn|>\n";
        p += "<|turn>user\n" + usr + "<turn|>\n<|turn>model\n";
        return p;
    }
    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", usr.c_str()});
    const char * tmpl = llm.tmpl.empty() ? nullptr : llm.tmpl.c_str();
    std::vector<char> buf(2 * (sys.size() + usr.size()) + 512);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (n < 0) return sys.empty() ? usr : sys + "\n\n" + usr; // unknown template: plain text
    return std::string(buf.data(), n);
}

// Drop reasoning blocks (Gemma 4 "<|channel>thought ... <channel|>", Qwen
// "<think> ... </think>") and any leftover control markers; tokens are
// rendered with special=true so these are visible here.
std::string strip_reasoning(std::string s) {
    const std::pair<const char *, const char *> blocks[] = {{"<|channel>", "<channel|>"}, {"<think>", "</think>"}};
    for (auto & b : blocks) {
        size_t open;
        while ((open = s.find(b.first)) != std::string::npos) {
            size_t close = s.find(b.second, open);
            s.erase(open, close == std::string::npos ? std::string::npos : close + strlen(b.second) - open);
        }
    }
    const char * markers[] = {"<turn|>", "<|turn>", "<end_of_turn>", "<start_of_turn>", "<eos>", "<bos>"};
    for (const char * m : markers) {
        size_t at;
        while ((at = s.find(m)) != std::string::npos) s.erase(at, strlen(m));
    }
    size_t b0 = s.find_first_not_of(" \n\t"), b1 = s.find_last_not_of(" \n\t");
    return b0 == std::string::npos ? std::string() : s.substr(b0, b1 - b0 + 1);
}

struct Stt {
    int kind = MW_STT_WHISPER;
    whisper_context * wctx = nullptr;
    parakeet_context * pctx = nullptr;
    int n_threads = 4;
};

} // namespace

extern "C" {

void mw_set_log(mw_log_fn fn) {
    g_log = fn;
    llama_log_set(log_cb, nullptr);
    whisper_log_set(log_cb, nullptr);
    parakeet_log_set(log_cb, nullptr);
    ggml_log_set(log_cb, nullptr);
}

int mw_init(const char * backend_dir) {
    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (!g_backends_loaded) {
        ggml_backend_load_all_from_path(backend_dir);
        llama_backend_init();
        g_backends_loaded = true;
    }
    return (int) ggml_backend_dev_count();
}

char * mw_devices_json(void) {
    std::string out = "[";
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        char buf[96];
        snprintf(buf, sizeof buf, "\"free\":%zu,\"total\":%zu}", free, total);
        if (i) out += ",";
        out += "{\"name\":\"" + json_escape(ggml_backend_dev_name(d)) + "\",\"desc\":\"" +
               json_escape(ggml_backend_dev_description(d)) + "\",\"type\":\"" +
               type_name(ggml_backend_dev_type(d)) + "\",\"backend\":\"" +
               json_escape(ggml_backend_reg_name(ggml_backend_dev_backend_reg(d))) + "\"," + buf;
    }
    out += "]";
    return dup_str(out);
}

void * mw_llm_load(const char * model_path, const char * device, int n_ctx, int n_threads, char ** err) {
    auto t0 = std::chrono::steady_clock::now();
    ggml_backend_dev_t devs[2] = {nullptr, nullptr};
    llama_model_params mp = llama_model_default_params();
    if (is_cpu(device)) {
        mp.devices = devs; // empty list: CPU only
        mp.n_gpu_layers = 0;
    } else {
        devs[0] = ggml_backend_dev_by_name(device);
        if (!devs[0]) {
            set_err(err, std::string("unknown device ") + device);
            return nullptr;
        }
        mp.devices = devs;
        mp.n_gpu_layers = 999;
    }
    llama_model * model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        set_err(err, "model load failed");
        return nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        set_err(err, "context init failed");
        return nullptr;
    }
    auto * llm = new Llm();
    llm->model = model;
    llm->ctx = ctx;
    llm->vocab = llama_model_get_vocab(model);
    const char * t = llama_model_chat_template(model, nullptr);
    llm->tmpl = t ? t : "";
    llm->gemma4 = llm->tmpl.find("<|turn>") != std::string::npos;
    llm->no_lookup = getenv("MW_NO_LOOKUP") != nullptr; // bench comparison only
    if (g_log) {
        char msg[128];
        snprintf(msg, sizeof msg, "mw_llm_load: %s in %.0f ms\n", is_cpu(device) ? "CPU" : device, ms_since(t0));
        g_log(GGML_LOG_LEVEL_INFO, msg);
    }
    return llm;
}

char * mw_llm_generate(void * handle, const char * system, const char * user, int max_tokens,
                       float temperature, int timeout_ms, mw_stats * stats, char ** err) {
    auto * llm = (Llm *) handle;
    mw_stats st = {};
    const std::string prompt = format_prompt(*llm, system, user);

    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(llm->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(llm->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true);
    }
    if (n <= 0) {
        set_err(err, "tokenize failed");
        return nullptr;
    }
    toks.resize(n);
    const int n_ctx = (int) llama_n_ctx(llm->ctx);
    if (n + max_tokens > n_ctx) {
        set_err(err, "prompt too long for context");
        return nullptr;
    }
    st.prompt_tokens = n;

    // Reuse the KV cache for the shared prefix (system prompt); keep >= 1
    // token to decode so the last position has logits.
    llama_memory_t mem = llama_get_memory(llm->ctx);
    size_t keep = 0;
    while (keep < llm->cached.size() && keep < toks.size() && llm->cached[keep] == toks[keep]) ++keep;
    if (keep == toks.size()) --keep;
    if (keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    llm->cached.assign(toks.begin(), toks.begin() + keep);
    st.cached_tokens = (int) keep;

    auto t0 = std::chrono::steady_clock::now();
    for (size_t i = keep; i < toks.size(); i += 512) {
        int len = (int) std::min<size_t>(512, toks.size() - i);
        if (llama_decode(llm->ctx, llama_batch_get_one(toks.data() + i, len)) != 0) {
            llama_memory_clear(mem, true);
            llm->cached.clear();
            set_err(err, "prefill decode failed");
            return nullptr;
        }
        llm->cached.insert(llm->cached.end(), toks.begin() + i, toks.begin() + i + len);
    }
    st.prefill_ms = ms_since(t0);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    auto t1 = std::chrono::steady_clock::now();
    bool failed = false;
    auto emit = [&](llama_token tok) {
        char piece[256];
        int len = llama_token_to_piece(llm->vocab, tok, piece, sizeof piece, 0, true);
        if (len > 0) out.append(piece, len);
        st.gen_tokens++;
    };
    if (temperature <= 0.0f && !llm->no_lookup) {
        failed = !generate_lookup(llm, smpl, toks, max_tokens, timeout_ms, t0, emit);
    } else {
        for (int i = 0; i < max_tokens; ++i) {
            llama_token tok = llama_sampler_sample(smpl, llm->ctx, -1);
            if (llama_vocab_is_eog(llm->vocab, tok)) break;
            emit(tok);
            if (timeout_ms > 0 && ms_since(t0) > timeout_ms) break;
            if (llama_decode(llm->ctx, llama_batch_get_one(&tok, 1)) != 0) {
                failed = true;
                break;
            }
            llm->cached.push_back(tok);
        }
    }
    st.gen_ms = ms_since(t1);
    llama_sampler_free(smpl);
    if (failed) {
        llama_memory_clear(mem, true);
        llm->cached.clear();
        set_err(err, "decode failed");
        return nullptr;
    }
    if (stats) *stats = st;
    return dup_str(strip_reasoning(out));
}

void mw_llm_free(void * handle) {
    auto * llm = (Llm *) handle;
    if (!llm) return;
    llama_free(llm->ctx);
    llama_model_free(llm->model);
    delete llm;
}

void * mw_stt_load(const char * model_path, int kind, const char * device, int n_threads, char ** err) {
    bool use_gpu = !is_cpu(device);
    int gpu = 0;
    if (use_gpu) {
        ggml_backend_dev_t dev = ggml_backend_dev_by_name(device);
        gpu = dev ? gpu_index_of(dev) : -1;
        if (gpu < 0) {
            set_err(err, std::string("unknown GPU device ") + device);
            return nullptr;
        }
    }
    auto * stt = new Stt();
    stt->kind = kind;
    stt->n_threads = n_threads;
    if (kind == MW_STT_PARAKEET) {
        parakeet_context_params cp = parakeet_context_default_params();
        cp.use_gpu = use_gpu;
        cp.gpu_device = gpu;
        stt->pctx = parakeet_init_from_file_with_params(model_path, cp);
    } else {
        whisper_context_params cp = whisper_context_default_params();
        cp.use_gpu = use_gpu;
        cp.gpu_device = gpu;
        cp.flash_attn = use_gpu;
        stt->wctx = whisper_init_from_file_with_params(model_path, cp);
    }
    if (!stt->wctx && !stt->pctx) {
        delete stt;
        set_err(err, "stt model load failed");
        return nullptr;
    }
    return stt;
}

char * mw_stt_transcribe(void * handle, const float * pcm, int n_samples, const char * language,
                         const char * prompt, mw_stats * stats, char ** err) {
    auto * stt = (Stt *) handle;
    mw_stats st = {};
    std::string out;
    auto t0 = std::chrono::steady_clock::now();
    if (stt->kind == MW_STT_PARAKEET) {
        parakeet_full_params p = parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
        p.n_threads = stt->n_threads;
        p.no_context = true;
        if (parakeet_full(stt->pctx, p, pcm, n_samples) != 0) {
            set_err(err, "parakeet transcription failed");
            return nullptr;
        }
        for (int i = 0; i < parakeet_full_n_segments(stt->pctx); ++i) {
            out += parakeet_full_get_segment_text(stt->pctx, i);
        }
    } else {
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = stt->n_threads;
        p.no_context = true;
        // Timestamp tokens let the decoder see where the audio ends; without
        // them a shrunken audio_ctx (below) makes it repeat the sentence.
        p.no_timestamps = false;
        p.print_progress = false;
        p.print_realtime = false;
        p.print_special = false;
        p.print_timestamps = false;
        p.suppress_blank = true;
        p.language = (language && *language) ? language : "auto";
        p.initial_prompt = (prompt && *prompt) ? prompt : nullptr;
        // whisper encodes a fixed 30 s window; shrink it to the clip (50
        // frames/s + margin) so a 5 s dictation costs ~1/4 of the encoder.
        // Measured on Exynos 990: base 3.8x -> 15x realtime. Turbo's 4-layer
        // decoder repeats sentences unless it sees ~10 s past the speech
        // (margins 128/256 repeated, 512 and the full window did not).
        const bool turbo = whisper_model_n_text_layer(stt->wctx) <= 4 && whisper_model_n_audio_layer(stt->wctx) >= 24;
        int ctx = (int) (n_samples / 16000.0 * 50.0) + (turbo ? 512 : 128);
        if (ctx < 1500) p.audio_ctx = ctx;
        if (whisper_full(stt->wctx, p, pcm, n_samples) != 0) {
            set_err(err, "whisper transcription failed");
            return nullptr;
        }
        for (int i = 0; i < whisper_full_n_segments(stt->wctx); ++i) {
            out += whisper_full_get_segment_text(stt->wctx, i);
        }
    }
    st.gen_ms = ms_since(t0);
    if (stats) *stats = st;
    // Segments carry a leading space; callers expect trimmed text.
    size_t b = out.find_first_not_of(' ');
    return dup_str(b == std::string::npos ? std::string() : out.substr(b));
}

void mw_stt_free(void * handle) {
    auto * stt = (Stt *) handle;
    if (!stt) return;
    if (stt->wctx) whisper_free(stt->wctx);
    if (stt->pctx) parakeet_free(stt->pctx);
    delete stt;
}

void mw_free_str(char * s) { free(s); }

} // extern "C"
