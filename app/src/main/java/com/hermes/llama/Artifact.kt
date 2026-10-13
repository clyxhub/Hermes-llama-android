package com.hermes.llama

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * A file produced by any generative model, local or cloud.
 *
 * Examples: an image from a diffusion model, a video clip, an audio/music track,
 * an HTML document, a PDF, a data file. The app materialises every artifact into
 * app storage (`filesDir/generated`) so it can be viewed, tested, shared and
 * downloaded regardless of where it came from.
 */
data class Artifact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String = "application/octet-stream",
    val kind: String = "file",       // image | audio | video | html | text | file
    val origin: String = "cloud",    // cloud | local
    val url: String? = null,         // remote source (not yet downloaded)
    val path: String? = null,        // local file once materialised
    val sizeBytes: Long = 0
) {
    val isRemote: Boolean get() = path == null && !url.isNullOrBlank()
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** A raw file payload found in a model response, before it becomes an [Artifact]. */
data class RawArtifact(
    val name: String,
    val mime: String,
    val kind: String,
    val url: String? = null,
    val b64: String? = null,
    val origin: String = "cloud"
)

/**
 * Materialises and hands out generated files. All files live under
 * `filesDir/generated`; remote payloads are downloaded on demand.
 */
object ArtifactStore {

    fun authority(context: Context): String = context.packageName + ".fileprovider"

    fun dir(context: Context): File = File(context.filesDir, "generated").apply { mkdirs() }

    private fun safeName(name: String): String {
        val base = name.ifBlank { "artifact" }.trim()
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "artifact" }
    }

    private fun uniqueFile(context: Context, name: String): File {
        val d = dir(context)
        val first = File(d, safeName(name))
        if (!first.exists()) return first
        val stem = first.name.substringBeforeLast('.', first.name)
        val ext = first.name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val cand = File(d, if (ext.isEmpty()) "$stem-$i" else "$stem-$i.$ext")
            if (!cand.exists()) return cand
            i++
        }
    }

    // --- materialisation -----------------------------------------------------

    fun saveBytes(context: Context, name: String, mime: String, bytes: ByteArray, origin: String = "cloud"): Artifact {
        val f = uniqueFile(context, name)
        f.writeBytes(bytes)
        return Artifact(name = f.name, mime = mime, kind = kindOf(mime, f.name), origin = origin, path = f.absolutePath, sizeBytes = f.length())
    }

    fun saveBase64(context: Context, name: String, mime: String, b64: String, origin: String = "cloud"): Artifact? = runCatching {
        val clean = b64.substringAfter(',', b64)
        saveBytes(context, name, mime, Base64.decode(clean, Base64.DEFAULT), origin)
    }.getOrNull()

    fun download(context: Context, url: String, nameHint: String? = null, origin: String = "cloud"): Artifact? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CalistoLocalAI/1.0")
        }
        conn.inputStream.use { input ->
            val bytes = input.readBytes()
            val ct = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
            val name = guessName(url, nameHint, ct)
            saveBytes(context, name, ct ?: mimeForName(name), bytes, origin)
        }
    }.getOrNull()

    /** Turn a discovered payload into a stored [Artifact]. */
    fun materialize(context: Context, raw: RawArtifact): Artifact? = when {
        raw.b64 != null -> saveBase64(context, raw.name, raw.mime, raw.b64, raw.origin)
        raw.url != null -> download(context, raw.url, raw.name, raw.origin)
        else -> null
    }

    fun materializeAll(context: Context, raws: List<RawArtifact>): List<Artifact> =
        raws.mapNotNull { runCatching { materialize(context, it) }.getOrNull() }

    /** Ensure an artifact has a local file (downloads it if it is remote-only). */
    fun materialize(context: Context, a: Artifact): Artifact =
        if (a.path != null) a
        else a.url?.let { u -> download(context, u, a.name, a.origin) }?.copy(id = a.id) ?: a

    fun bytesOf(a: Artifact): ByteArray? =
        a.path?.let { runCatching { File(it).readBytes() }.getOrNull() }

    fun delete(context: Context, a: Artifact) {
        a.path?.let { runCatching { File(it).delete() } }
    }

    /**
     * Copy an artifact out of app storage into the device's public Downloads
     * folder so the user can keep/open it outside the app. Uses MediaStore on
     * API 29+ (scoped storage) and a plain write on API 28.
     */
    fun exportToDownloads(context: Context, a: Artifact): Boolean = runCatching {
        val bytes = bytesOf(a) ?: return@runCatching false
        val name = safeName(a.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, a.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            true
        }
    }.getOrDefault(false)

    // --- sharing / opening / testing ----------------------------------------

    fun uriFor(context: Context, a: Artifact): Uri? {
        val p = a.path ?: return null
        return runCatching { FileProvider.getUriForFile(context, authority(context), File(p)) }.getOrNull()
    }

    fun shareIntent(context: Context, a: Artifact): Intent? {
        val u = uriFor(context, a) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = a.mime
            putExtra(Intent.EXTRA_STREAM, u)
            putExtra(Intent.EXTRA_TITLE, a.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openIntent(context: Context, a: Artifact): Intent? {
        val u = uriFor(context, a) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, a.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // --- classification ------------------------------------------------------

    fun kindOf(mime: String, name: String): String {
        val m = mime.lowercase()
        val e = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") || e in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg") -> "image"
            m.startsWith("audio/") || e in setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus") -> "audio"
            m.startsWith("video/") || e in setOf("mp4", "webm", "mkv", "mov", "avi") -> "video"
            m == "text/html" || e in setOf("html", "htm") -> "html"
            m.startsWith("text/") || e in setOf("txt", "md", "json", "xml", "csv", "log", "js", "css", "kt", "py") -> "text"
            else -> "file"
        }
    }

    fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "html", "htm" -> "text/html"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun extForMime(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("svg") -> "svg"
        mime.startsWith("audio/mpeg") -> "mp3"
        mime.startsWith("audio/wav") -> "wav"
        mime.startsWith("audio/ogg") -> "ogg"
        mime.startsWith("audio/") -> "m4a"
        mime.startsWith("video/") -> "mp4"
        mime.contains("html") -> "html"
        mime.startsWith("text/") -> "txt"
        else -> "bin"
    }

    private fun nameFromUrl(url: String): String {
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        return if (last.isNotBlank() && last.contains('.')) last else "generated"
    }

    private fun guessName(url: String, hint: String?, contentType: String?): String {
        val fromUrl = nameFromUrl(url)
        if (fromUrl != "generated") return fromUrl
        hint?.let { if (it.isNotBlank()) return it }
        val ext = contentType?.let { extForMime(it) } ?: "bin"
        return "generated.$ext"
    }
}

