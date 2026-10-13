package com.hermes.llama

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v5: HuggingFace is its OWN section (not a sub-tab). Search state lives at
 * object scope so results survive navigating away. Erasing the query clears the
 * results (the "searches doesn't erase" bug). The repo view has a real download
 * section and can pull the mmproj alongside the model in one tap.
 */
object HfState {
    var query by mutableStateOf("")
    var token by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var results by mutableStateOf<List<HuggingFaceClient.Repo>>(emptyList())
    var openRepo by mutableStateOf<String?>(null)
    var filesLoading by mutableStateOf(false)
    var files by mutableStateOf<List<HuggingFaceClient.RepoFile>>(emptyList())
    /** Bumped whenever a download completes, to refresh the Download section. */
    var dlVersion by mutableStateOf(0)

    /** file path -> 0..1 progress, while a download is active. */
    val downloads = mutableStateMapOf<String, Float>()
}

/** Downloads go to app-specific external storage, which is in ModelRepository's roots. */
private fun hfDir(context: Context, repoId: String): File =
    File(File(context.getExternalFilesDir(null), "models"), repoId.substringAfterLast('/'))

private fun hfRoot(context: Context): File = File(context.getExternalFilesDir(null), "models")

/** Every gguf already downloaded through the HF portal. */
private fun downloadedFiles(context: Context): List<File> {
    val root = hfRoot(context)
    if (!root.isDirectory) return emptyList()
    val out = mutableListOf<File>()
    runCatching {
        root.walkTopDown().maxDepth(4).forEach {
            if (it.isFile && it.name.endsWith(".gguf", ignoreCase = true)) out.add(it)
        }
    }
    return out.sortedBy { it.name.lowercase() }
}

private fun isMmproj(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("mmproj") || n.contains("projector") || n.contains("clip")
}

private fun affinity(a: String, b: String): Int {
    val x = a.lowercase().substringBeforeLast('.')
    val y = b.lowercase().substringBeforeLast('.')
    var i = 0
    while (i < x.length && i < y.length && x[i] == y[i]) i++
    return i
}

