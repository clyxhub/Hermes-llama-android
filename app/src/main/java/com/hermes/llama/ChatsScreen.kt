package com.hermes.llama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * v5: chat history. List every conversation with pin / rename / delete / share,
 * plus a new-chat action. Tapping a row opens it in the chat screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(vm: ChatViewModel, onMenu: () -> Unit, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    renameId?.let { id ->
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameChat(id, renameText); renameId = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text("Chats") },
                actions = {
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Create, "New chat") }
                }
            )
        }
    ) { pad ->
        if (state.chats.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No chats yet. Tap the pencil icon above to start one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.chats, key = { it.id }) { chat ->
                    var menu by remember { mutableStateOf(false) }
                    val selected = chat.id == state.current?.id
                    Card(
                        onClick = { onOpen(chat.id) },
                        colors = if (selected)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        else CardDefaults.cardColors(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chat.pinned) {
                                        Icon(Icons.Default.PushPin, "Pinned", modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(chat.title, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val last = chat.messages.lastOrNull()?.content?.replace('\n', ' ') ?: "No messages"
                                Text(last, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (chat.pinned) "Unpin" else "Pin") },
                                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                        onClick = { menu = false; vm.togglePin(chat.id) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { menu = false; renameText = chat.title; renameId = chat.id }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share conversation") },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = {
                                            menu = false
                                            shareText(context, ChatStore.exportMarkdown(chat), chat.title)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menu = false; vm.deleteChat(chat.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
