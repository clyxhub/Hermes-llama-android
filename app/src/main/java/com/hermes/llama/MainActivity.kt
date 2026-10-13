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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
                    "CALISTO local ai",
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
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Artifact?>(null) }
    val listState = rememberLazyListState()
    val msgs = state.current?.messages ?: emptyList()
    val caps = state.capabilities

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) copyUriToTemp(context, uri, isAudio = false)?.let { vm.setAttachment(it, false) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) copyUriToTemp(context, uri, isAudio = true)?.let { vm.setAttachment(it, true) }
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
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
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
                    attachmentIsAudio = state.attachmentIsAudio,
                    showImageAttach = caps.vision,
                    showAudioAttach = caps.audio,
                    busy = state.busy,
                    thinking = state.thinking,
                    webSearch = state.webSearch,
                    onToggleThinking = { vm.setThinking(!state.thinking) },
                    onToggleWebSearch = { vm.setWebSearch(!state.webSearch) },
                    onAttachImage = { imagePicker.launch("image/*") },
                    onAttachAudio = { audioPicker.launch("audio/*") },
                    onClearAttachment = { vm.clearAttachment() },
                    onVoice = { voiceInput.launch(VoiceIO.sttIntent()) },
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
                        MessageBubble(
                            m = m,
                            onEdit = { editIndex = i; editText = m.content },
                            onRetry = { vm.retryAt(i) },
                            onDelete = { vm.deleteMessage(i) },
                            onCopy = { clipboard.setText(AnnotatedString(m.content)) },
                            onSpeak = { VoiceIO.init(context); VoiceIO.speak(m.content) },
                            onOpenArtifact = { a -> vm.materializeArtifact(a); viewing = a }
                        )
                    }
                    if (state.busy) item { TypingBubble(state.streaming) }
                }
            }
        }
    }
    viewing?.let { a ->
        ArtifactViewerDialog(
            a = a,
            onDismiss = { viewing = null },
            onDownload = { vm.downloadArtifact(it) }
        )
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
            "Download a model to start chatting with CALISTO local ai",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("Download Model") }
    }
}

@Composable
private fun MessageBubble(
    m: Msg,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onOpenArtifact: (Artifact) -> Unit
) {
    val isUser = m.role == "user"
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(m.content, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Message actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; onCopy() }
                    )
                    if (isUser) {
                        DropdownMenuItem(
                            text = { Text("Edit & resend") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onEdit() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Retry") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { menu = false; onRetry() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Read aloud") },
                        leadingIcon = { Icon(Icons.Default.VolumeUp, null) },
                        onClick = { menu = false; onSpeak() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
        // Generated files (images, clips, music, HTML, …) shown under the bubble.
        m.artifacts.forEach { a ->
            Box(Modifier.widthIn(max = 300.dp)) {
                ArtifactCard(a = a, onOpen = onOpenArtifact)
            }
        }
    }
}

@Composable
private fun TypingBubble(streaming: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp)
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
    attachmentIsAudio: Boolean,
    showImageAttach: Boolean,
    showAudioAttach: Boolean,
    busy: Boolean,
    thinking: Boolean,
    webSearch: Boolean,
    onToggleThinking: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachAudio: () -> Unit,
    onClearAttachment: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (attachment != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (attachmentIsAudio) Icons.Default.MusicNote else Icons.Default.Image,
                        null, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (attachmentIsAudio) "Audio attached" else "Image attached",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)
                    )
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
            // Thinking + web search sit beside the input, not in the top bar.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ToolToggle("Thinking", Icons.Default.Psychology, thinking, onToggleThinking)
                ToolToggle("Search", Icons.Default.Public, webSearch, onToggleWebSearch)
                if (showImageAttach) {
                    IconButton(onClick = onAttachImage) { Icon(Icons.Default.Image, "Attach image") }
                }
                if (showAudioAttach) {
                    IconButton(onClick = onAttachAudio) { Icon(Icons.Default.MusicNote, "Attach audio") }
                }
                IconButton(onClick = onVoice) { Icon(Icons.Default.Mic, "Voice input") }
                Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = onSend, enabled = !busy) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}

@Composable
private fun ToolToggle(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon, label,
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
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
            Text("CALISTO local ai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Local GGUF inference with an OpenAI-compatible server.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text("Beyond PocketPal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf(
                "HuggingFace in-app download portal (model + mmproj)",
                "BYOK cloud providers, unlimited keys + custom endpoints",
                "Embedded OpenAI-compatible server",
                "mmproj library with auto-pairing + explicit pairing",
                "Built-in web search grounding",
                "Multi-chat history with pin/rename/share",
                "Per-message copy/edit/retry/delete/read-aloud"
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
private fun copyUriToTemp(context: Context, uri: Uri, isAudio: Boolean = false): String? = runCatching {
    val ext = if (isAudio) "wav" else "jpg"
    val f = File(context.cacheDir, "attach_${System.currentTimeMillis()}.$ext")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        f.outputStream().use { output -> input.copyTo(output) }
    }
    f.absolutePath
}.getOrNull()