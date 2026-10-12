// ---------------------------------------------------------------------------
// llama_jni.cpp — JNI bridge between Kotlin (LlamaBridge.kt) and llama.cpp.
//
// Stage (a) scope: load a GGUF model, run a blocking generate() call, and
// stream tokens back to Kotlin through a callback. This is deliberately the
// smallest thing that proves the native build + core inference loop works.
//
// Deliberately NOT here yet (later stages):
//   - mmproj / multimodal projection       -> stage (c)
//   - HTTP server (llama-server embed)     -> stage (d)
//   - sampler presets / thinking mode      -> stage (e)
//   - GPU offload (-ngl) + auto-tuner      -> stage (b) [done: Vulkan]
// ---------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>

#include "llama.h"
#include "common.h"

#define LOG_TAG "HermesLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaSession {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx              = 0;
    // Single inference at a time. The embedded server (stage d) will reuse
    // this same mutex as its inference queue.
    std::mutex     infer_mutex;
    std::atomic<bool> cancel{false};
};

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
    g_session.cancel.store(false);

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
    if (g_session.ctx)   { llama_free(g_session.ctx);          g_session.ctx = nullptr; }
    if (g_session.model) { llama_model_free(g_session.model);  g_session.model = nullptr; }
    g_session.vocab = nullptr;
    g_session.n_ctx = 0;
    llama_backend_free();
    LOGI("model + backend freed");
}

} // extern "C"
