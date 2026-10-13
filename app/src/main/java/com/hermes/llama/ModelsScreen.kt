package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v5: the in-app model library. Every local GGUF appears here WITH its
 * auto-paired mmproj, plus every cloud model the user selected in Providers.
 * Tap a row to load it — no repeated file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val state by vm.state.collectAsState()
    val local = state.models.filter { it.isLocal }
    val cloud = state.models.filter { !it.isLocal }
    val withProj = local.count { it.projector != null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text("Models") },
                actions = {
                    IconButton(onClick = { vm.refreshModelsNow() }) { Icon(Icons.Default.Refresh, "Rescan") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ModelsHeader("On device", "$withProj of ${local.size} paired with a projector (mmproj)") }
            if (local.isEmpty()) {
                item {
                    Text(
                        "No .gguf found.\nScanned: " + ModelRepository.defaultRoots().joinToString("\n") +
                            "\n\nDownload models from the HuggingFace section, then tap Rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(local, key = { it.id }) { e ->
                ModelRow(
                    name = e.name, subtitle = e.subtitle,
                    size = ModelRepository.formatSize(e.sizeBytes),
                    projector = e.projector, cloud = false,
                    loaded = state.currentModelId == e.id,
                    busy = state.busy,
                    onLoad = { vm.loadEntry(e) }
                )
            }

            if (cloud.isNotEmpty()) {
                item { ModelsHeader("Cloud (from Providers)", "${cloud.size} selected model(s)") }
                items(cloud, key = { it.id }) { e ->
                    ModelRow(
                        name = e.name, subtitle = e.providerName ?: "cloud",
                        size = "", projector = null, cloud = true,
                        loaded = state.currentModelId == e.id,
                        busy = state.busy,
                        onLoad = { vm.loadEntry(e) }
                    )
                }
            } else {
                item {
                    Text(
                        "No cloud models selected. Open Providers to add a key and tick models.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelsHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRow(
    name: String,
    subtitle: String,
    size: String,
    projector: String?,
    cloud: Boolean,
    loaded: Boolean,
    busy: Boolean,
    onLoad: () -> Unit
) {
    Card(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                val projLine = when {
                    cloud -> "cloud model"
                    projector != null -> "mmproj: " + projector.substringAfterLast('/')
                    else -> "no mmproj (text only)"
                }
                Text(projLine, style = MaterialTheme.typography.labelSmall)
                if (size.isNotBlank()) Text(size, style = MaterialTheme.typography.labelSmall)
            }
            if (loaded) {
                AssistChip(
                    onClick = {},
                    label = { Text("Loaded") },
                    leadingIcon = { Icon(Icons.Default.Check, null) }
                )
            } else {
                Button(onClick = onLoad, enabled = !busy) { Text("Load") }
            }
        }
    }
}
