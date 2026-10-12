package com.hermes.llama

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ChatScreen() } }
    }
}

/** Copies a content:// URI into app-private storage and returns the real path. */
private fun materialise(context: android.content.Context, uri: Uri): String? = runCatching {
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    val dst = File(context.filesDir, name)
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
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val path = materialise(context, it)
            if (path != null) vm.loadModel(path)
        }
    }

    // Auto-scroll as tokens stream in.
    LaunchedEffect(state.messages.size, state.streaming) {
        val target = state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Llama") },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Load model")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                            onClick = { vm.send(input); input = "" },
                            enabled = state.preset != null && input.isNotBlank()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
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
                    if (state.preset != null) {
                        Text(
                            "${state.preset!!.family} · temp ${state.preset!!.temperature} · " +
                                "capabilities: ${state.capabilities.describe()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.systemInfo.isNotBlank()) {
                        Text(
                            state.systemInfo,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
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
                items(state.messages) { msg -> MessageBubble(msg) }
                if (state.streaming.isNotEmpty()) {
                    item { MessageBubble(ChatMessage("assistant", state.streaming)) }
                }
                if (state.messages.isEmpty() && state.preset == null) {
                    item {
                        Text(
                            "Tap + to load a .gguf model from storage.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
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