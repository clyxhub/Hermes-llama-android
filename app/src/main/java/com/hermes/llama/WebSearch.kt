package com.hermes.llama

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal web search for the chat "search the internet" toggle. Uses DuckDuckGo's
 * HTML endpoint (no API key, no dependency) and returns the top hits as text that
 * is prepended to the prompt.
 *
 * Entity strings are assembled from fragments on purpose: writing the literal
 * entities has previously been mangled by the tooling pipeline.
 */
object WebSearch {

    data class Hit(val title: String, val url: String, val snippet: String)

    private val AMP = "&" + "amp;"
    private val QUOT = "&" + "quot;"
    private val APOS = "&" + "#x27;"
    private val LT = "&" + "lt;"
    private val GT = "&" + "gt;"
    private val NBSP = "&" + "nbsp;"

    private val RE_TITLE = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val RE_SNIP = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val RE_TAGS = Regex("<[^>]+>")
    private val RE_WS = Regex("\\s+")

    fun search(query: String, limit: Int = 5): List<Hit> = runCatching {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) HermesLlama/1.0")
            setRequestProperty("Accept", "text/html")
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        val titles = RE_TITLE.findAll(html).map { strip(it.groupValues[2]) to unwrap(decode(it.groupValues[1])) }.toList()
        val snips = RE_SNIP.findAll(html).map { strip(decode(it.groupValues[1])) }.toList()
        (0 until minOf(limit, titles.size)).map { i ->
            Hit(titles[i].first, titles[i].second, snips.getOrElse(i) { "" })
        }.filter { it.title.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** Format hits as a compact block to inject before the user's question. */
    fun contextBlock(query: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Web search results for \"").append(query).append("\":\n")
        hits.forEachIndexed { i, h ->
            sb.append(i + 1).append(". ").append(h.title)
            if (h.snippet.isNotBlank()) sb.append(" — ").append(h.snippet)
            if (h.url.isNotBlank()) sb.append(" (").append(h.url).append(")")
            sb.append('\n')
        }
        sb.append("\nUse these results if relevant; otherwise answer from your knowledge.")
        return sb.toString()
    }

    private fun strip(s: String): String = s.replace(RE_TAGS, " ").replace(RE_WS, " ").trim()

    private fun decode(s: String): String = s
        .replace(AMP, "&")
        .replace(QUOT, "\u0022")
        .replace(APOS, "'")
        .replace(LT, "\u003C")
        .replace(GT, "\u003E")
        .replace(NBSP, " ")

    /** DuckDuckGo wraps result links as /l/?uddg=ENCODED; unwrap when present. */
    private fun unwrap(href: String): String {
        val marker = "uddg="
        val i = href.indexOf(marker)
        if (i < 0) return href
        val enc = href.substring(i + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
    }
}