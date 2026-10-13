package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * v5: the model library page, styled after PocketPal's Models screen.
 *
 * Same placement: a hint banner at the top ("Available to Download"), a "+" FAB
 * bottom-right to find more, and one card per model with the name on the left
 * and the size on the right, plus a Use action and per-model icon buttons
 * (details / remove).
 *
 * The library itself is our differentiator: local GGUFs (with auto-paired
 * mmproj) AND the cloud models tick'd in the Providers screen, in one list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ChatViewModel, onMenu: () -> Unit, onFindMore: () -> Unit) {
    val state by vm.state.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelRepository.Entry?>(null) }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove model?") },
            text = { Text("Delete ${e.name}? This removes the file from storage.") },
            confirmButton = {
                TextButton(onClick = {
                    e.path?.let { runCatching { File(it).delete() } }
                    pendingDelete = null
                    vm.refreshModelsNow()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") } },
                title = { Text("Models") },
                actions = {
                    IconButton(onClick = { vm.refreshModelsNow() }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onFindMore) { Icon(Icons.Default.Add, "Find more models") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Available to Download", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use the + button to find more models on HuggingFace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.models.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No models yet. Tap + to download one.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.models, key = { it.id }) { m ->
                        ModelRow(
                            m = m,
                            selected = m.id == state.currentModelId,
                            expanded = expandedId == m.id,
                            projectors = state.projectorChoices,
                            onUse = { vm.loadEntry(m) },
                            onToggleDetails = { expandedId = if (expandedId == m.id) null else m.id },
                            onRemove = { pendingDelete = m },
                            onPair = { proj -> m.path?.let { vm.setProjector(it, proj) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    m: ModelRepository.Entry,
    selected: Boolean,
    expanded: Boolean,
    projectors: List<ModelRepository.Entry>,
    onUse: () -> Unit,
    onToggleDetails: () -> Unit,
    onRemove: () -> Unit,
    onPair: (String?) -> Unit
) {
    var projMenu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (selected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (m.isLocal) ModelRepository.formatSize(m.sizeBytes) else "cloud",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Text("Loaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else {
                    Button(onClick = onUse, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Use")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleDetails) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Details")
                }
                if (m.isLocal) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("source: ${m.source}", style = MaterialTheme.typography.labelSmall)
                m.path?.let { Text("path: $it", style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "projector: " + (m.projector?.substringAfterLast('/') ?: "none"),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (m.isLocal) {
                        Box {
                            TextButton(onClick = { projMenu = true }) { Text("Pair mmproj") }
                            DropdownMenu(expanded = projMenu, onDismissRequest = { projMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Auto (same folder)") },
                                    onClick = { projMenu = false; onPair(null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("None (text only)") },
                                    onClick = { projMenu = false; onPair("") }
                                )
                                projectors.forEach { pe ->
                                    DropdownMenuItem(
                                        text = { Text(pe.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { projMenu = false; pe.path?.let(onPair) }
                                    )
                                }
                            }
                        }
                    }
                }
                m.providerName?.let { Text("provider: $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}