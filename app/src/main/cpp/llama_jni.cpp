// ---------------------------------------------------------------------------
// llama_jni.cpp — JNI bridge between Kotlin (LlamaBridge.kt) and llama.cpp.
//
// Stage (a): load a GGUF model, run a blocking generate() call, stream tokens.
// Stage (b): GPU offload (-ngl) + CPU-only device restriction.
// Stage (c): multimodal projector (mmproj) -> image/audio input via mtmd.
// Stage (d)/(e): the embedded HTTP server re-uses infer_mutex as its queue,
//                and the sampler chain is built from the model preset.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>

#include "llama.h"
#include "common.h"

#ifdef HERMES_HAS_MTMD
// Stage (c): multimodal projector (mmproj) support. mtmd projects whatever
// modality the projector was trained for into the LLM embedding space - it is
// not a "vision file".
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

#define LOG_TAG "HermesLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaSession {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx              = 0;
    int n_threads          = 4;
    // Single inference at a time. The embedded server (stage d) reuses
    // this same mutex as its inference queue.
    std::mutex     infer_mutex;
    std::atomic<bool> cancel{false};
};

#ifdef HERMES_HAS_MTMD
// Stage (c): the loaded projector (mmproj). Projector is modality-agnostic:
// whatever the GGUF was trained for (vision, audio, ...) comes out of here.
mtmd_context* g_mtmd = nullptr;

void hermes_mtmd_free() {
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
}
#endif

// One global session for stage (a). Multi-model comes later.
LlamaSession g_session;

