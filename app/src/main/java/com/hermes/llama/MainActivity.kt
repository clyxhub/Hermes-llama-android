package com.hermes.llama

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * v5: sections reachable from the navigation drawer.
 *
 * Placement + style mirror PocketPal AI (full-width icon+label drawer items,
 * Chat first, Models/Settings/App Info present). The extra entries — Chats,
 * HuggingFace, Providers — are this app's differentiators.
 */
enum class Section(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Default.Chat),
    CHATS("Chats", Icons.Default.History),
    MODELS("Models", Icons.Default.Storage),
    HUGGINGFACE("HuggingFace", Icons.Default.CloudDownload),
    PROVIDERS("Providers", Icons.Default.Key),
    SETTINGS("Settings", Icons.Default.Settings),
    INFO("App Info", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HermesTheme { AppRoot() } }
    }
}

/** Compose Material3 theme that follows the system light/dark setting. */
@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

/** Drawer host + section switch. One shared drawer, exactly like PocketPal. */
@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    var section by remember { mutableStateOf(Section.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val open: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Hermes Llama",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Section.values().forEach { s ->
                    NavigationDrawerItem(
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label) },
                        selected = s == section,
                        onClick = {
                            section = s
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        when (section) {
            Section.CHAT -> ChatScreen(vm, onMenu = open, onOpenModels = { section = Section.MODELS })
            Section.CHATS -> ChatsScreen(vm, onMenu = open, onOpen = { section = Section.CHAT })
            Section.MODELS -> ModelsScreen(vm, onMenu = open, onFindMore = { section = Section.HUGGINGFACE })
            Section.HUGGINGFACE -> HuggingFaceScreen(vm, onMenu = open)
            Section.PROVIDERS -> ProvidersScreen(vm, onMenu = open)
            Section.SETTINGS -> SettingsScreen(vm, onMenu = open)
            Section.INFO -> InfoScreen(onMenu = open)
        }
    }
}

// --- chat -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onOpenModels: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val msgs = state.current?.messages ?: emptyList()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) copyUriToTemp(context, uri)?.let { vm.setAttachment(it, false) }
    }
    val voiceInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        VoiceIO.parseStt(res.data)?.let { input = it }
    }

    LaunchedEffect(msgs.size, state.streaming, state.busy) {
        val count = msgs.size + if (state.busy) 1 else 0
        if (count > 0) runCatching { listState.animateScrollToItem(count - 1) }
    }

    editIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { editIndex = null },
            title = { Text("Edit & resend") },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.editAndResend(idx, editText); editIndex = null }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { editIndex = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") }
                },
                title = { Text("Chat") },
                actions = {
                    IconButton(onClick = { vm.setThinking(!state.thinking) }) {
                        Icon(
                            Icons.Default.Psychology, "Thinking",
                            tint = if (state.thinking) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { vm.setWebSearch(!state.webSearch) }) {
                        Icon(
                            Icons.Default.Public, "Web search",
                            tint = if (state.webSearch) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    if (state.busy) {
                        IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "Stop") }
                    }
                }
            )
        },
        bottomBar = {
            if (state.currentModelId != null) {
                Composer(
                    value = input,
                    onChange = { input = it },
                    attachment = state.attachment,
                    busy = state.busy,
                    onAttachImage = { imagePicker.launch("image/*") },
                    onClearAttachment = { vm.clearAttachment() },
                    onVoice = { voiceInput.launch(VoiceIO.sttIntent()) },
                    onTts = {
                        val last = msgs.lastOrNull { it.role == "assistant" }?.content
                        if (last != null) { VoiceIO.init(context); VoiceIO.speak(last) }
                    },
                    onSend = {
                        val text = input.trim()
                        if (text.isNotEmpty() || state.attachment != null) {
                            vm.send(text, state.attachment)
                            input = ""
                        }
                    }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                state.currentModelId == null -> EmptyModels(onOpenModels)
                msgs.isEmpty() && !state.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start chatting — type below.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(msgs) { i, m ->
                        MessageBubble(m) { editIndex = i; editText = m.content }
                    }
                    if (state.busy) item { TypingBubble(state.streaming) }
                }
            }
        }
    }
}

@Composable
private fun EmptyModels(onOpenModels: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No Models Available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Download a model to start chatting with Hermes Llama",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("Download Model") }
    }
}

@Composable
private fun MessageBubble(m: Msg, onEdit: () -> Unit) {
    val isUser = m.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(m.content, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (isUser) {
            TextButton(onClick = onEdit) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun TypingBubble(streaming: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                if (streaming.isBlank()) "…" else streaming,
                modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onChange: (String) -> Unit,
    attachment: String?,
    busy: Boolean,
    onAttachImage: () -> Unit,
    onClearAttachment: () -> Unit,
    onVoice: () -> Unit,
    onTts: () -> Unit,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (attachment != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Image attached", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClearAttachment) { Icon(Icons.Default.Close, "Remove attachment") }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text("Message input") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttachImage) { Icon(Icons.Default.Image, "Add image") }
                IconButton(onClick = onVoice) { Icon(Icons.Default.Mic, "Voice input") }
                IconButton(onClick = onTts) { Icon(Icons.Default.VolumeUp, "TTS settings") }
                Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = onSend, enabled = !busy) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}

// --- app info ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoScreen(onMenu: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") } },
                title = { Text("App Info") }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Hermes Llama", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Local GGUF inference with an OpenAI-compatible server.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text("Beyond PocketPal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf(
                "HuggingFace in-app download portal",
                "BYOK cloud providers, unlimited keys",
                "Embedded OpenAI-compatible server",
                "mmproj library with auto-pairing",
                "Built-in web search grounding",
                "Multi-chat history with pin/rename/share"
            ).forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// --- shared helpers ---------------------------------------------------------

/** Share a conversation transcript through the system chooser. */
fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share conversation"))
}

/** Copy a picked content Uri into a cache file the native mtmd path can read. */
private fun copyUriToTemp(context: Context, uri: Uri): String? = runCatching {
    val f = File(context.cacheDir, "attach_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        f.outputStream().use { output -> input.copyTo(output) }
    }
    f.absolutePath
}.getOrNull()