/** Finds file payloads inside model output (JSON objects or plain text). */
object ArtifactScanner {

    private val base64Rx = Regex("^[A-Za-z0-9+/=\\r\\n]+$")
    private val mdLinkRx = Regex("\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)")
    private val bareUrlRx = Regex("https?://[^\\s\"'<>)\\]}]+")

    private val dataUriRx = Regex("data:([a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)")

    private val interestingExt = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg",
        "mp3", "wav", "ogg", "m4a", "flac",
        "mp4", "webm", "mkv", "mov",
        "html", "htm", "pdf", "zip", "json", "csv", "txt"
    )

    /** Recursively scan an OpenAI-style JSON response for base64 blobs / media URLs. */
    fun fromJson(root: JSONObject, origin: String = "cloud"): List<RawArtifact> {
        val out = mutableListOf<RawArtifact>()
        walk(root, "", out, origin)
        return out
    }

    private fun walk(node: Any?, key: String, out: MutableList<RawArtifact>, origin: String) {
        when (node) {
            is JSONObject -> node.keys().forEach { k -> walk(node.opt(k), k, out, origin) }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), key, out, origin)
            is String -> consider(node, key, out, origin)
        }
    }

    private fun consider(v: String, key: String, out: MutableList<RawArtifact>, origin: String) {
        if (v.isBlank()) return
        val k = key.lowercase()
        if (k.contains("b64") && v.length > 128 && base64Rx.matches(v)) {
            val mime = when {
                k.contains("audio") -> "audio/mpeg"
                k.contains("video") -> "video/mp4"
                else -> "image/png"
            }
            out.add(RawArtifact("generated." + mime.substringAfter('/'), mime, ArtifactStore.kindOf(mime, ""), b64 = v, origin = origin))
            return
        }
        if (v.startsWith("http://") || v.startsWith("https://")) {
            val looksMedia = interestingExt.any { v.substringBefore('?').endsWith(".$it", ignoreCase = true) }
            if (k.contains("url") || k.contains("link") || looksMedia) {
                val name = v.substringBefore('?').substringAfterLast('/', "generated")
                val mime = ArtifactStore.mimeForName(name)
                out.add(RawArtifact(name, mime, ArtifactStore.kindOf(mime, name), url = v, origin = origin))
            }
        }
    }

    /** Find media/file links embedded in a text reply (Markdown or bare URLs). */
    fun fromText(text: String, origin: String = "cloud"): List<RawArtifact> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<RawArtifact>()
        dataUriRx.findAll(text).forEach { m ->
            val mime = m.groupValues[1]
            val b64 = m.groupValues[2]
            val name = "generated." + ArtifactStore.extForMime(mime)
            out.add(RawArtifact(name, mime, ArtifactStore.kindOf(mime, name), b64 = b64, origin = origin))
        }
        val urls = LinkedHashSet<String>()
        mdLinkRx.findAll(text).forEach { urls.add(it.groupValues[1]) }
        bareUrlRx.findAll(text).forEach { urls.add(it.value) }
        return out + urls.mapNotNull { u ->
            val name = u.substringBefore('?').substringBefore('#').substringAfterLast('/', "generated")
            if (name == "generated") return@mapNotNull null
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in interestingExt) return@mapNotNull null
            val mime = ArtifactStore.mimeForName(name)
            RawArtifact(name, mime, ArtifactStore.kindOf(mime, name), url = u, origin = origin)
        }
    }
}
