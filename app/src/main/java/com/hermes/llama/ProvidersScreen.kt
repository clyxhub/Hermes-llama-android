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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reactive provider state.
 *
 * Keys and the selected-model set live in ProviderStore (plain SharedPreferences),
 * which Compose cannot observe. Every mutation bumps [revision] and the screen
 * reads it, so the UI recomposes immediately. (Fixes the "adding a key / loading
 * models hangs until you close and reopen the Providers screen" bug.)
 */
object ProviderUi {
    var revision by mutableStateOf(0)
        private set
    val expanded = mutableStateMapOf<String, Boolean>()
    val fetched = mutableStateMapOf<String, List<String>>()
    val fetching = mutableStateMapOf<String, Boolean>()

    fun refresh() { revision++ }
}

/**
 * v5: BYOK providers. Unlimited API keys per provider, an editable base URL per
 * provider, user-defined custom providers, and a provider model list you tick to
 * surface on the Models page alongside local GGUFs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev = ProviderUi.revision
    var modelQuery by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }

    val providers = remember(rev) { ProviderStore.allProviders(context) }

    if (showCustom) {
        AddCustomProviderDialog(
            onDismiss = { showCustom = false },
            onAdd = { name, url, prefix ->
                ProviderStore.addCustomProvider(context, name, url, prefix)
                ProviderUi.refresh()
                vm.refreshModelsNow()
                showCustom = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text("Providers") },
                actions = {
                    IconButton(onClick = { showCustom = true }) { Icon(Icons.Default.Add, "Add custom provider") }
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
            items(providers, key = { it.id }) { p ->
                ProviderCard(p = p, modelQuery = modelQuery, scope = scope, vm = vm)
            }
            item {
                Text(
                    "Tip: the + button adds a custom OpenAI-compatible provider; expand any " +
                        "provider to edit its endpoint or add keys.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    p: CloudProviders.Provider,
    modelQuery: String,
    scope: CoroutineScope,
    vm: ChatViewModel
) {
    val context = LocalContext.current
    val keys = ProviderStore.keysFor(context, p.id)
    val open = ProviderUi.expanded[p.id] == true
    var keyField by remember { mutableStateOf("") }
    var baseField by remember(p.id) { mutableStateOf(p.baseUrl) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        if (p.custom) {
                            Spacer(Modifier.width(6.dp))
                            Text("custom", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("${keys.size} key(s)", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { ProviderUi.expanded[p.id] = !open }) {
                    Icon(
                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        "Expand"
                    )
                }
            }

            if (open) {
                Text("Endpoint (base URL)", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = baseField, onValueChange = { baseField = it },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        ProviderStore.setBaseUrlOverride(context, p.id, baseField)
                        ProviderUi.refresh()
                        vm.refreshModelsNow()
                    }) { Text("Save") }
                }

                Spacer(Modifier.height(6.dp))

                keys.forEach { k ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.label, style = MaterialTheme.typography.bodySmall)
                            Text(maskKey(k.apiKey), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            ProviderStore.removeKey(context, k); ProviderUi.refresh(); vm.refreshModelsNow()
                        }) { Icon(Icons.Default.Delete, "Remove key") }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyField, onValueChange = { keyField = it },
                        placeholder = { Text("Paste API key") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = {
                        if (keyField.isNotBlank()) {
                            ProviderStore.addKey(context, p.id, "", keyField.trim())
                            keyField = ""
                            ProviderUi.refresh()
                            vm.refreshModelsNow()
                        }
                    }) { Icon(Icons.Default.Add, "Add key") }
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val k = ProviderStore.keysFor(context, p.id).firstOrNull()?.apiKey
                            if (!k.isNullOrBlank()) {
                                ProviderUi.fetching[p.id] = true
                                scope.launch {
                                    val list = withContext(Dispatchers.IO) { CloudEngine.models(p, k) }
                                    ProviderUi.fetched[p.id] = list
                                    ProviderUi.fetching[p.id] = false
                                    vm.refreshModelsNow()
                                }
                            }
                        },
                        enabled = keys.isNotEmpty() && ProviderUi.fetching[p.id] != true
                    ) { Text("Load models") }
                    if (ProviderUi.fetching[p.id] == true) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(18.dp))
                    }
                }

                val all = ProviderUi.fetched[p.id] ?: emptyList()
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
                                    ProviderUi.refresh()
                                    vm.refreshModelsNow()
                                }
                            )
                            Text(mid, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (keys.isNotEmpty() && ProviderUi.fetching[p.id] != true) {
                    Text("Tap “Load models” to fetch this provider’s list.",
                        style = MaterialTheme.typography.labelSmall)
                }

                if (p.custom) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        ProviderStore.removeCustomProvider(context, p.id)
                        ProviderUi.refresh()
                        vm.refreshModelsNow()
                    }) { Text("Delete custom provider") }
                }
            }
        }
    }
}

@Composable
private fun AddCustomProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("Bearer ") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Base URL (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prefix, onValueChange = { prefix = it },
                    label = { Text("Auth prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url, prefix) }, enabled = url.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun maskKey(key: String): String =
    if (key.length <= 4) "\u2022\u2022\u2022\u2022" else "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + key.takeLast(4)