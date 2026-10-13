package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v5: BYOK providers. Any number of API keys per provider; fetch the provider's
 * model list; tick the models you want. Tick'd models appear on the Models page
 * alongside local GGUFs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var modelQuery by remember { mutableStateOf("") }
    val fetched = remember { mutableStateMapOf<String, List<String>>() }
    val fetching = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text("Providers") },
                actions = {
                    IconButton(onClick = { vm.refreshModelsNow() }) { Icon(Icons.Default.Refresh, "Sync models") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                OutlinedTextField(
                    value = modelQuery, onValueChange = { modelQuery = it },
                    label = { Text("Filter models across providers") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            items(CloudProviders.ALL, key = { it.id }) { p ->
                val keys = ProviderStore.keysFor(context, p.id)
                val open = expanded == p.id
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                Text("${keys.size} key(s)", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { expanded = if (open) null else p.id }) {
                                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    "Expand")
                            }
                        }

                        if (open) {
                            Text(p.baseUrl, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(4.dp))

                            keys.forEach { k ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(k.label, style = MaterialTheme.typography.bodySmall)
                                        Text(maskKey(k.apiKey), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = {
                                        ProviderStore.removeKey(context, k); vm.bumpProviderSelection()
                                    }) { Icon(Icons.Default.Delete, "Remove key") }
                                }
                            }

                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = keyInput, onValueChange = { keyInput = it },
                                    placeholder = { Text("Paste API key") }, singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(onClick = {
                                    ProviderStore.addKey(context, p.id, "", keyInput.trim())
                                    keyInput = ""
                                    vm.bumpProviderSelection()
                                }) { Icon(Icons.Default.Add, "Add key") }
                            }

                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        val k = ProviderStore.keysFor(context, p.id).firstOrNull()?.apiKey
                                        if (k.isNullOrBlank()) return@Button
                                        fetching[p.id] = true
                                        scope.launch {
                                            val list = withContext(Dispatchers.IO) { CloudEngine.models(p, k) }
                                            fetched[p.id] = list
                                            fetching[p.id] = false
                                            vm.refreshModelsNow()
                                        }
                                    },
                                    enabled = keys.isNotEmpty() && fetching[p.id] != true
                                ) { Text("Load models") }
                                if (fetching[p.id] == true) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(Modifier.size(18.dp))
                                }
                            }

                            val all = fetched[p.id] ?: emptyList()
                            val shown = all.filter { modelQuery.isBlank() || it.contains(modelQuery, true) }
                            if (all.isNotEmpty()) {
                                Text("${shown.size} model(s) — tick to surface on the Models page",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp))
                                shown.forEach { mid ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = ProviderStore.isSelected(context, p.id, mid),
                                            onCheckedChange = {
                                                ProviderStore.toggleSelected(context, p.id, mid)
                                                vm.refreshModelsNow()
                                                vm.bumpProviderSelection()
                                            }
                                        )
                                        Text(mid, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            } else if (keys.isNotEmpty() && fetching[p.id] != true) {
                                Text("Tap “Load models” to fetch this provider's list.",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 4) "••••" else "••••••••" + key.takeLast(4)
