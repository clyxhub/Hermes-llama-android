package com.hermes.llama

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the UI renders. */
data class UiState(
    val chats: List<Chat> = emptyList(),
    val current: Chat? = null,
    val streaming: String = "",
    val busy: Boolean = false,
    val status: String = "No model loaded",
    val modelInfo: String = "",
    val models: List<ModelRepository.Entry> = emptyList(),
    val currentModelId: String? = null,
    val preset: PresetEngine.Preset? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val gpuLayers: Int = 0,
    val threads: Int = 4,
    val thinking: Boolean = false,
    val webSearch: Boolean = false,
    val attachment: String? = null,
    val attachmentIsAudio: Boolean = false,
    val serverRunning: Boolean = false,
    val serverPort: Int = 8080,
    val providerSelectionVersion: Int = 0
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("hermes_llama", Context.MODE_PRIVATE)
    private val chats: MutableList<Chat> = ChatStore.load(application)
    private var currentId: String? = prefs.getString(KEY_CURRENT, null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (chats.isEmpty()) ChatStore.newChat(chats)
        if (currentId == null || chats.none { it.id == currentId }) currentId = chats.first().id
        // Crash recovery: a GPU load that never completed means the process was
        // killed mid-offload (Vulkan driver crash). Self-heal to CPU so the app
        // cannot crash-loop on a bad GPU configuration.
        var gpuPref = prefs.getInt(KEY_GPU_LAYERS, 0)
        if (prefs.getBoolean(KEY_GPU_ATTEMPT, false)) {
            Log.w(TAG, "previous GPU load did not complete — disabling GPU offload (CPU mode)")
            gpuPref = 0
            prefs.edit().putInt(KEY_GPU_LAYERS, 0).putBoolean(KEY_GPU_ATTEMPT, false).apply()
        }
        _state.value = UiState(
            chats = ordered(),
            current = current(),
            gpuLayers = gpuPref,
            thinking = prefs.getBoolean(KEY_THINKING, false),
            webSearch = prefs.getBoolean(KEY_WEB, false),
            status = "No model loaded"
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LlamaBridge.init()
                Log.i(TAG, "system: " + LlamaBridge.systemInfo())
            }
            refreshModels()
            // Quick win: remember the last-used model and auto-load it.
            val last = prefs.getString(KEY_LAST_MODEL, null)
            if (last != null) {
                val entry = _state.value.models.firstOrNull { it.path == last }
                if (entry != null) loadEntry(entry)
            }
        }
    }

    // --- chat list ----------------------------------------------------------

    private fun ordered(): List<Chat> =
        chats.sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updatedAt })

    private fun current(): Chat? = chats.firstOrNull { it.id == currentId }

    private fun save() {
        ChatStore.save(getApplication(), chats)
        prefs.edit().putString(KEY_CURRENT, currentId).apply()
    }

    private fun push() = _state.update { it.copy(chats = ordered(), current = current()) }

    fun newChat() {
        val c = ChatStore.newChat(chats)
        currentId = c.id
        save(); push()
    }

    fun selectChat(id: String) { currentId = id; save(); push() }

    fun renameChat(id: String, title: String) { ChatStore.rename(chats, id, title); save(); push() }

    fun deleteChat(id: String) {
        ChatStore.delete(chats, id)
        if (chats.isEmpty()) ChatStore.newChat(chats)
        if (currentId == id) currentId = chats.first().id
        save(); push()
    }

    fun togglePin(id: String) { ChatStore.togglePin(chats, id); save(); push() }

    fun exportCurrent(): String = current()?.let { ChatStore.exportMarkdown(it) } ?: ""

    // --- model library ------------------------------------------------------

    private fun roots(): List<String> =
        prefs.getStringSet(ROOTS, null)?.toList()?.takeIf { it.isNotEmpty() }
            ?: ModelRepository.defaultRoots()

    private suspend fun refreshModels() = withContext(Dispatchers.IO) {
        val list = runCatching { ModelRepository.all(getApplication(), roots()) }.getOrDefault(emptyList())
        _state.update { it.copy(models = list) }
    }

    fun refreshModelsNow() {
        viewModelScope.launch { refreshModels() }
    }

    /** Load a library entry: local gguf (+ auto mmproj) or a cloud model. */
    fun loadEntry(entry: ModelRepository.Entry) {
        if (!entry.isLocal) {
            _state.update {
                it.copy(
                    currentModelId = entry.id,
                    status = "Cloud: ${entry.providerName} · ${entry.name}",
                    modelInfo = "${entry.providerName} · ${entry.modelId}",
                    preset = PresetEngine.Preset("Cloud", 0.7f, 0.9f, 40, 8192, "none"),
                    capabilities = ModelCapabilities(embeddings = false),
                    busy = false
                )
            }
            prefs.edit().putString(KEY_LAST_MODEL, null).apply()
            return
        }
        val path = entry.path ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(status = "Loading…", busy = true) }
            val preset0 = PresetEngine.detect(entry.name)
            val cfg = AutoTuner.recommend(getApplication(), path, false)
            var gpu = _state.value.gpuLayers
            Log.i(TAG, "loadModel path=$path ctx=${preset0.contextSize} threads=${cfg.threads} ngl=$gpu")

            // Mark the attempt in-flight: if the process dies here, init() will
            // detect this flag on next launch and disable GPU offload.
            if (gpu > 0) prefs.edit().putBoolean(KEY_GPU_ATTEMPT, true).apply()
            var rc = runCatching { LlamaBridge.loadModel(path, preset0.contextSize, cfg.threads, gpu) }.getOrDefault(-99)
            if (rc != 0 && gpu > 0) {
                Log.w(TAG, "GPU load failed rc=$rc — retrying CPU-only")
                gpu = 0
                rc = runCatching { LlamaBridge.loadModel(path, preset0.contextSize, cfg.threads, 0) }.getOrDefault(-99)
            }
            if (rc != 0) {
                Log.e(TAG, "load failed rc=$rc")
                prefs.edit().putBoolean(KEY_GPU_ATTEMPT, false).apply()
                _state.update { it.copy(busy = false, status = "Load failed (code $rc)") }
                return@launch
            }
            prefs.edit().putBoolean(KEY_GPU_ATTEMPT, false).apply()

            val preset = PresetEngine.refine(preset0)
            val info = PresetEngine.describeModel()

            var projectorOk = false
            entry.projector?.let { p ->
                projectorOk = runCatching { LlamaBridge.loadProjector(p, cfg.threads) }.getOrDefault(-1) == 0
            }
            val caps = ModelCapabilities.ofLoadedModel(reasoning = preset.reasoning)
            val backend = if (gpu > 0) "GPUx$gpu" else "CPU"

            AutoTuner.record(getApplication(), path, AutoTuner.Config(cfg.threads, gpu))
            prefs.edit().putString(KEY_LAST_MODEL, path).apply()

            // Publish the loaded model to the embedded server.
            LocalServer.updateModel(
                LocalServer.ModelSnapshot(
                    id = entry.name, contextSize = preset.contextSize,
                    capabilities = caps, chatTemplate = LlamaBridge.chatTemplate()
                )
            )

            _state.update {
                it.copy(
                    currentModelId = entry.id, busy = false, preset = preset, capabilities = caps,
                    threads = cfg.threads, thinking = preset.reasoning,
                    modelInfo = "$info · $backend · ${cfg.threads} threads" + if (projectorOk) " · projector ok" else "",
                    status = "Loaded ${preset.family} · ctx ${LlamaBridge.contextSize()} · $backend"
                )
            }
        }
    }

    // --- generation ---------------------------------------------------------

    private fun buildPrompt(preset: PresetEngine.Preset, turns: List<Msg>, thinking: Boolean): String {
        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (SYSTEM_PROMPT.isNotBlank()) { roles += "system"; contents += SYSTEM_PROMPT }
        turns.forEachIndexed { i, m ->
            var c = m.content
            // Qwen3 soft switch: "/no_think" in the final user turn disables reasoning.
            if (!thinking && m.role == "user" && i == turns.lastIndex) c = "$c /no_think"
            roles += m.role; contents += c
        }
        val tmpl = if (preset.chatTemplate == "gguf" || preset.chatTemplate == "none") "" else preset.chatTemplate
        val viaModel = runCatching { LlamaBridge.applyChatTemplate(tmpl, roles, contents, true) }.getOrNull()
        return viaModel ?: PresetEngine.applyTemplate(preset, SYSTEM_PROMPT, turns.lastOrNull()?.content ?: "")
    }

    /** Remove a chain-of-thought trace when thinking mode is off. */
    private fun stripThinking(text: String): String {
        val close = "\u003C/think\u003E"
        return if (text.contains(close)) text.substringAfter(close).trim() else text
    }

    fun send(input: String, mediaPath: String? = null) {
        val chat = current() ?: return
        val s = _state.value
        if ((input.isBlank() && mediaPath == null) || s.busy) return

        val text = input.ifBlank { "Describe this." }
        chat.messages.add(Msg("user", text))
        ChatStore.autoTitle(chat)
        chat.updatedAt = System.currentTimeMillis()
        save()
        _state.update {
            it.copy(current = current(), chats = ordered(), streaming = "", busy = true,
                status = "Generating…", attachment = null, attachmentIsAudio = false)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val started = System.currentTimeMillis()
            val s2 = _state.value

            if (s2.currentModelId?.startsWith("cloud:") == true) {
                runCloud(chat, s2, sb)
            } else {
                val preset = s2.preset
                if (preset != null) {
                    var query = text
                    if (s2.webSearch) {
                        val hits = runCatching { WebSearch.search(text) }.getOrDefault(emptyList())
                        val block = WebSearch.contextBlock(text, hits)
                        if (block.isNotBlank()) query = block + "\n\nQuestion: " + text
                    }
                    val turns = chat.messages.dropLast(1) + Msg("user", query)
                    val prompt = buildPrompt(preset, turns, s2.thinking)
                    runCatching {
                        if (mediaPath != null) {
                            LlamaBridge.generateWithMedia(prompt, mediaPath, MAX_TOKENS, preset.temperature, preset.topP) { t ->
                                sb.append(t); _state.update { it.copy(streaming = sb.toString()) }
                            }
                        } else {
                            LlamaBridge.generate(prompt, MAX_TOKENS, preset.temperature, preset.topP) { t ->
                                sb.append(t); _state.update { it.copy(streaming = sb.toString()) }
                            }
                        }
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val finalText = if (s2.thinking) sb.toString() else stripThinking(sb.toString())
            val speed = if (finalText.length > 0) " · %.1fs".format(elapsed / 1000.0) else ""

            withContext(Dispatchers.Main) {
                val c = current()
                if (c != null) {
                    c.messages.add(Msg("assistant", finalText))
                    c.updatedAt = System.currentTimeMillis()
                }
                save()
                _state.update {
                    it.copy(current = current(), chats = ordered(), streaming = "", busy = false,
                        status = "Ready$speed")
                }
            }
        }
    }

    private fun runCloud(chat: Chat, s: UiState, sb: StringBuilder) {
        val pid = s.currentModelId?.removePrefix("cloud:")?.substringBefore("::") ?: return
        val model = s.currentModelId?.substringAfter("::") ?: return
        val provider = CloudProviders.byId(pid) ?: return
        val key = ProviderStore.keysFor(getApplication(), pid).firstOrNull()?.apiKey ?: ""
        if (key.isBlank()) {
            sb.append("[no API key for ${provider.name}]")
            return
        }
        val msgs = chat.messages.dropLast(0).map { it.role to it.content }
        runCatching {
            CloudEngine.chat(provider, key, model, msgs, 0.7f) { t ->
                sb.append(t); _state.update { it.copy(streaming = sb.toString()) }
            }
        }
    }

    fun regenerate() {
        val c = current() ?: return
        val lastUser = c.messages.lastOrNull { it.role == "user" } ?: return
        c.messages.removeAll { it.role == "assistant" && c.messages.indexOf(it) > c.messages.indexOf(lastUser) }
        send(lastUser.content)
    }

    fun editAndResend(index: Int, newText: String) {
        val c = current() ?: return
        if (index !in c.messages.indices) return
        c.messages.subList(index, c.messages.size).clear()
        send(newText)
    }

    fun deleteMessage(index: Int) {
        val c = current() ?: return
        if (index in c.messages.indices) { c.messages.removeAt(index); save(); push() }
    }

    // --- toggles ------------------------------------------------------------

    fun setThinking(on: Boolean) { prefs.edit().putBoolean(KEY_THINKING, on).apply(); _state.update { it.copy(thinking = on) } }
    fun setWebSearch(on: Boolean) { prefs.edit().putBoolean(KEY_WEB, on).apply(); _state.update { it.copy(webSearch = on) } }
    fun setAttachment(path: String, isAudio: Boolean = false) = _state.update { it.copy(attachment = path, attachmentIsAudio = isAudio) }
    fun clearAttachment() = _state.update { it.copy(attachment = null, attachmentIsAudio = false) }
    fun setServer(running: Boolean, port: Int = _state.value.serverPort) = _state.update { it.copy(serverRunning = running, serverPort = port) }
    fun bumpProviderSelection() = _state.update { it.copy(providerSelectionVersion = it.providerSelectionVersion + 1) }

    fun setGpuLayers(layers: Int) {
        val v = layers.coerceIn(0, 200)
        prefs.edit().putInt(KEY_GPU_LAYERS, v).apply()
        _state.update { it.copy(gpuLayers = v) }
    }

    fun stop() = LlamaBridge.cancel()

    /** Headless repro hook (debug): auto-load a model with a given ngl for log capture. */
    fun debugLoad(path: String, ngl: Int, ctx: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LlamaBridge.init() }
            Log.i(TAG, "debugLoad path=$path ngl=$ngl ctx=$ctx")
            val rc = runCatching { LlamaBridge.loadModel(path, ctx, 4, ngl) }.getOrDefault(-99)
            Log.i(TAG, "debugLoad rc=$rc")
        }
    }

    override fun onCleared() {
        runCatching { LlamaBridge.free() }
        super.onCleared()
    }

    companion object {
        const val TAG = "HermesLlama"
        private const val SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's phone."
        private const val MAX_TOKENS = 512
        const val GPU_LAYERS_ALL = 99
        private const val KEY_GPU_LAYERS = "gpu_layers"
        private const val KEY_GPU_ATTEMPT = "gpu_attempt_inflight"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_CURRENT = "current_chat"
        private const val KEY_THINKING = "thinking"
        private const val KEY_WEB = "web_search"
        private const val ROOTS = "model_roots"
    }
}