// Route llama.cpp / ggml log output (backend enumeration + the
// "offloaded N/N layers to GPU" line) into logcat so GPU offload is
// observable with:  adb logcat -s HermesLlama-native
void hermes_log_callback(ggml_log_level level, const char* text, void* /*user*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_print(prio, "HermesLlama-native", "%s", text ? text : "");
}

std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeInit(JNIEnv*, jobject) {
    // Forward native logs to logcat *before* touching the backend so that
    // backend enumeration (Vulkan device discovery) is captured.
    llama_log_set(hermes_log_callback, nullptr);
    ggml_log_set(hermes_log_callback, nullptr);

    llama_backend_init();
    LOGI("llama backend initialised (gpu_offload_supported=%d)",
         llama_supports_gpu_offload() ? 1 : 0);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSystemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

/** True when a GPU backend (Vulkan) is registered and can offload layers. */
JNIEXPORT jboolean JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSupportsGpu(JNIEnv*, jobject) {
    return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Load a GGUF model. Returns 0 on success, negative on failure.
 *
 * nGpuLayers drives GPU (Vulkan) offload since stage (b): 0 = CPU only, a
 * large value (e.g. 99) offloads every layer the backend can hold.
 */
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads, jint nGpuLayers) {

    std::lock_guard<std::mutex> lock(g_session.infer_mutex);

    if (g_session.ctx) {
        llama_free(g_session.ctx);
        g_session.ctx = nullptr;
    }
    if (g_session.model) {
        llama_model_free(g_session.model);
        g_session.model = nullptr;
    }

    const std::string path = jstring_to_std(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    // Stage (b): honour the GPU-layers request. 0 = CPU only; a large value
    // (e.g. 99) offloads every layer the Vulkan backend can hold.
    // HERMES-PATCH(gpu-guard): never request GPU offload this build cannot do.
    // If no GPU backend registered (or the driver is absent), force CPU rather
    // than letting llama.cpp pick a bogus device.
    if (nGpuLayers > 0 && !llama_supports_gpu_offload()) {
        LOGI("HERMES: GPU offload requested but unsupported — forcing CPU");
        nGpuLayers = 0;
    }
    mparams.n_gpu_layers = nGpuLayers < 0 ? 0 : nGpuLayers;

    // HERMES-PATCH(cpu-only-devices): when GPU offload is disabled, restrict the
    // model to the CPU device. If `devices` is left NULL, the scheduler registers
    // ALL backends (including Vulkan); even with n_gpu_layers = 0 that pulls the
    // Vulkan buffer into the graph (sched_reserve: Vulkan0 compute buffer ~900MiB,
    // splits = 388/1) and measurably slows inference vs. the CPU-only build.
    if (nGpuLayers <= 0) {
        ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpu_dev != nullptr) {
            static ggml_backend_dev_t hermes_cpu_devices[2];
            hermes_cpu_devices[0] = cpu_dev;
            hermes_cpu_devices[1] = nullptr;
            mparams.devices = hermes_cpu_devices;
            LOGI("HERMES: CPU-only mode - restricting model devices to CPU");
        }
    }

    g_session.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_session.model) {
        LOGE("failed to load model: %s", path.c_str());
        return -1;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = static_cast<uint32_t>(nCtx > 0 ? nCtx : 4096);
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_batch   = 512;

    g_session.ctx = llama_init_from_model(g_session.model, cparams);
    if (!g_session.ctx) {
        LOGE("failed to create context");
        llama_model_free(g_session.model);
        g_session.model = nullptr;
        return -2;
    }

    g_session.vocab = llama_model_get_vocab(g_session.model);
    g_session.n_ctx = static_cast<int>(llama_n_ctx(g_session.ctx));
    g_session.n_threads = nThreads > 0 ? nThreads : 4;
    g_session.cancel.store(false);
#ifdef HERMES_HAS_MTMD
    // A projector is bound to the model it was loaded for: drop it on reload.
    hermes_mtmd_free();
#endif

    LOGI("model loaded: %s (n_ctx=%d, threads=%d, n_gpu_layers=%d)",
         path.c_str(), g_session.n_ctx, nThreads, (int) mparams.n_gpu_layers);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeGetContextSize(JNIEnv*, jobject) {
    return g_session.n_ctx;
}

JNIEXPORT void JNICALL
Java_com_hermes_llama_LlamaBridge_nativeCancel(JNIEnv*, jobject) {
    g_session.cancel.store(true);
}

/**
 * Generate from a raw prompt, invoking `callback.onToken(String)` per piece.
 * Returns the number of tokens generated, or negative on error.
 *
 * Note: chat templating is applied Kotlin-side for stage (a); the PresetEngine
 * (stage e) will move template selection into the native layer per-model.
 */
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject, jstring jprompt, jint maxTokens,
        jfloat temperature, jfloat topP, jobject callback) {

    std::lock_guard<std::mutex> lock(g_session.infer_mutex);

    if (!g_session.ctx || !g_session.model) {
        LOGE("generate called with no model loaded");
        return -1;
    }

    // Resolve the Kotlin callback method once.
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("callback missing onToken(String)");
        return -2;
    }

    const std::string prompt = jstring_to_std(env, jprompt);

    // --- tokenize -----------------------------------------------------------
    std::vector<llama_token> tokens(prompt.size() + 8);
    int n = llama_tokenize(g_session.vocab, prompt.c_str(),
                           static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(g_session.vocab, prompt.c_str(),
                           static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           true, true);
    }
    if (n < 0) { LOGE("tokenize failed"); return -3; }
    tokens.resize(n);

    // --- sampler chain ------------------------------------------------------
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Chat turns re-send the whole history, so reset the KV cache first: this
    // restarts positions at 0 and stops the context silently overflowing after
    // a few turns (which showed up as "prompt decode failed" = -4).
    if (llama_memory_t mem = llama_get_memory(g_session.ctx)) {
        llama_memory_clear(mem, true);
    }

    // --- decode prompt ------------------------------------------------------
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_session.ctx, batch) != 0) {
        LOGE("prompt decode failed");
        llama_sampler_free(smpl);
        return -4;
    }

    // --- generation loop ----------------------------------------------------
    int generated = 0;
    while (generated < maxTokens && !g_session.cancel.load()) {
        llama_token id = llama_sampler_sample(smpl, g_session.ctx, -1);

        if (llama_vocab_is_eog(g_session.vocab, id)) break;

        char buf[256];
        int len = llama_token_to_piece(g_session.vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_session.ctx, next) != 0) {
            LOGE("decode failed during generation");
            break;
        }
        generated++;
    }

    llama_sampler_free(smpl);
    g_session.cancel.store(false);
    LOGI("generation finished: %d tokens", generated);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_hermes_llama_LlamaBridge_nativeFree(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_session.infer_mutex);
#ifdef HERMES_HAS_MTMD
    hermes_mtmd_free();
#endif
    if (g_session.ctx)   { llama_free(g_session.ctx);          g_session.ctx = nullptr; }
    if (g_session.model) { llama_model_free(g_session.model);  g_session.model = nullptr; }
    g_session.vocab = nullptr;
    g_session.n_ctx = 0;
    llama_backend_free();
    LOGI("model + backend freed");
}

/**
 * Stage (e): model metadata + chat templating.
 *
 * The PresetEngine reads real GGUF metadata (arch, context length, quant) via
 * these, instead of guessing from the file name, and formats chat turns with
 * the template embedded in the GGUF (falling back to a named template).
 */

JNIEXPORT jstring JNICALL
Java_com_hermes_llama_LlamaBridge_nativeModelDesc(JNIEnv* env, jobject) {
    if (!g_session.model) return nullptr;
    char buf[512];
    if (llama_model_desc(g_session.model, buf, sizeof(buf)) < 0) return nullptr;
    return env->NewStringUTF(buf);
}

