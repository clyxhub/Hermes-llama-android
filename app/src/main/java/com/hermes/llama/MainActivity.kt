package com.hermes.llama

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Top-level navigation sections. */
enum class Section { CHAT, CHATS, MODELS, HUGGINGFACE, PROVIDERS, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppRoot() } }
    }
}

// --- small helpers ---------------------------------------------------------

/** Copies a content:// URI into app storage and returns the real path. */
fun materialise(context: Context, uri: Uri, subdir: String = "media"): String? = runCatching {
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
    val dir = File(context.filesDir, subdir).apply { mkdirs() }
    val dst = File(dir, name)
    context.contentResolver.openInputStream(uri)!!.use { input ->
        FileOutputStream(dst).use { output -> input.copyTo(output, 1 shl 20) }
    }
    dst.absolutePath
}.getOrNull()

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("hermes", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, text: String, title: String = "Share") {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(i, title))
}

// --- app host --------------------------------------------------------------

@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var section by remember { mutableStateOf(Section.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        VoiceIO.init(context)
        // Debug hook: allow headless model load for crash capture (am start ... --es model PATH --ei ngl 99).
        val intent = (context as? Activity)?.intent
        val dbg = intent?.getStringExtra("model")
        if (dbg != null) vm.debugLoad(dbg, intent.getIntExtra("ngl", 0), intent.getIntExtra("ctx", 4096))
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Hermes Llama", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                val go: (Section) -> Unit = { s -> section = s; scope.launch { drawer.close() } }
                NavigationDrawerItem(label = { Text("New chat") }, selected = false,
                    onClick = { vm.newChat(); go(Section.CHAT) },
                    icon = { Icon(Icons.Default.Add, null) }, modifier = Modifier.padding(6.dp))
                NavigationDrawerItem(label = { Text("Chats") }, selected = section == Section.CHATS,
                    onClick = { go(Section.CHATS) },
                    icon = { Icon(Icons.Default.MoreVert, null) }, modifier = Modifier.padding(6.dp))
                NavigationDrawerItem(label = { Text("Models") }, selected = section == Section.MODELS,
                    onClick = { go(Section.MODELS) },
                    icon = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.padding(6.dp))
                NavigationDrawerItem(label = { Text("HuggingFace") }, selected = section == Section.HUGGINGFACE,
                    onClick = { go(Section.HUGGINGFACE) },
                    icon = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.padding(6.dp))
                NavigationDrawerItem(label = { Text("Providers") }, selected = section == Section.PROVIDERS,
                    onClick = { go(Section.PROVIDERS) },
                    icon = { Icon(Icons.Default.Share, null) }, modifier = Modifier.padding(6.dp))
                NavigationDrawerItem(label = { Text("Settings") }, selected = section == Section.SETTINGS,
                    onClick = { go(Section.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.padding(6.dp))
                Spacer(Modifier.height(12.dp))
                Text(state.status, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    ) {
        when (section) {
            Section.CHAT -> ChatScreen(vm, onMenu = { scope.launch { drawer.open() } },
                onNewChat = { vm.newChat() }, onOpenChats = { section = Section.CHATS })
            Section.CHATS -> ChatsScreen(vm, onMenu = { scope.launch { drawer.open() } },
                onOpen = { id -> vm.selectChat(id); section = Section.CHAT })
            Section.MODELS -> ModelsScreen(vm, onMenu = { scope.launch { drawer.open() } })
            Section.HUGGINGFACE -> HuggingFaceScreen(vm, onMenu = { scope.launch { drawer.open() } })
            Section.PROVIDERS -> ProvidersScreen(vm, onMenu = { scope.launch { drawer.open() } })
            Section.SETTINGS -> SettingsScreen(vm, onMenu = { scope.launch { drawer.open() } })
        }
    }
}

// --- chat ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onNewChat: () -> Unit, onOpenChats: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { materialise(context, it, "media")?.let { p -> vm.setAttachment(p, false) } }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { materialise(context, it, "media")?.let { p -> vm.setAttachment(p, false) } }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { materialise(context, it, "media")?.let { p -> vm.setAttachment(p, true) } }
    }
    val stt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        VoiceIO.parseStt(res.data)?.let { input = if (input.isBlank()) it else "$input $it" }
    }

    val messages = state.current?.messages ?: emptyList()
    LaunchedEffect(messages.size, state.streaming) {
        val target = messages.size + if (state.streaming.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    if (editIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text("Edit message") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.editAndResend(editIndex, editText); editIndex = -1 }) { Text("Resend") } },
            dismissButton = { TextButton(onClick = { editIndex = -1 }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu") } },
                title = { Text(state.current?.title ?: "Hermes Llama", maxLines = 1) },
                actions = {
                    IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Chat history") }, onClick = { menuOpen = false; onOpenChats() }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenuItem(text = { Text("Share conversation") }, onClick = {
                            menuOpen = false; shareText(context, vm.exportCurrent(), "Share conversation")
                        }) { Icon(Icons.Default.Share, null) }
                        DropdownMenuItem(text = { Text("Rename chat") }, onClick = {
                            menuOpen = false
                            state.current?.let { c ->
                                editText = c.title; editIndex = -2
                            }
                        }) { Icon(Icons.Default.Edit, null) }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    // toggles row
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = state.thinking, onClick = { vm.setThinking(!state.thinking) },
                            label = { Text("Thinking", style = MaterialTheme.typography.labelSmall) })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = state.webSearch, onClick = { vm.setWebSearch(!state.webSearch) },
                            label = { Text("Web search", style = MaterialTheme.typography.labelSmall) })
                    }
                    state.attachment?.let { p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Attached: ${p.substringAfterLast('/')}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { vm.clearAttachment() }) { Icon(Icons.Default.Clear, "Remove") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Attach file") }
                        IconButton(onClick = { imagePicker.launch("image/*") }) { Icon(Icons.Default.Add, "Attach image") }
                        IconButton(onClick = { stt.launch(VoiceIO.sttIntent()) }) { Icon(Icons.Default.Mic, "Voice input") }
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") }, maxLines = 4,
                            enabled = state.preset != null && !state.busy
                        )
                        Spacer(Modifier.width(6.dp))
                        if (state.busy) {
                            IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "Stop") }
                        } else {
                            IconButton(
                                onClick = { vm.send(input, state.attachment); input = "" },
                                enabled = state.preset != null && (input.isNotBlank() || state.attachment != null)
                            ) { Icon(Icons.Default.Send, "Send") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    if (state.modelInfo.isNotBlank()) Text(state.modelInfo, style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages) { idx, msg ->
                    MessageBubble(
                        msg = msg, thinking = state.thinking,
                        onCopy = { copyToClipboard(context, msg.content) },
                        onSpeak = { VoiceIO.speak(msg.content) },
                        onEdit = { if (msg.role == "user") { editText = msg.content; editIndex = idx } },
                        onRetry = { vm.regenerate() },
                        onDelete = { vm.deleteMessage(idx) }
                    )
                }
                if (state.streaming.isNotEmpty()) {
                    item { MessageBubble(Msg("assistant", state.streaming), state.thinking, {}, {}, {}, {}, {}) }
                }
                if (messages.isEmpty()) {
                    item { Text("Pick a model on the Models page, then chat. Use Web search for live info.", Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: Msg, thinking: Boolean,
    onCopy: () -> Unit, onSpeak: () -> Unit, onEdit: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit
) {
    val isUser = msg.role == "user"
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        val parsed = if (!isUser) splitThinking(msg.content) else ("" to msg.content)
        val showTrace = !isUser && thinking && hasThinking(msg.content)
        Box {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    if (showTrace && parsed.first.isNotEmpty()) {
                        Text("reasoning", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(parsed.first, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text((if (showTrace) parsed.second else msg.content).ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Long-press handled via the trailing "more" affordance under each bubble.
            IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)) {
                Icon(Icons.Default.MoreVert, "Message actions", modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() }) { Icon(Icons.Default.ContentCopy, null) }
                DropdownMenuItem(text = { Text("Read aloud") }, onClick = { menu = false; onSpeak() }) { Icon(Icons.Default.VolumeUp, null) }
                if (isUser) DropdownMenuItem(text = { Text("Edit & resend") }, onClick = { menu = false; onEdit() }) { Icon(Icons.Default.Edit, null) }
                if (!isUser) DropdownMenuItem(text = { Text("Retry") }, onClick = { menu = false; onRetry() }) { Icon(Icons.Default.Refresh, null) }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() }) { Icon(Icons.Default.Delete, null) }
            }
        }
    }
}

// --- thinking-tag helpers (escapes keep the source tag-safe) ----------------

private const val THINK_OPEN = "\u003Cthink\u003E"
private const val THINK_CLOSE = "\u003C/think\u003E"

fun hasThinking(content: String): Boolean =
    content.contains(THINK_OPEN) || content.contains(THINK_CLOSE)

fun splitThinking(content: String): Pair<String, String> {
    val hasOpen = content.contains(THINK_OPEN)
    val hasClose = content.contains(THINK_CLOSE)
    val afterOpen = if (hasOpen) content.substringAfter(THINK_OPEN) else content
    val trace = afterOpen.substringBefore(THINK_CLOSE).trim()
    val answer = if (hasClose) afterOpen.substringAfter(THINK_CLOSE).trim() else ""
    return trace to answer
}