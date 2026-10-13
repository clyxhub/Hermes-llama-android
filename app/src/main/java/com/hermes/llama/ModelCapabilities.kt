package com.hermes.llama

/**
 * Declared capabilities of a loaded model.
 *
 * Stage (c) will populate these from the mmproj GGUF metadata
 * (`clip.projector_type`, encoder arch, tensor shapes) rather than from file
 * extensions. The whole point of this type is that mmproj is NOT vision-only —
 * it projects whatever modality it was trained for into the LLM embedding
 * space, so the UI is generated from these flags.
 */
data class ModelCapabilities(
    val text: Boolean = true,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    val reasoning: Boolean = false,
    val embeddings: Boolean = false,
    val tools: Boolean = false
) {
    /** Human-readable summary for the model card. */
    fun describe(): String = buildList {
        if (text) add("text")
        if (vision) add("vision")
        if (audio) add("audio")
        if (video) add("video")
        if (reasoning) add("thinking")
        if (embeddings) add("embeddings")
        if (tools) add("tools")
    }.joinToString(", ").ifEmpty { "none" }

    companion object {
        /**
         * Stage (c): build the capability set from the *loaded* projector rather
         * than from the file name. Must be called after the model (and, when
         * present, the mmproj projector) have been loaded.
         */
        fun ofLoadedModel(reasoning: Boolean = false): ModelCapabilities = runCatching {
            val vision = LlamaBridge.supportsVision()
            val audio = LlamaBridge.supportsAudio()
            ModelCapabilities(
                text = true,
                vision = vision,
                audio = audio,
                video = vision,          // video = frame sampling over the vision path
                reasoning = reasoning,
                embeddings = true,       // local llama.cpp can always serve embeddings
                tools = false            // tool calling is not implemented yet
            )
        }.getOrDefault(ModelCapabilities(reasoning = reasoning))
    }
}