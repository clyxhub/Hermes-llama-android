package com.hermes.llama

/**
 * Thin Kotlin wrapper over the native llama.cpp bridge.
 *
 * Stage (a): load model, stream a generation, cancel, free.
 * The native side owns a single global session; this object is the only
 * intended entry point to it.
 */
object LlamaBridge {

    init {
        System.loadLibrary("hermes_llama")
    }

    /** Token callback for [generate]. Called on the generation thread. */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    // --- native declarations -------------------------------------------------

    private external fun nativeInit(): Int
    private external fun nativeSystemInfo(): String
    private external fun nativeLoadModel(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int
    ): Int
    private external fun nativeGetContextSize(): Int
    private external fun nativeCancel()
    private external fun nativeGenerate(
        prompt: String, maxTokens: Int, temperature: Float, topP: Float, callback: TokenCallback
    ): Int
    private external fun nativeFree()

    // --- public API ----------------------------------------------------------

    fun init(): Int = nativeInit()

    fun systemInfo(): String = nativeSystemInfo()

    /** @return 0 on success; negative error code otherwise. */
    fun loadModel(path: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Int =
        nativeLoadModel(path, nCtx, nThreads, nGpuLayers)

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
}