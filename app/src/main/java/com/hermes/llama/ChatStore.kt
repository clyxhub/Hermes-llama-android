package com.hermes.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One chat message. `role` is "user" | "assistant" | "system". */
data class Msg(
    val role: String,
    val content: String,
    val ts: Long = System.currentTimeMillis()
)

/** A conversation. */
data class Chat(
    val id: String,
    var title: String,
    var pinned: Boolean = false,
    var messages: MutableList<Msg> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

/**
 * Multi-chat persistence (replaces the single-history blob from the first pass).
 * Everything is a JSON array in one SharedPreferences entry — simple, atomic, and
 * dependency-free.
 */
object ChatStore {

    private const val PREFS = "hermes_llama"
    private const val KEY = "chats_v2"

    fun load(context: Context): MutableList<Chat> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return@runCatching mutableListOf()
        val arr = JSONArray(raw)
        val out = mutableListOf<Chat>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val msgs = mutableListOf<Msg>()
            val ma = o.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until ma.length()) {
                val m = ma.getJSONObject(j)
                msgs.add(Msg(m.optString("role"), m.optString("content"), m.optLong("ts")))
            }
            out.add(
                Chat(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    title = o.optString("title", "New chat"),
                    pinned = o.optBoolean("pinned", false),
                    messages = msgs,
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }
        out
    }.getOrDefault(mutableListOf())

    fun save(context: Context, chats: List<Chat>) {
        val arr = JSONArray()
        chats.forEach { c ->
            val msgs = JSONArray()
            c.messages.forEach { m ->
                msgs.put(JSONObject().put("role", m.role).put("content", m.content).put("ts", m.ts))
            }
            arr.put(
                JSONObject()
                    .put("id", c.id).put("title", c.title).put("pinned", c.pinned)
                    .put("updatedAt", c.updatedAt).put("messages", msgs)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun newChat(chats: MutableList<Chat>): Chat {
        val c = Chat(id = UUID.randomUUID().toString(), title = "New chat")
        chats.add(0, c)
        return c
    }

    fun delete(chats: MutableList<Chat>, id: String) = chats.removeAll { it.id == id }

    fun rename(chats: MutableList<Chat>, id: String, title: String) {
        chats.firstOrNull { it.id == id }?.title = title.ifBlank { "New chat" }
    }

    fun togglePin(chats: MutableList<Chat>, id: String) {
        chats.firstOrNull { it.id == id }?.let { it.pinned = !it.pinned }
    }

    /** A derived title from the first user message (used when none was set). */
    fun autoTitle(chat: Chat) {
        if (chat.title != "New chat" && chat.title.isNotBlank()) return
        val first = chat.messages.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        if (first.isNotEmpty()) chat.title = first.take(40)
    }

    /** Full transcript as Markdown, for "share conversation script". */
    fun exportMarkdown(chat: Chat): String {
        val sb = StringBuilder()
        sb.append("# ").append(chat.title).append("\n\n")
        chat.messages.forEach { m ->
            val who = when (m.role) {
                "user" -> "You"
                "assistant" -> "Assistant"
                else -> m.role
            }
            sb.append("**").append(who).append(":**\n\n").append(m.content).append("\n\n")
        }
        return sb.toString()
    }
}
