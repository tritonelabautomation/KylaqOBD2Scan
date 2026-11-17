package com.example.manual

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Private on-device cache of the owner's manual (personal offline use).
 *
 * Layout under filesDir/manual_portal:
 *   last_connect.json      identifier / config / language / manualId / title
 *   tree_<lang>.json       full TOC tree per language
 *   sections/<sha256>.json {"linkTarget":..,"payload":<raw vw-topic JSON>}
 *   media/<sha256>.bin     raw image bytes
 *
 * Filenames are SHA-256 hashes of portal keys - keys can contain '?', '&', '=' and '/'.
 * Section files keep the original linkTarget inside the wrapper so cached-body search
 * can still open the right section offline.
 */
class ManualCache(private val root: File) {

    data class LastConnect(
        val identifier: String,
        val configLabel: String,
        val uiLanguage: String,
        val manualId: String,
        val title: String,
        val savedAtMs: Long
    )

    private val sectionsDir = File(root, "sections")
    private val mediaDir = File(root, "media")

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun treeFile(lang: String) = File(root, "tree_" + lang.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    init {
        root.mkdirs()
        sectionsDir.mkdirs()
        mediaDir.mkdirs()
    }

    fun saveTree(lang: String, json: String) {
        runCatching { treeFile(lang).writeText(json) }
    }

    fun loadTree(lang: String): String? =
        treeFile(lang).takeIf { it.exists() && it.length() > 0 }?.let { runCatching { it.readText() }.getOrNull() }

    fun treeLangs(): List<String> =
        root.listFiles { f -> f.name.startsWith("tree_") && f.name.endsWith(".json") }
            ?.map { it.name.removePrefix("tree_").removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()

    fun saveSection(linkTarget: String, json: String) {
        runCatching {
            val wrapper = JSONObject().put("linkTarget", linkTarget).put("payload", json)
            File(sectionsDir, fileName(linkTarget) + ".json").writeText(wrapper.toString())
        }
    }

    fun loadSection(linkTarget: String): String? = readSectionFile(File(sectionsDir, fileName(linkTarget) + ".json"))?.second

    fun hasSection(linkTarget: String): Boolean = File(sectionsDir, fileName(linkTarget) + ".json").exists()

    /** Every cached section as (linkTarget, raw payload) - used by offline full-text search. */
    fun allSections(): List<Pair<String, String>> =
        sectionsDir.listFiles()?.mapNotNull { readSectionFile(it) } ?: emptyList()

    private fun readSectionFile(f: File): Pair<String, String>? {
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val o = JSONObject(f.readText())
            o.optString("linkTarget") to o.optString("payload")
        }.getOrNull()?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
    }

    fun sectionCount(): Int = sectionsDir.listFiles()?.size ?: 0

    fun saveMedia(key: String, bytes: ByteArray) {
        runCatching { File(mediaDir, fileName(key) + ".bin").writeBytes(bytes) }
    }

    fun loadMedia(key: String): ByteArray? =
        File(mediaDir, fileName(key) + ".bin")
            .takeIf { it.exists() && it.length() > 0 }
            ?.let { runCatching { it.readBytes() }.getOrNull() }

    fun hasMedia(key: String): Boolean = File(mediaDir, fileName(key) + ".bin").exists()

    fun mediaCount(): Int = mediaDir.listFiles()?.size ?: 0

    fun saveLastConnect(info: LastConnect) {
        runCatching {
            File(root, "last_connect.json").writeText(
                JSONObject()
                    .put("identifier", info.identifier)
                    .put("configLabel", info.configLabel)
                    .put("uiLanguage", info.uiLanguage)
                    .put("manualId", info.manualId)
                    .put("title", info.title)
                    .put("savedAtMs", info.savedAtMs)
                    .toString()
            )
        }
    }

    fun loadLastConnect(): LastConnect? =
        File(root, "last_connect.json")
            .takeIf { it.exists() }
            ?.let { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    LastConnect(
                        identifier = o.optString("identifier"),
                        configLabel = o.optString("configLabel"),
                        uiLanguage = o.optString("uiLanguage"),
                        manualId = o.optString("manualId"),
                        title = o.optString("title", "Owner's Manual"),
                        savedAtMs = o.optLong("savedAtMs")
                    )
                }.getOrNull()
            }

    fun approxSizeBytes(): Long {
        fun dirSize(d: File): Long = d.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
        return dirSize(root)
    }

    fun clear() {
        sectionsDir.listFiles()?.forEach { runCatching { it.delete() } }
        mediaDir.listFiles()?.forEach { runCatching { it.delete() } }
        root.listFiles { f -> f.isFile }?.forEach { runCatching { it.delete() } }
    }
}