/** Red / yellow / green badge colour for a hardware-fit verdict. */
private fun fitColor(fit: DeviceFit.Fit): Color = when (fit) {
    DeviceFit.Fit.GREEN -> Color(0xFF2E7D32)
    DeviceFit.Fit.YELLOW -> Color(0xFFF9A825)
    DeviceFit.Fit.RED -> Color(0xFFC62828)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = HfState.openRepo
    val dlv = HfState.dlVersion
    val ui by vm.state.collectAsState()
    // 17th requirement: hardware-fit profile (advisory only).
    val prof = remember(ui.gpuAvailable) { DeviceFit.profile(context, ui.gpuAvailable) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (repo != null) HfState.openRepo = null else onMenu() }) {
                        Icon(if (repo != null) Icons.Default.ArrowBack else Icons.Default.Menu,
                            if (repo != null) "Back" else "Menu")
                    }
                },
                title = { Text(repo ?: "HuggingFace", maxLines = 1) },
                actions = {
                    IconButton(onClick = { HfState.dlVersion++ }) { Icon(Icons.Default.Refresh, "Refresh downloads") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (repo == null) {
                // --- search bar -----------------------------------------------------
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = HfState.query,
                        onValueChange = {
                            HfState.query = it
                            if (it.isBlank()) { HfState.results = emptyList(); HfState.error = "" }
                        },
                        placeholder = { Text("Search GGUF models…") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = {
                        val q = HfState.query.trim()
                        if (q.isNotEmpty()) scope.launch {
                            HfState.loading = true; HfState.error = ""
                            runCatching {
                                HfState.results = withContext(Dispatchers.IO) {
                                    HuggingFaceClient.search(q, HfState.token.ifBlank { null })
                                }
                            }.onFailure { HfState.error = it.message ?: "search failed" }
                            HfState.loading = false
                        }
                    }) { Icon(Icons.Default.Search, "Search") }
                }
                OutlinedTextField(
                    value = HfState.token, onValueChange = { HfState.token = it },
                    placeholder = { Text("HF token (optional, for gated repos)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                )
                if (HfState.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (HfState.error.isNotBlank()) {
                    Text(HfState.error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }

                // --- results + downloads -------------------------------------------
                val dl = remember(dlv) { downloadedFiles(context) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (HfState.results.isNotEmpty()) {
                        item { Text("Search results", fontWeight = FontWeight.SemiBold) }
                    }
                    items(HfState.results, key = { it.id }) { r ->
                        Card(onClick = {
                            HfState.openRepo = r.id
                            HfState.filesLoading = true
                            scope.launch {
                                HfState.files = withContext(Dispatchers.IO) {
                                    HuggingFaceClient.files(r.id, HfState.token.ifBlank { null })
                                }
                                HfState.filesLoading = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.id, fontWeight = FontWeight.SemiBold)
                                Text("downloads ${r.downloads} · likes ${r.likes}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Downloaded files", fontWeight = FontWeight.SemiBold)
                    }
                    if (dl.isEmpty()) {
                        item {
                            Text("Nothing downloaded yet — search and open a repo to download.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        }
                    }
                    items(dl, key = { it.absolutePath }) { f ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        (if (isMmproj(f.name)) "mmproj · " else "") + ModelRepository.formatSize(f.length()),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Text("in Models", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                // --- repo file view -------------------------------------------------
                if (HfState.filesLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val models = HfState.files.filter { !isMmproj(it.path.substringAfterLast('/')) }
                val projs = HfState.files.filter { isMmproj(it.path.substringAfterLast('/')) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (HfState.files.isEmpty() && !HfState.filesLoading) {
                        item {
                            Text("No .gguf files in this repo.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (models.isNotEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("Your phone: " + DeviceFit.deviceSummary(prof),
                                    style = MaterialTheme.typography.labelSmall)
                                Text("Advisory only - any model can still be downloaded.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { Text("Model files", fontWeight = FontWeight.SemiBold) }
                    }
                    items(models, key = { it.path }) { f ->
                        val best = projs.maxByOrNull { affinity(f.path.substringAfterLast('/'), it.path.substringAfterLast('/')) }
                        val bytes = f.size + (best?.size ?: 0L)
                        FileRow(
                            f = f,
                            fit = DeviceFit.evaluate(bytes, prof),
                            fitDetail = DeviceFit.detail(bytes, prof),
                            extraLabel = if (best != null) "＋ Download mmproj" else null,
                            onDownload = {
                                launchDownload(context, scope, vm, repo, listOf(f.path))
                            },
                            onExtra = if (best != null) {
                                { launchDownload(context, scope, vm, repo, listOf(f.path, best.path)) }
                            } else null
                        )
                    }
                    if (projs.isNotEmpty()) item { Text("Projector files (mmproj)", fontWeight = FontWeight.SemiBold) }
                    items(projs, key = { it.path }) { f ->
                        FileRow(
                            f = f, extraLabel = null,
                            onDownload = { launchDownload(context, scope, vm, repo, listOf(f.path)) },
                            onExtra = null
                        )
                    }
                    item {
                        Text("Downloads land in " + hfDir(context, repo).absolutePath +
                            " and appear on the Models page (mmproj pairs automatically).",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

/** Kick off a download, optionally of several files (model + its mmproj). */
private fun launchDownload(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    vm: ChatViewModel,
    repo: String,
    paths: List<String>
) {
    paths.forEach { HfState.downloads[it] = 0f }
    scope.launch {
        val dir = hfDir(context, repo)
        withContext(Dispatchers.IO) {
            paths.forEach { path ->
                HuggingFaceClient.download(repo, path, dir, HfState.token.ifBlank { null }) { done, total ->
                    HfState.downloads[path] = if (total > 0) done.toFloat() / total else 0f
                }
                HfState.downloads.remove(path)
            }
        }
        HfState.dlVersion++
        vm.refreshModelsNow()
    }
}

@Composable
private fun FileRow(
    f: HuggingFaceClient.RepoFile,
    extraLabel: String?,
    onDownload: () -> Unit,
    onExtra: (() -> Unit)?,
    fit: DeviceFit.Fit? = null,
    fitDetail: String? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ModelRepository.formatSize(f.size), style = MaterialTheme.typography.labelSmall)
                    if (fit != null) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(8.dp).background(fitColor(fit), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text(DeviceFit.label(fit), style = MaterialTheme.typography.labelSmall, color = fitColor(fit))
                    }
                }
                if (fit != null && fitDetail != null) {
                    Text(fitDetail, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val prog = HfState.downloads[f.path]
            if (prog != null) {
                LinearProgressIndicator(progress = prog, modifier = Modifier.width(72.dp))
            } else {
                if (onExtra != null && extraLabel != null) {
                    TextButton(onClick = onExtra) { Text(extraLabel, style = MaterialTheme.typography.labelSmall) }
                }
                IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download") }
            }
        }
    }
}