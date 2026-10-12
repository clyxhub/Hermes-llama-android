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
    val preset: PresetEngine.Preset? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val busy: Boolean = false,
    val status: String = "No model loaded",
    val systemInfo: String = "",
    /** Layers to offload to the Vulkan GPU backend; 0 = CPU only. */
    val gpuLayers: Int = 0
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("hermes_llama", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 0)))
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

    /** Load a GGUF off local storage, offloading [UiState.gpuLayers] layers to Vulkan. */
    fun loadModel(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(status = "Loading…", busy = true) }
            val preset = PresetEngine.detect(path.substringAfterLast('/'))
            val nGpuLayers = _state.value.gpuLayers
            val rc = runCatching { LlamaBridge.loadModel(path, preset.contextSize, 4, nGpuLayers) }
                .getOrDefault(-99)

            _state.update {
                if (rc == 0) it.copy(
                    modelPath = path,
                    preset = preset,
                    busy = false,
                    status = "Loaded ${preset.family} · ctx ${LlamaBridge.contextSize()}" +
                        if (nGpuLayers > 0) " · GPU×$nGpuLayers" else " · CPU"
                ) else it.copy(busy = false, status = "Load failed (code $rc)")
            }
            if (rc == 0) {
                prefs.edit().putString(KEY_LAST_MODEL, path).apply()
            }
        }
    }

    fun send(input: String) {
        val s = _state.value
        val preset = s.preset ?: return
        if (input.isBlank() || s.busy) return

        val history = s.messages + ChatMessage("user", input)
        _state.update {
            it.copy(messages = history, streaming = "", busy = true, status = "Generating…")
        }

        viewModelScope.launch(Dispatchers.IO) {
            val prompt = PresetEngine.applyTemplate(preset, SYSTEM_PROMPT, input)
            val sb = StringBuilder()
            val startedAt = System.currentTimeMillis()

            runCatching {
                LlamaBridge.generate(prompt, 512, preset.temperature, preset.topP) { token ->
                    sb.append(token)
                    // Publish partial text on each token so the UI streams.
                    _state.update { it.copy(streaming = sb.toString()) }
                }
            }

            // Quick win: tokens/sec readout for the finished response.
            val elapsedMs = System.currentTimeMillis() - startedAt
            val tokPerSec = if (elapsedMs > 0) sb.length * 1000.0 / elapsedMs / 4.0 else 0.0
            val speed = if (tokPerSec >= 1.0) " · %.1f tok/s".format(tokPerSec) else ""

            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", sb.toString()),
                        streaming = "",
                        busy = false,
                        status = "Ready$speed"
                    )
                }
            }
        }
    }

    /**
     * Set how many layers to offload to the Vulkan backend (0 = CPU only).
     * Takes effect on the next [loadModel] call.
     */
    fun setGpuLayers(layers: Int) {
        val v = layers.coerceAtLeast(0)
        _state.update { it.copy(gpuLayers = v) }
        // Persist so the choice survives app restarts (the toggle only takes
        // effect at model-load time, so it must still be set on next launch).
        prefs.edit().putInt(KEY_GPU_LAYERS, v).apply()
    }

    fun stop() = LlamaBridge.cancel()

    override fun onCleared() {
        runCatching { LlamaBridge.free() }
        super.onCleared()
    }

    companion object {
        private const val SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's phone."
        /** Sentinel meaning "offload as many layers as the backend can hold". */
        const val GPU_LAYERS_ALL = 99

        /** SharedPreferences key for the persisted GPU-layers setting. */
        private const val KEY_GPU_LAYERS = "gpu_layers"

        /** SharedPreferences key for the last successfully loaded model path. */
        private const val KEY_LAST_MODEL = "last_model_path"
    }
}
