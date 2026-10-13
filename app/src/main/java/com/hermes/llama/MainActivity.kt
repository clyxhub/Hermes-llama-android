package com.hermes.llama

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ChatScreen() } }
    }
}

/** Copies a content:// URI into app-private storage and returns the real path. */
private fun materialise(context: android.content.Context, uri: Uri, subdir: String = "models"): String? =
    runCatching {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
        val dir = File(context.filesDir, subdir).apply { mkdirs() }
        val dst = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output, 1 shl 20) }
        }
        dst.absolutePath
    }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { materialise(context, it, "models")?.let(vm::loadModel) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { materialise(context, it, "media")?.let { p -> vm.setAttachment(p, false) } }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { materialise(context, it, "media")?.let { p -> vm.setAttachment(p, true) } }
    }

    // Auto-scroll as tokens stream in.
    LaunchedEffect(state.messages.size, state.streaming) {
        val target = state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Llama") },
                actions = {
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                    }
                    IconButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Load model")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    // Pending media attachment chip (stage c).
                    state.attachment?.let { path ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📎 ${path.substringAfterLast('/')}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.clearAttachment() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Remove attachment")
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.capabilities.vision) {
                            IconButton(onClick = { imagePicker.launch("image/*") }) {
                                Icon(Icons.Default.Add, contentDescription = "Attach image")
                            }
                        }
                        if (state.capabilities.audio) {
                            IconButton(onClick = { audioPicker.launch("audio/*") }) {
                                Icon(Icons.Default.Add, contentDescription = "Attach audio")
                            }
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") },
                            enabled = state.preset != null && !state.busy,
                            maxLines = 4
                        )
                        Spacer(Modifier.width(8.dp))
                        if (state.busy) {
                            IconButton(onClick = { vm.stop() }) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    vm.send(input, state.attachment)
                                    input = ""
                                },
                                enabled = state.preset != null && (input.isNotBlank() || state.attachment != null)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // --- model / status card ---------------------------------------
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (state.modelInfo.isNotBlank()) {
                        Text(state.modelInfo, style = MaterialTheme.typography.labelSmall)
                    }

                    // Stage (b): Vulkan GPU offload toggle. Applies on next load.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("GPU offload (Vulkan)", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (state.gpuLayers > 0) "offloading ${state.gpuLayers} layers"
                                else "CPU only · applies on next model load",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Switch(
                            checked = state.gpuLayers > 0,
                            onCheckedChange = { on ->
                                vm.setGpuLayers(if (on) ChatViewModel.GPU_LAYERS_ALL else 0)
                            }
                        )
                    }

                    // Stage (e): thinking mode toggle.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Thinking mode", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "show reasoning trace" + if (state.preset?.reasoning == true) " · auto-on" else "",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Switch(checked = state.thinking, onCheckedChange = { vm.setThinking(it) })
                    }

                    if (state.preset != null) {
                        Text(
                            "${state.preset!!.family} · temp ${state.preset!!.temperature} · " +
                                "capabilities: ${state.capabilities.describe()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // --- conversation ----------------------------------------------
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { msg -> MessageBubble(msg, state.thinking) }
                if (state.streaming.isNotEmpty()) {
                    item { MessageBubble(ChatMessage("assistant", state.streaming), state.thinking) }
                }
                if (state.messages.isEmpty() && state.preset == null) {
                    item {
                        Text(
                            "Tap + to load a .gguf model from storage, or open Settings for the " +
                                "in-app model browser / cloud providers.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stage (e): when thinking mode is on, an assistant message containing a
 * `…` block renders the trace in a collapsible, distinct style.
 */
/**
 * Qwen3-style chain-of-thought delimiters, built with unicode escapes so this
 * source file never contains raw angle brackets.
 */
private const val THINK_OPEN = "\u003Cthink\u003E"
private const val THINK_CLOSE = "\u003C/think\u003E"

/** True when [content] carries a chain-of-thought trace (Qwen3-style tags). */
private fun hasThinking(content: String): Boolean =
    content.contains(THINK_OPEN) || content.contains(THINK_CLOSE)

/**
 * Split an assistant message into (reasoning trace, final answer). Handles the
 * common case where the chat template already emitted the opening ` thinking`
 * into the prompt, so only the closing tag appears in the generated text.
 */
private fun splitThinking(content: String): Pair<String, String> {
    val hasOpen = content.contains(THINK_OPEN)
    val hasClose = content.contains(THINK_CLOSE)
    val afterOpen = if (hasOpen) content.substringAfter(THINK_OPEN) else content
    val trace = afterOpen.substringBefore(THINK_CLOSE).trim()
    val answer = if (hasClose) afterOpen.substringAfter(THINK_CLOSE).trim() else ""
    return trace to answer
}

@Composable
private fun MessageBubble(msg: ChatMessage, thinking: Boolean) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val parsed = if (!isUser) splitThinking(msg.content) else ("" to msg.content)
        val trace = parsed.first
        val answer = parsed.second
        if (!isUser && thinking && hasThinking(msg.content)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    if (trace.isNotEmpty()) {
                        Text("💭 reasoning", style = MaterialTheme.typography.labelSmall)
                        Text(trace, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(answer.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    msg.content.ifEmpty { "…" },
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var port by remember { mutableStateOf(state.serverPort.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var bindAll by remember { mutableStateOf(false) }

    // HuggingFace portal state (stage h path B)
    var hfQuery by remember { mutableStateOf("") }
    var hfRepos by remember { mutableStateOf<List<HuggingFaceClient.Repo>>(emptyList()) }
    var hfFiles by remember { mutableStateOf<List<HuggingFaceClient.RepoFile>>(emptyList()) }
    var hfActiveRepo by remember { mutableStateOf("") }
    var hfStatus by remember { mutableStateOf("") }

    // Cloud (stage h path C)
    var providerId by remember { mutableStateOf(CloudProviders.ALL.first().id) }
    var cloudKey by remember { mutableStateOf("") }
    var cloudModel by remember { mutableStateOf("") }
    var cloudStatus by remember { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Clear, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- embedded server (stage d) -------------------------------
            Text("Embedded server (OpenAI-compatible)", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run server", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = state.serverRunning,
                    onCheckedChange = { on ->
                        if (on) {
                            val p = port.toIntOrNull() ?: 8080
                            LocalServer.apiKey = apiKey.takeIf { it.isNotBlank() }
                            LocalServer.bindAll = bindAll
                            vm.setServer(true, p)
                            ServerService.start(context, p, bindAll, apiKey.takeIf { it.isNotBlank() })
                        } else {
                            ServerService.stop(context)
                            vm.setServer(false)
                        }
                    }
                )
            }
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text("Port") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("Bearer key (optional)") }, modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bind LAN (0.0.0.0)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = bindAll, onCheckedChange = { bindAll = it })
            }
            Text(
                "Client base URL: http://127.0.0.1:${port.ifBlank { "8080" }}/v1",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace
            )

            HorizontalDivider()

            // ---- HuggingFace portal (stage h path B) ---------------------
            Text("Model browser (HuggingFace)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = hfQuery, onValueChange = { hfQuery = it },
                label = { Text("Search GGUF models") }, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    hfStatus = "Searching…"; hfFiles = emptyList()
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { HuggingFaceClient.search(hfQuery) }
                        hfRepos = res
                        hfStatus = if (res.isEmpty()) "No results" else "${res.size} repos"
                    }
                }, enabled = hfQuery.isNotBlank()
            ) { Text("Search") }
            if (hfStatus.isNotBlank()) Text(hfStatus, style = MaterialTheme.typography.labelSmall)

            hfRepos.take(10).forEach { repo ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(repo.id, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        hfActiveRepo = repo.id
                        hfStatus = "Listing ${repo.id}…"
                        scope.launch {
                            val files = withContext(Dispatchers.IO) { HuggingFaceClient.files(repo.id) }
                            hfFiles = files
                            hfStatus = "${files.size} .gguf files"
                        }
                    }) { Text("Files") }
                }
            }

            hfFiles.take(12).forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                        Text("%.0f MB".format(f.size / 1_000_000.0), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        hfStatus = "Downloading…"
                        scope.launch {
                            val dir = File(context.filesDir, "models")
                            val out = withContext(Dispatchers.IO) {
                                HuggingFaceClient.download(hfActiveRepo, f.path, dir) { d, t ->
                                    hfStatus = "%.0f/%.0f MB".format(d / 1_000_000.0, t / 1_000_000.0)
                                }
                            }
                            if (out != null) { hfStatus = "Saved ${out.name}"; vm.loadModel(out.absolutePath) }
                            else hfStatus = "Download failed"
                        }
                    }) { Text("Get") }
                }
            }

            HorizontalDivider()

            // ---- Cloud providers (stage h path C) ------------------------
            Text("Cloud providers (BYOK)", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { providerMenu = true }, Modifier.fillMaxWidth()) {
                    Text(CloudProviders.byId(providerId)?.name ?: "Provider")
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    CloudProviders.ALL.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = {
                            providerId = p.id; providerMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = cloudKey, onValueChange = { cloudKey = it },
                label = { Text("API key") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cloudModel, onValueChange = { cloudModel = it },
                label = { Text("Model id") }, modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                cloudStatus = "Fetching models…"
                scope.launch {
                    val p = CloudProviders.byId(providerId)!!
                    val models = withContext(Dispatchers.IO) { CloudEngine.models(p, cloudKey) }
                    cloudStatus = if (models.isEmpty()) "No models (check key)" else models.take(20).joinToString(", ")
                }
            }, enabled = cloudKey.isNotBlank()) { Text("Fetch model list") }
            if (cloudStatus.isNotBlank()) {
                Text(cloudStatus, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}