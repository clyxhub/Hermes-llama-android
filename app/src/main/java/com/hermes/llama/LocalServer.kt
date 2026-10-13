package com.hermes.llama

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Stage (d): a minimal, dependency-free HTTP server that exposes the loaded
 * local model over an OpenAI-compatible API so other apps (and llama.cpp
 * clients) can talk to it.
 *
 * Endpoints
 *   GET  /health
 *   GET  /props                       (context size, chat template, modalities)
 *   GET  /v1/models
 *   POST /tokenize                    (token count)
 *   POST /v1/completions              (text)
 *   POST /v1/chat/completions         (SSE streaming + non-streaming, image parts)
 *
 * Inference is strictly serialized (one request at a time) — the native bridge
 * owns a single context, and the KV cache cannot be shared across requests.
 */
object LocalServer {

    /** Snapshot of the loaded model, published by the service/view-model. */
    data class ModelSnapshot(
        val id: String,
        val contextSize: Int,
        val capabilities: ModelCapabilities,
        val chatTemplate: String?
    )

    /**
     * One discoverable model in the catalogue: every local GGUF under the scan
     * roots plus every cloud model ticked in Providers. This is what the server
     * advertises on GET /v1/models, independent of what is currently loaded.
     */
    data class ModelCard(
        val id: String,
        val ownedBy: String,
        val source: String,
        val providerId: String? = null,
        val detail: String = ""
    )

    @Volatile private var snapshot: ModelSnapshot? = null
    @Volatile private var catalogue: List<ModelCard> = emptyList()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile var apiKey: String? = null
    @Volatile var bindAll: Boolean = false

