package com.hermes.llama

/**
 * Maps a model family to its default runtime settings, so a freshly loaded
 * model gets sensible values with zero user input.
 *
 * Stage (a): family detection from the file name + sampler defaults.
 * Stage (e): reads real GGUF metadata (arch, context length, chat template)
 * from the loaded model and refines those defaults; also classifies the model
 * for the capability-driven UI.
 */
object PresetEngine {

    data class Preset(
        val family: String,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val contextSize: Int,
        val chatTemplate: String,
        /** True when the model should default to thinking/reasoning output. */
        val reasoning: Boolean = false
    )

    private val FAMILIES = listOf(
        "qwen"     to Preset("Qwen",     0.7f, 0.80f, 20, 8192, "chatml"),
        "llama"    to Preset("Llama",    0.8f, 0.95f, 40, 4096, "llama3"),
        "gemma"    to Preset("Gemma",    1.0f, 0.95f, 64, 8192, "gemma"),
        "phi"      to Preset("Phi",      0.7f, 0.90f, 40, 4096, "chatml"),
        "mistral"  to Preset("Mistral",  0.7f, 0.90f, 40, 8192, "mistral"),
        "deepseek" to Preset("DeepSeek", 0.6f, 0.90f, 40, 8192, "deepseek"),
        "reason"   to Preset("Reasoning",0.6f, 0.95f, 20, 16384, "chatml", reasoning = true)
    )

    private val DEFAULT = Preset("Generic", 0.8f, 0.95f, 40, 4096, "chatml")

    /** Best-effort family match from a model file name (used before load). */
    fun detect(fileName: String): Preset {
        val lower = fileName.lowercase()
        return FAMILIES.firstOrNull { lower.contains(it.first) }?.second ?: DEFAULT
    }

    /** Map a GGUF `general.architecture` string to a family preset. */
    fun familyForArch(arch: String?): Preset? {
        val a = arch?.lowercase() ?: return null
        return when {
            a.startsWith("qwen")     -> FAMILIES.first { it.first == "qwen" }.second
            a.startsWith("llama")    -> FAMILIES.first { it.first == "llama" }.second
            a.startsWith("gemma")    -> FAMILIES.first { it.first == "gemma" }.second
            a.startsWith("phi")      -> FAMILIES.first { it.first == "phi" }.second
            a.startsWith("mistral")  -> FAMILIES.first { it.first == "mistral" }.second
            a.startsWith("deepseek") -> FAMILIES.first { it.first == "deepseek" }.second
            else -> null
        }
    }

    /**
     * Enrich [base] with the loaded model's real GGUF metadata. Must be called
     * on a background thread after [LlamaBridge.loadModel] succeeded.
     */
    fun refine(base: Preset): Preset = runCatching {
        val arch  = LlamaBridge.modelMeta("general.architecture")
        val name  = LlamaBridge.modelMeta("general.name") ?: ""
        val ctxRaw = arch?.let { LlamaBridge.modelMeta("$it.context_length") }
        val ctx   = ctxRaw?.trim()?.toIntOrNull()?.coerceIn(512, 32768)
        val hasTemplate = LlamaBridge.chatTemplate() != null

        val family = familyForArch(arch)?.family ?: base.family
        val reasoning = base.reasoning ||
            name.contains("reason", ignoreCase = true) ||
            (arch?.contains("reason", ignoreCase = true) == true)

        base.copy(
            family = family,
            contextSize = ctx ?: base.contextSize,
            // "gguf" means "use the template embedded in the model".
            chatTemplate = if (hasTemplate) "gguf" else base.chatTemplate,
            reasoning = reasoning
        )
    }.getOrDefault(base)

    /** @return a short human-readable model summary for the model card. */
    fun describeModel(): String = runCatching {
        val desc = LlamaBridge.modelDesc() ?: return@runCatching "unknown"
        val params = LlamaBridge.modelParamCount()
        val sizeGb = LlamaBridge.modelSize() / 1_000_000_000.0
        val bill = if (params > 0) "%.2fB".format(params / 1_000_000_000.0) else "?"
        "$desc · $bill params · %.2f GB".format(sizeGb)
    }.getOrDefault("unknown")

    /**
     * Named-template fallback used when the GGUF carries no chat template.
     * Stage (e) prefers [LlamaBridge.applyChatTemplate]; this is the backup.
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
        "deepseek" -> buildString {
            if (!system.isNullOrBlank()) append("$system\n\n")
            append("User: $user\n\nAssistant: ")
        }
        else -> buildString { // chatml — the most common fallback
            if (!system.isNullOrBlank()) append("<|im_start|>system\n$system<|im_end|>\n")
            append("<|im_start|>user\n$user<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }
}