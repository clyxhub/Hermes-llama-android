package com.hermes.llama

/**
 * Maps a model family to its default runtime settings, so a freshly loaded
 * model gets sensible values with zero user input.
 *
 * Stage (a): family detection + sampler defaults only.
 * Stage (e): reads real GGUF metadata (arch, param count, quant, chat template)
 * and drives the auto-tuner.
 */
object PresetEngine {

    data class Preset(
        val family: String,
        val temperature: Float,
        val topP: Float,
        val contextSize: Int,
        val chatTemplate: String
    )

    private val FAMILIES = listOf(
        "qwen"     to Preset("Qwen",     0.7f, 0.80f, 8192, "chatml"),
        "llama"    to Preset("Llama",    0.8f, 0.95f, 4096, "llama3"),
        "gemma"    to Preset("Gemma",    1.0f, 0.95f, 8192, "gemma"),
        "phi"      to Preset("Phi",      0.7f, 0.90f, 4096, "chatml"),
        "mistral"  to Preset("Mistral",  0.7f, 0.90f, 8192, "mistral"),
        "deepseek" to Preset("DeepSeek", 0.6f, 0.90f, 8192, "deepseek")
    )

    private val DEFAULT = Preset("Generic", 0.8f, 0.95f, 4096, "chatml")

    /** Best-effort family match from a model file name. */
    fun detect(fileName: String): Preset {
        val lower = fileName.lowercase()
        return FAMILIES.firstOrNull { lower.contains(it.first) }?.second ?: DEFAULT
    }

    /**
     * Wrap a user turn in the family's chat template.
     * Stage (e) replaces this with the template embedded in the GGUF.
     */
    fun applyTemplate(preset: Preset, system: String?, user: String): String = when (preset.chatTemplate) {
        "llama3" -> buildString {
            append("<|begin_of_text|>")
            if (!system.isNullOrBlank())
                append("<|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>")
            append("<|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|>")
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
        "mistral" -> buildString {
            if (!system.isNullOrBlank()) append("[INST] $system\n\n$user [/INST]")
            else append("[INST] $user [/INST]")
        }
        "gemma" -> buildString {
            if (!system.isNullOrBlank()) append("<start_of_turn>user\n$system\n\n$user<end_of_turn>\n")
            else append("<start_of_turn>user\n$user<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        else -> buildString { // chatml — the most common fallback
            if (!system.isNullOrBlank()) append("<|im_start|>system\n$system<|im_end|>\n")
            append("<|im_start|>user\n$user<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }
}