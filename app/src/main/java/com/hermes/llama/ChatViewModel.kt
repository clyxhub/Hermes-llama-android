package com.hermes.llama

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(val role: String, val content: String)

data class UiState(
    val modelPath: String? = null,
    val modelName: String = "",
    val projectorPath: String? = null,
    val preset: PresetEngine.Preset? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val busy: Boolean = false,
    val status: String = "No model loaded",
    val modelInfo: String = "",
    val systemInfo: String = "",
    /** Layers to offload to the Vulkan GPU backend; 0 = CPU only. */
    val gpuLayers: Int = 0,
    val threads: Int = 4,
    /** Thinking mode: render a collapsible reasoning trace when supported. */
    val thinking: Boolean = false,
    /** Pending media attachment (image/audio) for the next turn. */
    val attachment: String? = null,
    val attachmentIsAudio: Boolean = false,
    val tokensPerSecond: Double = 0.0,
    val serverRunning: Boolean = false,
    val serverPort: Int = 8080
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("hermes_llama", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 0),
            messages = loadHistory()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LlamaBridge.init()
                _state.update { it.copy(systemInfo = LlamaBridge.systemInfo()) }
            }
            // Quick win: remember the last-used model and auto-load it on start
            // so the user doesn't have to re-pick the .gguf every launch.
            prefs.getString(KEY_LAST_MODEL, null)?.let { last ->
                if (File(last).exists()) loadModel(last)
                else prefs.edit().remove(KEY_LAST_MODEL).apply()
            }
        }
    }

    // --- history persistence ------------------------------------------------

    private fun saveHistory(messages: List<ChatMessage>) {
        val arr = org.json.JSONArray()
        messages.forEach { arr.put(org.json.JSONObject().put("role", it.role).put("content", it.content)) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun loadHistory(): List<ChatMessage> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]")!!)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatMessage(o.getString("role"), o.getString("content"))
        }
    }.getOrDefault(emptyList())

    fun clearChat() {
        saveHistory(emptyList())
        _state.update { it.copy(messages = emptyList(), streaming = "", status = "Chat cleared") }
    }

    // --- model loading ------------------------------------------------------

    /** True when the native build reports a usable GPU (Vulkan) backend. */
    private fun gpuAvailable(): Boolean =
        _state.value.systemInfo.contains("Vulkan", ignoreCase = true)

    /**
     * Stage (c) helper: a model's projector is shipped alongside it as a
     * `*mmproj*.gguf`. Auto-detect and pair it so the user never has to.
     */
    private fun findProjector(modelPath: String): String? = runCatching {
        val dir = File(modelPath).parentFile ?: return@runCatching null
        dir.listFiles()
            ?.firstOrNull {
                it.isFile && it.name.endsWith(".gguf", ignoreCase = true) &&
                    it.name.contains("mmproj", ignoreCase = true)
            }
            ?.absolutePath
    }.getOrNull()

    /** Load a GGUF off local storage, auto-pairing any mmproj projector. */
    fun loadModel(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(status = "Loading…", busy = true) }

            val preset0 = PresetEngine.detect(path.substringAfterLast('/'))
            val cfg = AutoTuner.recommend(getApplication(), path, gpuAvailable())
            val nGpuLayers = _state.value.gpuLayers

            val rc = runCatching { LlamaBridge.loadModel(path, preset0.contextSize, cfg.threads, nGpuLayers) }
                .getOrDefault(-99)

            if (rc != 0) {
                _state.update { it.copy(busy = false, status = "Load failed (code $rc)") }
                return@launch
            }

            // Stage (e): refine the preset from real GGUF metadata now the model is in memory.
            val preset = PresetEngine.refine(preset0)
            val info = PresetEngine.describeModel()

            // Stage (c): auto-pair + load the projector, then derive capabilities.
            val projector = findProjector(path)
            var projectorOk = false
            if (projector != null) {
                projectorOk = runCatching { LlamaBridge.loadProjector(projector, cfg.threads) }.getOrDefault(-1) == 0
            }
            val caps = ModelCapabilities.ofLoadedModel(reasoning = preset.reasoning)

            val backend = if (nGpuLayers > 0) "GPU×$nGpuLayers" else "CPU"

            // Persist the winning config for this (device, model) pair.
            AutoTuner.record(getApplication(), path, AutoTuner.Config(cfg.threads, nGpuLayers))
            prefs.edit().putString(KEY_LAST_MODEL, path).apply()

            _state.update {
                it.copy(
                    modelPath = path,
                    modelName = path.substringAfterLast('/'),
                    projectorPath = if (projectorOk) projector else null,
                    preset = preset,
                    capabilities = caps,
                    threads = cfg.threads,
                    busy = false,
                    thinking = preset.reasoning,
                    modelInfo = "$info · $backend · ${cfg.threads} threads" +
                        if (projectorOk) " · projector ✓" else "",
                    status = "Loaded ${preset.family} · ctx ${LlamaBridge.contextSize()} · $backend"
                )
            }
        }
    }

    // --- generation ---------------------------------------------------------

    /**
     * Format the whole conversation with the model's own chat template, falling
     * back to the named template from [PresetEngine]. Stage (e).
     */
    private fun buildPrompt(preset: PresetEngine.Preset, turns: List<ChatMessage>): String {
        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (SYSTEM_PROMPT.isNotBlank()) { roles += "system"; contents += SYSTEM_PROMPT }
        turns.forEach { roles += it.role; contents += it.content }

        val tmpl = if (preset.chatTemplate == "gguf") "" else preset.chatTemplate
        val viaModel = runCatching { LlamaBridge.applyChatTemplate(tmpl, roles, contents, true) }.getOrNull()
        return viaModel ?: PresetEngine.applyTemplate(preset, SYSTEM_PROMPT, turns.lastOrNull()?.content ?: "")
    }

    fun send(input: String, mediaPath: String? = null) {
        val s = _state.value
        val preset = s.preset ?: return
        if ((input.isBlank() && mediaPath == null) || s.busy) return

        val turn = ChatMessage("user", input.ifBlank { "Describe this." })
        val history = s.messages + turn
        saveHistory(history)
        _state.update {
            it.copy(messages = history, streaming = "", busy = true, status = "Generating…",
                attachment = null, attachmentIsAudio = false)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val prompt = buildPrompt(preset, history)
            val sb = StringBuilder()
            val startedAt = System.currentTimeMillis()

            val generated = runCatching {
                if (mediaPath != null) {
                    LlamaBridge.generateWithMedia(
                        prompt, mediaPath, MAX_TOKENS, preset.temperature, preset.topP
                    ) { tok -> sb.append(tok); _state.update { it.copy(streaming = sb.toString()) } }
                } else {
                    LlamaBridge.generate(
                        prompt, MAX_TOKENS, preset.temperature, preset.topP
                    ) { tok -> sb.append(tok); _state.update { it.copy(streaming = sb.toString()) } }
                }
            }.getOrDefault(-1)

            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            val tokPerSec = if (generated > 0) generated * 1000.0 / elapsedMs else 0.0
            val speed = if (tokPerSec >= 1.0) " · %.1f tok/s".format(tokPerSec) else ""

            // Persist the measured throughput so the auto-tuner keeps the win.
            _state.value.modelPath?.let { p ->
                AutoTuner.record(
                    getApplication(), p,
                    AutoTuner.Config(_state.value.threads, _state.value.gpuLayers),
                    tokPerSec
                )
            }

            withContext(Dispatchers.Main) {
                _state.update {
                    val finalMessages = it.messages + ChatMessage("assistant", sb.toString())
                    saveHistory(finalMessages)
                    it.copy(
                        messages = finalMessages,
                        streaming = "",
                        busy = false,
                        tokensPerSecond = tokPerSec,
                        status = "Ready$speed"
                    )
                }
            }
        }
    }

    /** Re-run the last user turn (regenerate). */
    fun regenerate() {
        val msgs = _state.value.messages
        val lastUser = msgs.lastOrNull { it.role == "user" } ?: return
        _state.update { it.copy(messages = msgs.dropLastWhile { m -> m.role == "assistant" }) }
        send(lastUser.content)
    }

    /** Stage (e): toggle thinking mode for reasoning-capable models. */
    fun setThinking(on: Boolean) = _state.update { it.copy(thinking = on) }

    /** Stage (c): stage a media file for the next turn. */
    fun setAttachment(path: String, isAudio: Boolean = false) =
        _state.update { it.copy(attachment = path, attachmentIsAudio = isAudio) }

    fun clearAttachment() = _state.update { it.copy(attachment = null, attachmentIsAudio = false) }

    /** Stage (b): set the number of layers to offload (0 = CPU only). */
    fun setGpuLayers(layers: Int) {
        val v = layers.coerceAtLeast(0)
        _state.update { it.copy(gpuLayers = v) }
        prefs.edit().putInt(KEY_GPU_LAYERS, v).apply()
    }

    // --- embedded server (stage d) -----------------------------------------

    fun setServer(running: Boolean, port: Int = _state.value.serverPort) =
        _state.update { it.copy(serverRunning = running, serverPort = port) }

    fun stop() = LlamaBridge.cancel()

    override fun onCleared() {
        runCatching { LlamaBridge.free() }
        super.onCleared()
    }

    companion object {
        private const val SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's phone."
        private const val MAX_TOKENS = 512

        /** Sentinel meaning "offload as many layers as the backend can hold". */
        const val GPU_LAYERS_ALL = 99

        private const val KEY_GPU_LAYERS = "gpu_layers"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_HISTORY = "chat_history"
    }
}