package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v5: settings — GPU offload, chat defaults, the local API server, model folders
 * and status. GPU layers default to 0 (CPU) so the app can never crash on load;
 * the loader also retries CPU-only when a GPU load fails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var port by remember { mutableStateOf(state.serverPort.toString()) }
    var token by remember { mutableStateOf("") }
    var bindAll by remember { mutableStateOf(LocalServer.bindAll) }
    var serverOn by remember { mutableStateOf(ServerState.running) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text("Settings") }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Performance", fontWeight = FontWeight.Bold)
                    Text("GPU layers: ${state.gpuLayers}  (0 = CPU only)",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = state.gpuLayers.toFloat(),
                        onValueChange = { vm.setGpuLayers(it.toInt()) },
                        valueRange = 0f..99f
                    )
                    Text("Some Vulkan drivers crash when many layers are offloaded. If the app " +
                        "closes while loading, set this back to 0. A failed GPU load auto-retries on CPU.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Chat defaults", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Thinking mode", Modifier.weight(1f))
                        Switch(checked = state.thinking, onCheckedChange = { vm.setThinking(it) })
                    }
                    Text("Off appends /no_think and hides any reasoning the model still emits.",
                        style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Web search", Modifier.weight(1f))
                        Switch(checked = state.webSearch, onCheckedChange = { vm.setWebSearch(it) })
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Local API server", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (serverOn) "Running on 127.0.0.1:$port" else "Stopped",
                            Modifier.weight(1f))
                        Switch(checked = serverOn, onCheckedChange = { on ->
                            if (on) {
                                val p = port.toIntOrNull() ?: 8080
                                ServerService.start(context, p, bindAll, token.ifBlank { null })
                                vm.setServer(true, p)
                            } else {
                                ServerService.stop(context)
                                vm.setServer(false)
                            }
                            serverOn = on
                        })
                    }
                    OutlinedTextField(
                        value = port, onValueChange = { s -> port = s.filter { it.isDigit() } },
                        label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("Bearer token (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Expose on LAN (0.0.0.0)", Modifier.weight(1f))
                        Switch(checked = bindAll, onCheckedChange = { bindAll = it })
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Model folders", fontWeight = FontWeight.Bold)
                    ModelRepository.defaultRoots().forEach {
                        Text("• $it", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.refreshModelsNow() }) {
                        Icon(Icons.Default.Refresh, "Rescan")
                        Spacer(Modifier.width(6.dp))
                        Text("Rescan")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Status", fontWeight = FontWeight.Bold)
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    if (state.modelInfo.isNotBlank()) {
                        Text(state.modelInfo, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("arm64-v8a · minSdk 28 · Hermes Llama",
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedButton(onClick = { vm.newChat() }, modifier = Modifier.fillMaxWidth()) {
                Text("Start a new chat")
            }
        }
    }
}
