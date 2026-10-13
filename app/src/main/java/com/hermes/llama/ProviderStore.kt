package com.hermes.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * BYOK provider state:
 *  - any number of API keys per provider (label + secret)
 *  - a per-model selected/unselected set; selected models surface on the Models page
 *  - a per-provider editable base URL (endpoints change)
 *  - user-defined custom providers (OpenAI-compatible endpoints not in the built-in list)
 *
 * Persisted as JSON in the shared "hermes_llama" preferences.
 */
object ProviderStore {

    private const val PREFS = "hermes_llama"
    private const val KEY_KEYS = "provider_keys_v1"
    private const val KEY_SEL = "provider_selected_v1"
    private const val KEY_BASEURL = "provider_baseurl_v1"
    private const val KEY_CUSTOM = "provider_custom_v1"

    data class KeyEntry(val providerId: String, val label: String, val apiKey: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- provider catalogue -------------------------------------------------

    /** Base-URL overrides: providerId -> url. */
    fun baseUrlOverrides(context: Context): MutableMap<String, String> = runCatching {
        val raw = prefs(context).getString(KEY_BASEURL, null) ?: return@runCatching mutableMapOf()
        val o = JSONObject(raw)
        val out = mutableMapOf<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        out
    }.getOrDefault(mutableMapOf())

    fun setBaseUrlOverride(context: Context, providerId: String, url: String) {
        val map = baseUrlOverrides(context)
        if (url.isBlank()) map.remove(providerId) else map[providerId] = url.trim()
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs(context).edit().putString(KEY_BASEURL, o.toString()).apply()
    }

    /** Custom (user-added) OpenAI-compatible providers. */
    fun customProviders(context: Context): MutableList<CloudProviders.Provider> = runCatching {
        val raw = prefs(context).getString(KEY_CUSTOM, null) ?: return@runCatching mutableListOf()
        val arr = JSONArray(raw)
        val out = mutableListOf<CloudProviders.Provider>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CloudProviders.Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    authPrefix = o.optString("authPrefix", "Bearer "),
                    custom = true
                )
            )
        }
        out
    }.getOrDefault(mutableListOf())

    private fun saveCustom(context: Context, list: List<CloudProviders.Provider>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("baseUrl", it.baseUrl).put("authPrefix", it.authPrefix)
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    /** Add a custom provider; returns the created provider (id is a slug + suffix). */
    fun addCustomProvider(context: Context, name: String, baseUrl: String, authPrefix: String): CloudProviders.Provider {
        val slug = name.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "custom" } +
            "_" + (System.currentTimeMillis() % 100000)
        val p = CloudProviders.Provider(
            id = "custom:$slug",
            name = name.ifBlank { "Custom" },
            baseUrl = baseUrl.trim().trimEnd('/'),
            authPrefix = authPrefix.ifBlank { "Bearer " },
            custom = true
        )
        val list = customProviders(context)
        list.add(p)
        saveCustom(context, list)
        return p
    }

    fun removeCustomProvider(context: Context, id: String) {
        val list = customProviders(context)
        list.removeAll { it.id == id }
        saveCustom(context, list)
    }

    /** Built-in providers with any base-URL override applied. */
    fun builtins(context: Context): List<CloudProviders.Provider> {
        val overrides = baseUrlOverrides(context)
        return CloudProviders.ALL.map { p -> overrides[p.id]?.let { p.copy(baseUrl = it) } ?: p }
    }

    /** Every provider the user can use: built-ins (overridden) + customs. */
    fun allProviders(context: Context): List<CloudProviders.Provider> =
        builtins(context) + customProviders(context)

    fun resolve(context: Context, id: String): CloudProviders.Provider? =
        allProviders(context).firstOrNull { it.id == id }

    fun providersById(context: Context): Map<String, CloudProviders.Provider> =
        allProviders(context).associateBy { it.id }

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