    /** One inference at a time across all connections. */
    private val inferLock = Object()

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "hermes-http").apply { isDaemon = true }
    }

    fun updateModel(model: ModelSnapshot?) { snapshot = model }

    /**
     * Publish the discoverable catalogue directly (used by the view-model, which
     * already has the freshly scanned library in hand).
     */
    fun updateCatalogue(cards: List<ModelCard>) { catalogue = cards }

    /**
     * Rebuild the catalogue from the app model library so a running server
     * always answers /v1/models with every available local GGUF and every cloud
     * model the user ticked in Providers, not just the currently loaded one.
     */
    fun refreshCatalogue(context: Context) {
        val entries = runCatching {
            ModelRepository.all(context, ModelRepository.rootsFrom(context))
        }.getOrDefault(emptyList())
        catalogue = cardsFrom(entries)
    }

    /** Map library entries to OpenAI-style model cards. */
    fun cardsFrom(entries: List<ModelRepository.Entry>): List<ModelCard> = entries.map { e ->
        ModelCard(
            id = e.name,
            ownedBy = if (e.isLocal) "local" else (e.providerName ?: "cloud"),
            source = e.source,
            providerId = e.providerId,
            detail = e.subtitle
        )
    }


    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    fun start(port: Int): Boolean {
        if (isRunning) return true
        return try {
            val addr = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
            val ss = ServerSocket(port, 50, addr)
            serverSocket = ss
            acceptThread = Thread({ acceptLoop(ss) }, "hermes-http-accept").apply {
                isDaemon = true; start()
            }
            true
        } catch (t: Throwable) {
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (ignored: Throwable) { break }
            pool.execute { runCatching { handle(socket) }.also { socket.closeQuietly() } }
        }
    }

    // --- request handling ----------------------------------------------------

    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readBody(input, contentLength) else ByteArray(0)

        // Optional bearer auth.
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            val auth = headers["authorization"] ?: ""
            if (auth != "Bearer $key") {
                writeJson(output, 401, JSONObject().put("error", "invalid api key"))
                return
            }
        }

        when {
            method == "GET" && path == "/health" ->
                writeJson(output, 200, JSONObject().put("status", "ok"))

            method == "GET" && path == "/v1/models" ->
                writeJson(output, 200, modelsResponse())

            method == "GET" && path == "/props" ->
                writeJson(output, 200, propsResponse())

            method == "POST" && path == "/tokenize" -> {
                val text = runCatching { JSONObject(String(body, Charsets.UTF_8)).optString("content") }.getOrDefault("")
                val n = LlamaBridge.countTokens(text)
                writeJson(output, 200, JSONObject().put("n_tokens", n))
            }

            method == "POST" && (path == "/v1/chat/completions" || path == "/completion") ->
                handleChat(output, body)

            method == "POST" && path == "/v1/completions" ->
                handleCompletion(output, body)

            else -> writeJson(output, 404, JSONObject().put("error", "not found"))
        }
        output.flush()
    }

    private fun handleChat(output: OutputStream, body: ByteArray) {
        val snap = snapshot ?: run { writeJson(output, 503, err("no model loaded")); return }
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }
            .getOrElse { writeJson(output, 400, err("bad json")); return }

        val stream = req.optBoolean("stream", false)
        val maxTokens = req.optInt("max_tokens", 512)
        val temperature = req.optDouble("temperature", 0.8).toFloat()
        val topP = req.optDouble("top_p", 0.95).toFloat()

        // Build the prompt from messages, honouring the model's chat template.
        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        var mediaPath: String? = null

        val messages = req.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role", "user")
            val content = m.opt("content")
            when (content) {
                is String -> { roles.add(role); contents.add(content) }
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        when (part.optString("type")) {
                            "text" -> sb.append(part.optString("text"))
                            "image_url" -> {
                                val url = part.optJSONObject("image_url")?.optString("url") ?: ""
                                mediaPath = mediaPath ?: dataUrlToTempFile(url)
                            }
                            "input_audio" -> {
                                val data = part.optJSONObject("input_audio")?.optString("data") ?: ""
                                mediaPath = mediaPath ?: dataUrlToTempFile(data, "wav")
                            }
                        }
                    }
                    roles.add(role); contents.add(sb.toString())
                }
                else -> { roles.add(role); contents.add("") }
            }
        }

        val tmpl = if (snap.chatTemplate.isNullOrBlank()) "" else snap.chatTemplate
        val prompt = runCatching {
            LlamaBridge.applyChatTemplate(tmpl, roles, contents, true)
        }.getOrNull() ?: contents.lastOrNull().orEmpty()

        if (stream) {
            writeSseHeaders(output)
            synchronized(inferLock) {
                val id = "chatcmpl-" + System.currentTimeMillis()
                val created = System.currentTimeMillis() / 1000
                val run: (String) -> Unit = { tok ->
                    val chunk = JSONObject()
                        .put("id", id).put("object", "chat.completion.chunk")
                        .put("created", created).put("model", snap.id)
                        .put("choices", JSONArray().put(JSONObject()
                            .put("index", 0)
                            .put("delta", JSONObject().put("content", tok))
                            .put("finish_reason", JSONObject.NULL)))
                    output.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                runCatching {
                    if (mediaPath != null) LlamaBridge.generateWithMedia(prompt, mediaPath!!, maxTokens, temperature, topP, run)
                    else LlamaBridge.generate(prompt, maxTokens, temperature, topP, run)
                }
                output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } else {
            val sb = StringBuilder()
            synchronized(inferLock) {
                runCatching {
                    if (mediaPath != null) LlamaBridge.generateWithMedia(prompt, mediaPath!!, maxTokens, temperature, topP) { sb.append(it) }
                    else LlamaBridge.generate(prompt, maxTokens, temperature, topP) { sb.append(it) }
                }
            }
            val resp = JSONObject()
                .put("id", "chatcmpl-" + System.currentTimeMillis())
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", snap.id)
                .put("choices", JSONArray().put(JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", sb.toString()))
                    .put("finish_reason", "stop")))
            writeJson(output, 200, resp)
        }
    }

    private fun handleCompletion(output: OutputStream, body: ByteArray) {
        val snap = snapshot ?: run { writeJson(output, 503, err("no model loaded")); return }
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }
            .getOrElse { writeJson(output, 400, err("bad json")); return }
        val prompt = req.optString("prompt", "")
        val maxTokens = req.optInt("max_tokens", 512)
        val temperature = req.optDouble("temperature", 0.8).toFloat()
        val topP = req.optDouble("top_p", 0.95).toFloat()

        val sb = StringBuilder()
        synchronized(inferLock) {
            runCatching { LlamaBridge.generate(prompt, maxTokens, temperature, topP) { sb.append(it) } }
        }
        val resp = JSONObject()
            .put("id", "cmpl-" + System.currentTimeMillis())
            .put("object", "text_completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", snap.id)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0).put("text", sb.toString()).put("finish_reason", "stop")))
        writeJson(output, 200, resp)
    }

    // --- responses -----------------------------------------------------------

    private fun modelsResponse(): JSONObject {
        val list = JSONArray()
        val seen = HashSet<String>()

        // Every discoverable model: local GGUFs on disk + selected cloud models.
        catalogue.forEach { c ->
            if (seen.add(c.id)) {
                list.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("object", "model")
                        .put("owned_by", c.ownedBy)
                        .put("source", c.source)
                        .put("provider", c.providerId ?: JSONObject.NULL)
                        .put("detail", c.detail)
                )
            }
        }

        // Always advertise the currently loaded model, even if it is not on disk
        // (for example a model loaded from a file-picker path outside the roots).
        snapshot?.let { s ->
            if (seen.add(s.id)) {
                list.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("object", "model")
                        .put("owned_by", "local")
                        .put("source", "local")
                        .put("loaded", true)
                )
            }
        }

        return JSONObject().put("object", "list").put("data", list)
    }

    private fun propsResponse(): JSONObject {
        val snap = snapshot
        val modalities = JSONArray().put("text")
        snap?.capabilities?.let {
            if (it.vision) modalities.put("image")
            if (it.audio) modalities.put("audio")
        }
        return JSONObject()
            .put("model", snap?.id ?: "none")
            .put("n_ctx", snap?.contextSize ?: 0)
            .put("chat_template", snap?.chatTemplate ?: JSONObject.NULL)
            .put("modalities", modalities)
            .put("sampling", JSONObject().put("temperature", 0.8).put("top_p", 0.95))
    }

    // --- helpers -------------------------------------------------------------

    private fun err(msg: String) = JSONObject().put("error", JSONObject().put("message", msg))

    private fun writeJson(output: OutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: application/json\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Decode an OpenAI `data:` URL (or raw base64) into a temp file for mtmd. */
    private fun dataUrlToTempFile(dataUrl: String, ext: String = "jpg"): String? = runCatching {
        val b64 = if (dataUrl.contains(",")) dataUrl.substringAfter(',') else dataUrl
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val f = File.createTempFile("hermes_media_", ".$ext")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return buf
    }

    private fun Socket.closeQuietly() { runCatching { close() } }
}