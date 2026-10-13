package com.hermes.llama

import android.content.Context
import java.io.File

/**
 * Discovers models the app can use, from two sources:
 *  - LOCAL: every *.gguf under the configured scan roots, with its mmproj
 *    projector auto-paired from the same folder.
 *  - CLOUD: every model the user has explicitly selected in the Providers screen.
 *
 * This is what makes the Models page a real library: the user picks a model once
 * and loads it, instead of re-opening the file picker every time.
 */
object ModelRepository {

    /** Where we look for GGUF files by default (user can add more in Settings). */
    fun defaultRoots(): List<String> = listOf(
        "/sdcard/Download/Hermes/models",
        "/sdcard/Download/models",
        "/sdcard/models",
        "/sdcard/Android/data/com.hermes.llama/files/models"
    )

    /** Scan roots from preferences, falling back to the defaults. */
    fun rootsFrom(context: Context): List<String> =
        context.getSharedPreferences("hermes_llama", Context.MODE_PRIVATE)
            .getStringSet("model_roots", null)?.toList()?.takeIf { it.isNotEmpty() }
            ?: defaultRoots()

    data class Entry(
        val id: String,
        val name: String,
        val subtitle: String,
        val source: String,          // "local" | "cloud"
        val sizeBytes: Long = 0,
        val path: String? = null,    // local model file
        val projector: String? = null, // local mmproj
        val providerId: String? = null,
        val providerName: String? = null,
        val modelId: String? = null
    ) {
        val isLocal: Boolean get() = source == "local"
    }

    /** A projector (mmproj / clip) file vs. an actual LLM. */
    fun isProjector(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("mmproj") || n.contains("projector") || n.contains("clip")
    }

    private fun stem(name: String): String =
        name.substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() || it == ' ' }

    /** Longest-common-prefix score so "...-mmproj-Q8_0" pairs with its model. */
    private fun affinity(model: String, projector: String): Int {
        val a = stem(model); val b = stem(projector)
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun bestProjector(model: File, projectors: List<File>): String? {
        val dir = model.parentFile?.absolutePath ?: return null
        val inDir = projectors.filter { it.parentFile?.absolutePath == dir }
        if (inDir.isEmpty()) return null
        return inDir.maxByOrNull { affinity(model.name, it.name) }?.absolutePath
    }

    /** Scan the roots for local models + projectors (depth-limited). */
    fun scanLocal(roots: List<String>, maxDepth: Int = 4): List<Entry> {
        val models = mutableListOf<File>()
        val projectors = mutableListOf<File>()
        roots.forEach { root ->
            val f = File(root)
            if (!f.isDirectory) return@forEach
            runCatching {
                f.walkTopDown().maxDepth(maxDepth).forEach { file ->
                    if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true)) {
                        if (isProjector(file.name)) projectors.add(file) else models.add(file)
                    }
                }
            }
        }
        return models.map { m ->
            Entry(
                id = "local:" + m.absolutePath,
                name = m.name,
                subtitle = m.parentFile?.name ?: "",
                source = "local",
                sizeBytes = m.length(),
                path = m.absolutePath,
                projector = bestProjector(m, projectors)
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** All projector (mmproj/clip) GGUF files under the roots, for explicit pairing. */
    fun scanProjectors(roots: List<String>, maxDepth: Int = 4): List<Entry> {
        val out = mutableListOf<Entry>()
        roots.forEach { root ->
            val f = File(root)
            if (!f.isDirectory) return@forEach
            runCatching {
                f.walkTopDown().maxDepth(maxDepth).forEach { file ->
                    if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true) && isProjector(file.name)) {
                        out.add(
                            Entry(
                                id = "proj:" + file.absolutePath,
                                name = file.name,
                                subtitle = file.parentFile?.name ?: "",
                                source = "projector",
                                sizeBytes = file.length(),
                                path = file.absolutePath
                            )
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Cloud entries for every model the user selected in the Providers screen. */
    fun cloudEntries(context: Context): List<Entry> {
        val sel = ProviderStore.selectedModels(context)
        if (sel.isEmpty()) return emptyList()
        val providers = ProviderStore.providersById(context)
        return sel.mapNotNull { key ->
            val parts = key.split("::")
            if (parts.size != 2) return@mapNotNull null
            val pid = parts[0]; val mid = parts[1]
            val p = providers[pid] ?: return@mapNotNull null
            Entry(
                id = "cloud:$key",
                name = mid,
                subtitle = p.name,
                source = "cloud",
                providerId = pid,
                providerName = p.name,
                modelId = mid
            )
        }.sortedWith(compareBy({ it.providerName ?: "" }, { it.name }))
    }

    /** All usable models: local first, then selected cloud models. */
    fun all(context: Context, roots: List<String>): List<Entry> =
        scanLocal(roots) + cloudEntries(context)

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}