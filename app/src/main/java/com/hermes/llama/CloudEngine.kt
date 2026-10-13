package com.hermes.llama

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage (h) path C: BYOK cloud providers.
 *
 * Data-driven provider presets (base URL + auth scheme) so the configs can be
 * updated without an app change. Cloud models implement the *same* chat
 * interface shape as the local engine, so streaming/thinking/the server all
 * work uniformly.
 */
object CloudProviders {
    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val authPrefix: String = "Bearer "
    )

    val ALL: List<Provider> = listOf(
        Provider("openai",     "OpenAI",      "https://api.openai.com/v1"),
        Provider("anthropic",  "Anthropic",   "https://api.anthropic.com/v1"),
        Provider("gemini",     "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Provider("groq",       "Groq",        "https://api.groq.com/openai/v1"),
        Provider("mistral",    "Mistral",     "https://api.mistral.ai/v1"),
        Provider("deepseek",   "DeepSeek",    "https://api.deepseek.com/v1"),
        Provider("xai",        "xAI",         "https://api.x.ai/v1"),
        Provider("openrouter", "OpenRouter",  "https://openrouter.ai/api/v1"),
        Provider("together",   "Together",    "https://api.together.xyz/v1"),
        Provider("fireworks",  "Fireworks",   "https://api.fireworks.ai/inference/v1"),
        Provider("cerebras",   "Cerebras",    "https://api.cerebras.ai/v1"),
        Provider("cohere",     "Cohere",      "https://api.cohere.ai/compatibility/v1")
    )

    fun byId(id: String): Provider? = ALL.firstOrNull { it.id == id }
}

/** OpenAI-compatible chat client used for every BYOK provider. */
object CloudEngine {

    /** Fetch the provider's model list (OpenAI `/models` shape). */
    fun models(provider: CloudProviders.Provider, apiKey: String): List<String> = runCatching {
        val conn = open("${provider.baseUrl}/models", apiKey, provider.authPrefix, "GET")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).map { data.getJSONObject(it).optString("id") }
    }.getOrDefault(emptyList())

    /**
     * Streaming chat. Calls [onToken] for each delta; returns the full text.
     * Blocking — call off the main thread.
     */
    fun chat(
        provider: CloudProviders.Provider,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ): String = runCatching {
        val conn = open("${provider.baseUrl}/chat/completions", apiKey, provider.authPrefix, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("temperature", temperature)
            .put("stream", true)
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val sb = StringBuilder()
        BufferedReader(conn.inputStream.reader()).useLines { lines ->
            lines.forEach { line ->
                val l = line.trim()
                if (!l.startsWith("data:")) return@forEach
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") return@forEach
                runCatching {
                    val delta = JSONObject(data)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content")
                    if (!delta.isNullOrEmpty()) { sb.append(delta); onToken(delta) }
                }
            }
        }
        sb.toString()
    }.getOrDefault("")

    private fun open(url: String, apiKey: String, authPrefix: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "$authPrefix$apiKey")
            setRequestProperty("Accept", "text/event-stream")
        }
}