package com.hermes.llama

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.webkit.WebView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Icon for an artifact kind. */
private fun iconFor(kind: String) = when (kind) {
    "image" -> Icons.Default.Image
    "audio" -> Icons.Default.Audiotrack
    "video" -> Icons.Default.Movie
    "html" -> Icons.Default.Code
    "text" -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

/**
 * Compact card shown under a chat bubble for every generated file. Tapping it
 * opens the interactive viewer.
 */
@Composable
fun ArtifactCard(a: Artifact, onOpen: (Artifact) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onOpen(a) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(iconFor(a.kind), null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        a.kind,
                        if (a.sizeBytes > 0) ModelRepository.formatSize(a.sizeBytes) else null,
                        if (a.isRemote) "remote" else null,
                        a.origin
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.OpenInNew, "Open", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Full-screen interactive viewer. Image / audio / video / text render inline; an
 * HTML artifact can be *tested* in an in-app WebView. Every kind can be opened
 * with a system app, shared, or saved to the device.
 */
@Composable
fun ArtifactViewerDialog(
    a: Artifact,
    onDismiss: () -> Unit,
    onDownload: (Artifact) -> Unit
) {
    val context = LocalContext.current
    var webPreview by remember(a.id) { mutableStateOf(a.kind == "html") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row {
                OutlinedIconButton(onClick = { onDownload(a) }) { Icon(Icons.Default.Download, "Save") }
                Spacer(Modifier.width(4.dp))
                OutlinedIconButton(onClick = { shareArtifact(context, a) }) { Icon(Icons.Default.Share, "Share") }
                Spacer(Modifier.width(4.dp))
                OutlinedIconButton(onClick = { openArtifact(context, a) }) { Icon(Icons.Default.OpenInNew, "Open") }
            }
        },
        title = {
            Column {
                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    a.kind + (if (a.sizeBytes > 0) " · " + ModelRepository.formatSize(a.sizeBytes) else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (a.kind == "html") {
                    TextButton(onClick = { webPreview = !webPreview }) {
                        Text(if (webPreview) "Hide preview" else "Test (preview in app)")
                    }
                }
                Spacer(Modifier.height(8.dp))
                ArtifactPreview(a, webPreview)
            }
        }
    )
}

@Composable
private fun ArtifactPreview(a: Artifact, webPreview: Boolean) {
    val context = LocalContext.current
    val local = ensureLocal(a, context)
    when (a.kind) {
        "image" -> ImagePreview(a, local)
        "audio" -> AudioPreview(a, local)
        "video" -> VideoPreview(local)
        "html" -> if (webPreview) HtmlPreview(local) else TextPreview(local)
        "text" -> TextPreview(local)
        else -> GenericPreview(a)
    }
}

/** Resolve a local file path, downloading the artifact on demand if it is remote. */
@Composable
private fun ensureLocal(a: Artifact, context: Context): String? {
    val path by produceState<String?>(initialValue = a.path, a.id) {
        value = a.path ?: withContext(Dispatchers.IO) { ArtifactStore.materialize(context, a).path }
    }
    return path
}

@Composable
private fun ImagePreview(a: Artifact, path: String?) {
    val bmp by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = a.name,
            modifier = Modifier.fillMaxWidth().height(320.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Placeholder(if (path == null) "Downloading…" else "No preview available")
    }
}

@Composable
private fun AudioPreview(a: Artifact, path: String?) {
    var playing by remember(a.id) { mutableStateOf(false) }
    val player = remember(a.id) { MediaPlayer() }
    DisposableEffect(a.id) {
        onDispose { runCatching { player.release() } }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            runCatching {
                if (playing) {
                    player.pause(); playing = false
                } else {
                    val p = path ?: return@runCatching
                    player.reset()
                    player.setDataSource(p)
                    player.prepare()
                    player.start()
                    playing = true
                }
            }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play")
        }
        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (path == null) Text("Downloading…", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun VideoPreview(path: String?) {
    if (path == null) {
        Placeholder("Downloading…")
        return
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(path)
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
            }
        },
        modifier = Modifier.fillMaxWidth().height(260.dp)
    )
}

@Composable
private fun HtmlPreview(path: String?) {
    if (path == null) {
        Placeholder("Downloading…")
        return
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadUrl("file://" + path)
            }
        },
        modifier = Modifier.fillMaxWidth().height(360.dp)
    )
}

@Composable
private fun TextPreview(path: String?) {
    val text by produceState(initialValue = "", path) {
        value = withContext(Dispatchers.IO) {
            path?.let { runCatching { File(it).readBytes().toString(Charsets.UTF_8) }.getOrNull() }.orEmpty()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(320.dp)
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text(
                if (text.isBlank()) "(empty)" else text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun Placeholder(msg: String) {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GenericPreview(a: Artifact) {
    Column(
        Modifier.fillMaxWidth().height(160.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(iconFor(a.kind), null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(8.dp))
        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Open with a system app to interact with this file.", style = MaterialTheme.typography.labelSmall)
    }
}

// --- helpers ----------------------------------------------------------------

/** Hand a saved artifact to another app via the system chooser. */
fun shareArtifact(context: Context, a: Artifact) {
    val intent = ArtifactStore.shareIntent(context, a) ?: return
    context.startActivity(Intent.createChooser(intent, "Share " + a.name))
}

/** Open an artifact with whichever system app handles its type. */
fun openArtifact(context: Context, a: Artifact) {
    val intent = ArtifactStore.openIntent(context, a) ?: return
    runCatching { context.startActivity(intent) }
}
