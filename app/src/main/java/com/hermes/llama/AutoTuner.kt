package com.hermes.llama

import android.content.Context
import android.os.Build

/**
 * Stage (b) completion: the "auto-tuner".
 *
 * Picks the per-(device, model) runtime configuration (thread count + GPU layer
 * offload) and persists the winning config so the same pair never re-tunes.
 *
 * A full multi-config benchmark (reload the model once per candidate) is
 * deliberately avoided: on a 1–3 GB GGUF each reload costs seconds and the
 * difference between adjacent thread counts is small. Instead we:
 *   1. consult the persisted config for this (device, model) pair, else
 *   2. apply a topology-derived heuristic (leave one core for the UI, cap at 6,
 *      offload all layers when the Vulkan backend is actually present),
 *   3. let the caller record the first measured tok/s back via [record].
 */
object AutoTuner {

    private const val PREFS = "hermes_autotune"

    data class Config(val threads: Int, val gpuLayers: Int)

    /** Stable key for "this device" — survives reinstalls of the model set. */
    fun deviceKey(): String {
        val socMan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else Build.MANUFACTURER
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.MODEL
        return listOf(socMan, socModel, Build.HARDWARE).joinToString("-").replace(' ', '_')
    }

    private fun key(modelPath: String): String =
        "${deviceKey()}::${modelPath.substringAfterLast('/')}"

    /** The persisted config for this (device, model), or null if never tuned. */
    fun load(context: Context, modelPath: String): Config? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(modelPath)
        if (!p.contains("$k::threads")) return null
        return Config(p.getInt("$k::threads", 4), p.getInt("$k::gpu", 0))
    }

    /**
     * Recommended config for this (device, model): the tuned value if present,
     * otherwise a topology-derived default.
     */
    fun recommend(context: Context, modelPath: String, gpuAvailable: Boolean): Config {
        load(context, modelPath)?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Leave a core for the UI/OS and cap at 6: beyond that the memory
        // bandwidth on a mobile SoC saturates and tok/s flattens.
        val threads = (cores - 1).coerceIn(2, 6)
        val gpu = if (gpuAvailable) ChatViewModel.GPU_LAYERS_ALL else 0
        return Config(threads, gpu)
    }

    /** Persist the winning config (and optionally the measured tok/s). */
    fun record(context: Context, modelPath: String, config: Config, tokensPerSecond: Double = 0.0) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(modelPath)
        p.edit()
            .putInt("$k::threads", config.threads)
            .putInt("$k::gpu", config.gpuLayers)
            .putFloat("$k::tps", tokensPerSecond.toFloat())
            .apply()
    }
}