/** Returns the GGUF metadata value for `key`, or null when absent. */
JNIEXPORT jstring JNICALL
Java_com_hermes_llama_LlamaBridge_nativeModelMeta(JNIEnv* env, jobject, jstring jkey) {
    if (!g_session.model) return nullptr;
    const std::string key = jstring_to_std(env, jkey);
    char buf[8192];
    if (llama_model_meta_val_str(g_session.model, key.c_str(), buf, sizeof(buf)) < 0) return nullptr;
    return env->NewStringUTF(buf);
}

/** Total parameter count of the loaded model. */
JNIEXPORT jlong JNICALL
Java_com_hermes_llama_LlamaBridge_nativeModelParamCount(JNIEnv*, jobject) {
    return g_session.model ? (jlong) llama_model_n_params(g_session.model) : 0;
}

/** Total size of all tensors, bytes. */
JNIEXPORT jlong JNICALL
Java_com_hermes_llama_LlamaBridge_nativeModelSize(JNIEnv*, jobject) {
    return g_session.model ? (jlong) llama_model_size(g_session.model) : 0;
}

/** The default chat template embedded in the GGUF, or null. */
JNIEXPORT jstring JNICALL
Java_com_hermes_llama_LlamaBridge_nativeChatTemplate(JNIEnv* env, jobject) {
    if (!g_session.model) return nullptr;
    const char * tmpl = llama_model_chat_template(g_session.model, nullptr);
    return tmpl ? env->NewStringUTF(tmpl) : nullptr;
}

/**
 * Apply a chat template to parallel role/content arrays and return the
 * formatted prompt. When `jtmpl` is empty the model's own template is used.
 */
JNIEXPORT jstring JNICALL
Java_com_hermes_llama_LlamaBridge_nativeApplyChatTemplate(
        JNIEnv* env, jobject, jstring jtmpl, jobjectArray jroles,
        jobjectArray jcontents, jboolean addAss) {

    if (!g_session.model) return nullptr;

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roleStore(n), contentStore(n);
    std::vector<llama_chat_message> msgs;
    msgs.reserve(n);
    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        roleStore[i]    = jstring_to_std(env, jr);
        contentStore[i] = jstring_to_std(env, jc);
        if (jr) env->DeleteLocalRef(jr);
        if (jc) env->DeleteLocalRef(jc);
    }
    for (jsize i = 0; i < n; i++) {
        llama_chat_message m;
        m.role    = roleStore[i].c_str();
        m.content = contentStore[i].c_str();
        msgs.push_back(m);
    }

    const std::string tmpl = jstring_to_std(env, jtmpl);
    const char * tmplC = tmpl.empty()
            ? llama_model_chat_template(g_session.model, nullptr)
            : tmpl.c_str();
    if (!tmplC) return nullptr;

    int32_t need = llama_chat_apply_template(
            tmplC, msgs.data(), msgs.size(), addAss, nullptr, 0);
    if (need <= 0) return nullptr;

    std::string out(need, '\0');
    int32_t written = llama_chat_apply_template(
            tmplC, msgs.data(), msgs.size(), addAss, out.data(), (int32_t) out.size());
    if (written < 0) return nullptr;
    if (written < need) out.resize(written);
    return env->NewStringUTF(out.c_str());
}

/** Token count for a string under the loaded vocab (for the token stats UI). */
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeCountTokens(JNIEnv* env, jobject, jstring jtext) {
    if (!g_session.vocab) return -1;
    const std::string text = jstring_to_std(env, jtext);
    std::vector<llama_token> tmp(text.size() + 16);
    int n = llama_tokenize(g_session.vocab, text.c_str(), (int32_t) text.size(),
                           tmp.data(), (int32_t) tmp.size(), true, true);
    if (n < 0) {
        tmp.resize(-n);
        n = llama_tokenize(g_session.vocab, text.c_str(), (int32_t) text.size(),
                           tmp.data(), (int32_t) tmp.size(), true, true);
    }
    return n < 0 ? -1 : n;
}

