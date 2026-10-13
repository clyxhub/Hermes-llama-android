package com.hermes.llama

import android.content.Context
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

private const val KEY_LAN = "mdns_lan"
private const val KEY_MDNS_HOST = "mdns_host"
private const val KEY_KV_QUANT = "kv_quant"

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
    val prefs = remember { context.getSharedPreferences("hermes_llama", Context.MODE_PRIVATE) }
    var lanOn by remember { mutableStateOf(prefs.getBoolean(KEY_LAN, false)) }
    var hostText by remember {
        mutableStateOf(prefs.getString(KEY_MDNS_HOST, MdnsResponder.DEFAULT_HOST) ?: MdnsResponder.DEFAULT_HOST)
    }
    var kvMode by remember { mutableStateOf(prefs.getInt(KEY_KV_QUANT, LlamaBridge.KV_Q8_0)) }

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
                SwitchRow(
                    "GPU acceleration (auto)",
                    if (state.gpuAvailable) "Offload every layer the GPU can hold"
                    else "No GPU backend detected — running on CPU",
                    state.gpuAuto
                ) { vm.setGpuAuto(it) }
            }
            item {
                SettingRow(
                    title = "GPU Layers",
                    subtitle = if (state.gpuAuto) "Auto" + (if (state.gpuAvailable) " (all layers)" else " (CPU only)")
                    else if (state.gpuLayers == 0) "0 - CPU only"
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
            item {
                SettingRow(
                    "Context Size",
                    (state.preset?.contextSize?.toString() ?: "not loaded") + " · defaults from the loaded model",
                    null
                )
            }
            item {
                SettingRow(
                    "KV cache quantization",
                    when (kvMode) {
                        LlamaBridge.KV_Q4_0 -> "Q4_0 - smallest, applies on next model load"
                        LlamaBridge.KV_Q8_0 -> "Q8_0 - halves context memory (next load)"
                        else -> "Off (f16) - full precision"
                    }
                ) {
                    TextButton(onClick = {
                        kvMode = when (kvMode) {
                            LlamaBridge.KV_Q8_0 -> LlamaBridge.KV_Q4_0
                            LlamaBridge.KV_Q4_0 -> LlamaBridge.KV_OFF
                            else -> LlamaBridge.KV_Q8_0
                        }
                        prefs.edit().putInt(KEY_KV_QUANT, kvMode).apply()
                    }) { Text(kvLabel(kvMode)) }
                }
            }
            item {
                SettingRow(
                    "Device Selection",
                    when {
                        state.gpuAuto && state.gpuAvailable -> "GPU (auto, all layers)"
                        state.gpuLayers > 0 -> "GPU x${state.gpuLayers}"
                        else -> "CPU only - no hardware accelerators detected"
                    },
                    null
                )
            }

            // Thinking / web-search live in the chat composer (single source of
            // truth) — deliberately NOT duplicated here.

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
                        ServerService.start(context, p, lanOn, null)
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
                SwitchRow(
                    "LAN access (mDNS)",
                    if (lanOn) "Reachable at ${MdnsResponder.url(state.serverPort)}"
                    else "Reachable only on this device (localhost)",
                    lanOn
                ) { on ->
                    prefs.edit().putBoolean(KEY_LAN, on).apply()
                    lanOn = on
                    if (serverOn) ServerService.start(context, portText.toIntOrNull() ?: 8080, on, null)
                }
            }
            item {
                SettingRow(
                    "Server name",
                    "${MdnsResponder.hostname()} · ${if (MdnsResponder.isRunning) "advertising" else "mDNS (.local)"}"
                ) {
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { v ->
                            hostText = v
                            prefs.edit().putString(KEY_MDNS_HOST, v).apply()
                            if (MdnsResponder.isRunning) MdnsResponder.start(context, v, state.serverPort)
                        },
                        singleLine = true,
                        modifier = Modifier.width(170.dp)
                    )
                }
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
            item { SettingRow("CALISTO local ai", "0.1.0 - local llama.cpp on-device", null) }
        }
    }
}

/** Short label for a KV-cache quantization mode. */
private fun kvLabel(mode: Int): String = when (mode) {
    LlamaBridge.KV_Q4_0 -> "Q4_0"
    LlamaBridge.KV_Q8_0 -> "Q8_0"
    else -> "Off"
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