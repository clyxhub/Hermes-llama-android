package com.hermes.llama

/**
 * Thin Kotlin wrapper over the native llama.cpp bridge.
 *
 * Stage (a): load model, stream a generation, cancel, free.
 * Stage (b): GPU offload (-ngl) + CPU-only device restriction.
 * Stage (c): mmproj projector load + media (image/audio) generation via mtmd.
 * Stage (e): GGUF metadata access + template-based prompt formatting.
 *
 * The native side owns a single global session; this object is the only
 * intended entry point to it.
 */
object LlamaBridge {

    init {
        System.loadLibrary("hermes_llama")
    }

    /** Token callback for [generate]/[generateWithMedia]. Called on the decoding thread. */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    // --- native declarations -------------------------------------------------

    private external fun nativeInit(): Int
    private external fun nativeSystemInfo(): String
    private external fun nativeSupportsGpu(): Boolean
    private external fun nativeLoadModel(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int
    ): Int
    private external fun nativeSetKvQuant(mode: Int)
    private external fun nativeGetContextSize(): Int
    private external fun nativeCancel()
    private external fun nativeGenerate(
        prompt: String, maxTokens: Int, temperature: Float, topP: Float, callback: TokenCallback
    ): Int
    private external fun nativeFree()

    // stage (e): metadata / templating
    private external fun nativeModelDesc(): String?
    private external fun nativeModelMeta(key: String): String?
    private external fun nativeModelParamCount(): Long
    private external fun nativeModelSize(): Long
    private external fun nativeChatTemplate(): String?
    private external fun nativeApplyChatTemplate(
        template: String, roles: Array<String>, contents: Array<String>, addAssistant: Boolean
    ): String?
    private external fun nativeCountTokens(text: String): Int

    // stage (c): multimodal projector
    private external fun nativeLoadProjector(path: String, nThreads: Int): Int
    private external fun nativeSupportsVision(): Boolean
    private external fun nativeSupportsAudio(): Boolean
    private external fun nativeGenerateWithMedia(
        prompt: String, mediaPath: String, maxTokens: Int,
        temperature: Float, topP: Float, callback: TokenCallback
    ): Int

    // --- public API ----------------------------------------------------------

    fun init(): Int = nativeInit()

    fun systemInfo(): String = nativeSystemInfo()

    /** True when a GPU backend (Vulkan) is registered for layer offload. */
    fun supportsGpu(): Boolean = nativeSupportsGpu()

    /** KV-cache element type modes for [setKvQuant]. */
    const val KV_OFF = 0
    const val KV_Q8_0 = 1
    const val KV_Q4_0 = 2

    /** @return 0 on success; negative error code otherwise. */
    fun loadModel(path: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Int =
        nativeLoadModel(path, nCtx, nThreads, nGpuLayers)

    /**
     * Choose the KV-cache element type used by the NEXT [loadModel] call.
     * 0 = f16 (default), 1 = q8_0, 2 = q4_0. Quantizing roughly halves the
     * cache's memory, which is what keeps long contexts from OOM-ing a phone.
     */
    fun setKvQuant(mode: Int) = nativeSetKvQuant(mode)

    fun contextSize(): Int = nativeGetContextSize()

    fun cancel() = nativeCancel()

    fun free() = nativeFree()

    /**
     * Blocking generation. Must be called off the main thread.
     * @return number of tokens generated, or negative on error.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        onToken: (String) -> Unit
    ): Int = nativeGenerate(prompt, maxTokens, temperature, topP, TokenCallback(onToken))

    // --- metadata / templating ----------------------------------------------

    fun modelDesc(): String? = nativeModelDesc()
    fun modelMeta(key: String): String? = nativeModelMeta(key)
    fun modelParamCount(): Long = nativeModelParamCount()
    fun modelSize(): Long = nativeModelSize()
    fun chatTemplate(): String? = nativeChatTemplate()
    fun countTokens(text: String): Int = nativeCountTokens(text)

    /**
     * Apply [template] (empty = the model's own) to the conversation and return
     * the formatted prompt, or null when no model/template is available.
     */
    fun applyChatTemplate(
        template: String,
        roles: List<String>,
        contents: List<String>,
        addAssistant: Boolean = true
    ): String? {
        if (roles.isEmpty() || roles.size != contents.size) return null
        return nativeApplyChatTemplate(template, roles.toTypedArray(), contents.toTypedArray(), addAssistant)
    }

    // --- multimodal (stage c) -----------------------------------------------

    /** @return 0 on success; -99 when the build has no mtmd support. */
    fun loadProjector(path: String, nThreads: Int = 4): Int = nativeLoadProjector(path, nThreads)

    fun supportsVision(): Boolean = nativeSupportsVision()
    fun supportsAudio(): Boolean = nativeSupportsAudio()

    /** Generate from a prompt + one media file (image/audio handled by the projector). */
    fun generateWithMedia(
        prompt: String,
        mediaPath: String,
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        onToken: (String) -> Unit
    ): Int = nativeGenerateWithMedia(
        prompt, mediaPath, maxTokens, temperature, topP, TokenCallback(onToken)
    )
}