#ifdef HERMES_HAS_MTMD
/**
 * Stage (c): load an mmproj (projector) GGUF for the currently loaded model.
 * Returns 0 on success, negative on failure, -99 when mtmd is unavailable.
 *
 * The projector is deliberately run on the CPU (p.use_gpu = false): the Vulkan
 * encoder path is the least stable thing on this driver and image encoding is a
 * one-shot cost, unlike token-by-token decoding.
 */
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeLoadProjector(JNIEnv* env, jobject, jstring jpath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_session.infer_mutex);
    if (!g_session.model) { LOGE("projector load requires a loaded model"); return -1; }

    hermes_mtmd_free();

    const std::string path = jstring_to_std(env, jpath);
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu       = false;
    p.print_timings = false;
    p.n_threads     = nThreads > 0 ? nThreads : 4;
    p.media_marker  = mtmd_default_marker();

    g_mtmd = mtmd_init_from_file(path.c_str(), g_session.model, p);
    if (!g_mtmd) {
        LOGE("mtmd_init_from_file failed: %s", path.c_str());
        return -2;
    }

    LOGI("projector loaded: %s (vision=%d audio=%d marker=%s)", path.c_str(),
         mtmd_support_vision(g_mtmd) ? 1 : 0,
         mtmd_support_audio(g_mtmd) ? 1 : 0,
         mtmd_default_marker());
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSupportsVision(JNIEnv*, jobject) {
    return (g_mtmd && mtmd_support_vision(g_mtmd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSupportsAudio(JNIEnv*, jobject) {
    return (g_mtmd && mtmd_support_audio(g_mtmd)) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Stage (c): one-shot prompt+media generation. mtmd tokenizes the prompt with
 * the media spliced at the marker, encodes the bitmap, and decodes the mixed
 * embeddings; the generation loop is identical to the text-only path.
 *
 * A dedicated context is created and freed per call so the KV cache is clean
 * (cheap relative to encoding the image).
 */
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeGenerateWithMedia(
        JNIEnv* env, jobject, jstring jprompt, jstring jmedia,
        jint maxTokens, jfloat temperature, jfloat topP, jobject callback) {

    std::lock_guard<std::mutex> lock(g_session.infer_mutex);
    if (!g_session.model || !g_mtmd) { LOGE("media generate: no model or projector loaded"); return -1; }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) { LOGE("callback missing onToken(String)"); return -2; }

    const std::string prompt = jstring_to_std(env, jprompt);
    const std::string media  = jstring_to_std(env, jmedia);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = static_cast<uint32_t>(g_session.n_ctx > 0 ? g_session.n_ctx : 4096);
    cparams.n_threads = g_session.n_threads;
    cparams.n_batch   = 512;
    llama_context* ctx = llama_init_from_model(g_session.model, cparams);
    if (!ctx) { LOGE("media generate: context creation failed"); return -3; }

    mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    mtmd_helper_bitmap_wrapper wrap = mtmd_helper_bitmap_init_from_file(g_mtmd, media.c_str(), false, opt);
    if (wrap.bitmap == nullptr) {
        LOGE("media generate: could not decode media file %s", media.c_str());
        llama_free(ctx);
        return -4;
    }

    const std::string text = std::string(mtmd_default_marker()) + "\n" + prompt;
    mtmd_input_text in_text{ text.c_str(), text.size(), true, true };
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bitmaps[1] = { wrap.bitmap };

    int32_t rc = mtmd_tokenize(g_mtmd, chunks, &in_text, bitmaps, 1);
    if (rc != 0) {
        LOGE("mtmd_tokenize failed: %d", rc);
        mtmd_input_chunks_free(chunks); mtmd_bitmap_free(wrap.bitmap); llama_free(ctx);
        return -5;
    }
    LOGI("media: %zu tokens across %zu chunks", mtmd_helper_get_n_tokens(chunks), mtmd_input_chunks_size(chunks));

    llama_pos new_n_past = 0;
    rc = mtmd_helper_eval_chunks(g_mtmd, ctx, chunks, 0, 0, 512, true, &new_n_past);
    if (rc != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", rc);
        mtmd_input_chunks_free(chunks); mtmd_bitmap_free(wrap.bitmap); llama_free(ctx);
        return -6;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_session.cancel.store(false);
    int generated = 0;
    while (generated < maxTokens && !g_session.cancel.load()) {
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(g_session.vocab, id)) break;

        char buf[256];
        int len = llama_token_to_piece(g_session.vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, next) != 0) { LOGE("decode failed during media generation"); break; }
        generated++;
    }

    llama_sampler_free(smpl);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(wrap.bitmap);
    llama_free(ctx);
    g_session.cancel.store(false);
    LOGI("media generation finished: %d tokens", generated);
    return generated;
}
#else
// mtmd was not built: keep the JNI surface stable and report "unsupported".
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeLoadProjector(JNIEnv*, jobject, jstring, jint) { return -99; }
JNIEXPORT jboolean JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSupportsVision(JNIEnv*, jobject) { return JNI_FALSE; }
JNIEXPORT jboolean JNICALL
Java_com_hermes_llama_LlamaBridge_nativeSupportsAudio(JNIEnv*, jobject) { return JNI_FALSE; }
JNIEXPORT jint JNICALL
Java_com_hermes_llama_LlamaBridge_nativeGenerateWithMedia(
        JNIEnv*, jobject, jstring, jstring, jint, jfloat, jfloat, jobject) { return -99; }
#endif

} // extern "C"
