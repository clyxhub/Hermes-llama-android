package com.hermes.llama

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
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
import java.io.File

/**
 * v5: HuggingFace is its OWN section (not a sub-tab). Search state lives here,
 * at object scope, so results survive navigating away — this was the
 * "searches doesn't erase" bug (state was previously held in the composable and
 * reset on recomposition/navigation).
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

    /** file path -> 0..1 progress, while a download is active. */
    val downloads = mutableStateMapOf<String, Float>()
}

/** Downloads go to app-specific external storage, which is in ModelRepository's roots. */
private fun hfDir(context: Context, repoId: String): File =
    File(File(context.getExternalFilesDir(null), "models"), repoId.substringAfterLast('/'))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = HfState.openRepo

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (repo != null) HfState.openRepo = null else onMenu() }) {
                        Icon(if (repo != null) Icons.Default.ArrowBack else Icons.Default.Menu,
                            if (repo != null) "Back" else "Menu")
                    }
                },
                title = { Text(repo ?: "HuggingFace", maxLines = 1) }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            if (repo == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = HfState.query, onValueChange = { HfState.query = it },
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
            }

            if (repo == null) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (HfState.results.isEmpty() && !HfState.loading) {
                        item {
                            Text("Search results appear here and stay put when you navigate away.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        }
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
                }
            } else {
                if (HfState.filesLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                    items(HfState.files, key = { it.path }) { f ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
                                    Text(ModelRepository.formatSize(f.size),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                val prog = HfState.downloads[f.path]
                                if (prog != null) {
                                    LinearProgressIndicator(
                                        progress = prog,
                                        modifier = Modifier.width(96.dp)
                                    )
                                } else {
                                    IconButton(onClick = {
                                        HfState.downloads[f.path] = 0f
                                        scope.launch {
                                            val dir = hfDir(context, repo)
                                            withContext(Dispatchers.IO) {
                                                HuggingFaceClient.download(
                                                    repo, f.path, dir, HfState.token.ifBlank { null }
                                                ) { done, total ->
                                                    HfState.downloads[f.path] =
                                                        if (total > 0) done.toFloat() / total else 0f
                                                }
                                            }
                                            HfState.downloads.remove(f.path)
                                            vm.refreshModelsNow()
                                        }
                                    }) { Icon(Icons.Default.Download, "Download") }
                                }
                            }
                        }
                    }
                }
                Text("Downloads land in " + hfDir(context, repo).absolutePath +
                    " — they show up on the Models page.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }
}
