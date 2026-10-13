package com.hermes.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * BYOK provider state:
 *  - any number of API keys per provider (label + secret)
 *  - a per-model selected/unselected set; selected models surface on the Models page
 *
 * Persisted as JSON in the shared "hermes_llama" preferences.
 */
object ProviderStore {

    private const val PREFS = "hermes_llama"
    private const val KEY_KEYS = "provider_keys_v1"
    private const val KEY_SEL = "provider_selected_v1"

    data class KeyEntry(val providerId: String, val label: String, val apiKey: String)

    fun providersById(): Map<String, CloudProviders.Provider> =
        CloudProviders.ALL.associateBy { it.id }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- keys ---------------------------------------------------------------

    fun keys(context: Context): MutableList<KeyEntry> = runCatching {
        val raw = prefs(context).getString(KEY_KEYS, null) ?: return@runCatching mutableListOf()
        val arr = JSONArray(raw)
        val out = mutableListOf<KeyEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(KeyEntry(o.optString("pid"), o.optString("label"), o.optString("key")))
        }
        out
    }.getOrDefault(mutableListOf())

    private fun saveKeys(context: Context, list: List<KeyEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("pid", it.providerId).put("label", it.label).put("key", it.apiKey))
        }
        prefs(context).edit().putString(KEY_KEYS, arr.toString()).apply()
    }

    fun addKey(context: Context, providerId: String, label: String, apiKey: String) {
        if (apiKey.isBlank()) return
        val list = keys(context)
        list.add(KeyEntry(providerId, label.ifBlank { "key ${list.count { it.providerId == providerId } + 1 }" }, apiKey))
        saveKeys(context, list)
    }

    fun removeKey(context: Context, entry: KeyEntry) {
        val list = keys(context)
        list.removeAll { it.providerId == entry.providerId && it.apiKey == entry.apiKey }
        saveKeys(context, list)
    }

    fun keysFor(context: Context, providerId: String): List<KeyEntry> =
        keys(context).filter { it.providerId == providerId }

    // --- model selection ----------------------------------------------------

    fun selectedModels(context: Context): MutableSet<String> = runCatching {
        val raw = prefs(context).getString(KEY_SEL, null) ?: return@runCatching mutableSetOf()
        val arr = JSONArray(raw)
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        out
    }.getOrDefault(mutableSetOf())

    private fun saveSelected(context: Context, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_SEL, arr.toString()).apply()
    }

    fun isSelected(context: Context, providerId: String, modelId: String): Boolean =
        "$providerId::$modelId" in selectedModels(context)

    /** @return the new selected state for that model. */
    fun toggleSelected(context: Context, providerId: String, modelId: String): Boolean {
        val set = selectedModels(context)
        val key = "$providerId::$modelId"
        val now = if (key in set) { set.remove(key); false } else { set.add(key); true }
        saveSelected(context, set)
        return now
    }
}