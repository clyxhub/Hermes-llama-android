package com.hermes.llama

import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage (h) path B: a native in-app HuggingFace portal (NOT a redirect).
 *
 * Talks straight to the HF HTTP API to search repositories, list the files /
 * quants inside one, and download a file with resume + optional token for
 * gated repos. Everything is done with `HttpURLConnection` so there is no
 * extra dependency to build.
 */
object HuggingFaceClient {

    private const val BASE = "https://huggingface.co"

    data class Repo(val id: String, val downloads: Int, val likes: Int)

    data class RepoFile(val path: String, val size: Long)

    /** Search all HF models for GGUF repos matching [query]. */
    fun search(query: String, token: String? = null, limit: Int = 25): List<Repo> = runCatching {
        val url = "$BASE/api/models?search=${enc(query)}&filter=gguf&limit=$limit&sort=downloads&direction=-1"
        val arr = JSONArray(get(url, token))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Repo(o.optString("id"), o.optInt("downloads"), o.optInt("likes"))
        }
    }.getOrDefault(emptyList())

    /** List the files in a repository's `main` revision. */
    fun files(repoId: String, token: String? = null): List<RepoFile> = runCatching {
        val arr = JSONArray(get("$BASE/api/models/$repoId/tree/main?recursive=true", token))
        (0 until arr.length())
            .map { i -> arr.getJSONObject(i) }
            .filter { it.optString("type") == "file" }
            .map { RepoFile(it.optString("path"), it.optLong("size")) }
            .filter { it.path.endsWith(".gguf", ignoreCase = true) }
    }.getOrDefault(emptyList())

    /**
     * Download [path] from [repoId] into [destDir] with resume support.
     * @return the downloaded file, or null on failure.
     */
    fun download(
        repoId: String,
        path: String,
        destDir: File,
        token: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File? = runCatching {
        destDir.mkdirs()
        val dest = File(destDir, path.substringAfterLast('/'))
        val already = if (dest.exists()) dest.length() else 0L

        val conn = (URL("$BASE/$repoId/resolve/main/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        conn.connect()

        if (conn.responseCode !in 200..299) return@runCatching null
        val total = conn.contentLengthLong.let { if (conn.responseCode == 206) it + already else it }

        RandomAccessFile(dest, "rw").use { raf ->
            if (already > 0) raf.seek(already)
            var downloaded = already
            conn.inputStream.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
        dest
    }.getOrNull()

    private fun get(url: String, token: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}