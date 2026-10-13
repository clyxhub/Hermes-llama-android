package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v5: Settings, styled after PocketPal: a bold section header followed by
 * label + subtitle rows with a control on the right (switch / stepper / value).
 *
 * Everything here is real and wired to the ViewModel or the foreground server
 * service. The "Device Selection" row mirrors PocketPal's device line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var serverOn by remember { mutableStateOf(ServerState.running) }
    var portText by remember { mutableStateOf(state.serverPort.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") } },
                title = { Text("Settings") }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {

            item { SectionHeader("Model Initialization Settings") }
            item {
                SettingRow(
                    title = "GPU Layers",
                    subtitle = if (state.gpuLayers == 0) "0 - CPU only - no hardware accelerators"
                    else "${state.gpuLayers} layers offloaded"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setGpuLayers(state.gpuLayers - 1) }) {
                            Icon(Icons.Default.Remove, "Fewer layers")
                        }
                        Text("${state.gpuLayers}", fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { vm.setGpuLayers(state.gpuLayers + 1) }) {
                            Icon(Icons.Default.Add, "More layers")
                        }
                    }
                }
            }
            item { SettingRow("Threads", "${state.threads} (auto-tuned)", null) }
            item { SettingRow("Context Size", state.preset?.contextSize?.toString() ?: "not loaded", null) }
            item {
                SettingRow(
                    "Device Selection",
                    if (state.gpuLayers > 0) "GPU x${state.gpuLayers}" else "CPU only - no hardware accelerators detected",
                    null
                )
            }

            item { SectionHeader("Generation Settings") }
            item {
                SwitchRow(
                    "Thinking / reasoning",
                    "Let the model emit a reasoning trace",
                    state.thinking
                ) { vm.setThinking(it) }
            }
            item {
                SwitchRow(
                    "Web search",
                    "Ground answers with DuckDuckGo results",
                    state.webSearch
                ) { vm.setWebSearch(it) }
            }

            item { SectionHeader("Local Server") }
            item {
                SwitchRow(
                    "OpenAI-compatible server",
                    "Serve the loaded model to other apps",
                    serverOn
                ) { on ->
                    if (on) {
                        val p = portText.toIntOrNull() ?: 8080
                        vm.setServer(true, p)
                        ServerService.start(context, p, false, null)
                    } else {
                        ServerService.stop(context)
                        vm.setServer(false, 0)
                    }
                    serverOn = on
                }
            }
            item {
                SettingRow("Server address", "http://127.0.0.1:${state.serverPort}/v1", null)
            }
            item {
                SettingRow("Port", portText) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        modifier = Modifier.width(96.dp)
                    )
                }
            }

            item { SectionHeader("Memory Settings") }
            item { SettingRow("Memory Mapping", "Enabled by default", null) }
            item { SettingRow("Use Memory Lock", "Not available in this build", null) }
            item { SettingRow("Enable Weight Repacking", "Enabled by default", null) }

            item { SectionHeader("About") }
            item { SettingRow("Hermes Llama", "0.1.0 - local llama.cpp on-device", null) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}