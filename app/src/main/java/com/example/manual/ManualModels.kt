package com.example.manual

import org.json.JSONObject

/** One TOC node of the manual tree (portal .trees format: label / linkTarget / children). */
data class ManualTocNode(
    val label: String,
    val linkTarget: String?,
    val children: List<ManualTocNode>
)

/** Flattened TOC row for list rendering. */
data class FlatNode(
    val pathId: String,
    val parentId: String?,
    val depth: Int,
    val label: String,
    val linkTarget: String?,
    val hasChildren: Boolean
)

/** A cached, ready-to-render manual. */
data class ManualBook(
    val manualId: String,
    val title: String,
    val uiLanguage: String,
    val roots: List<ManualTocNode>
)

/** An image reference extracted from a section's bodyHtml. */
data class MediaRef(val key: String, val lang: String?)

/** Local search result: either a TOC label hit or a cached-section body hit. */
data class SearchHit(
    val linkTarget: String?,
    val label: String,
    val snippet: String,
    val fromBody: Boolean
)

/**
 * Pure JSON/HTML helpers for the portal payloads - no Android types, fully unit-tested.
 */
object ManualJson {

    private const val NULL_STR = "null"

    fun stripTags(html: String): String = html
        .replace(Regex("(?s)<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun nodeOrNull(obj: JSONObject?): ManualTocNode? {
        if (obj == null) return null
        val label = stripTags(obj.optString("label", "").ifBlank { "" })
        val link = obj.optString("linkTarget", NULL_STR)
            .ifBlank { null }
            ?.takeIf { it != NULL_STR }
        val childrenArr = obj.optJSONArray("children")
        val children = if (childrenArr == null) emptyList()
        else (0 until childrenArr.length()).mapNotNull { nodeOrNull(childrenArr.optJSONObject(it)) }
        if (label.isBlank() && link == null && children.isEmpty()) return null
        return ManualTocNode(label, link, children)
    }

    /** Parse the .trees array of the V6/topic response into TOC roots. */
    fun parseTrees(treeJson: String): List<ManualTocNode> {
        return try {
            val root = JSONObject(treeJson)
            val trees = root.optJSONArray("trees") ?: return emptyList()
            (0 until trees.length()).mapNotNull { nodeOrNull(trees.optJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Depth-first flatten with stable path ids ("0", "0.1", "0.1.2"...). */
    fun flatten(roots: List<ManualTocNode>): List<FlatNode> {
        val out = mutableListOf<FlatNode>()
        fun walk(nodes: List<ManualTocNode>, parentId: String?, depth: Int) {
            nodes.forEachIndexed { i, n ->
                val pathId = if (parentId == null) "$i" else "$parentId.$i"
                out += FlatNode(pathId, parentId, depth, n.label, n.linkTarget, n.children.isNotEmpty())
                walk(n.children, pathId, depth + 1)
            }
        }
        walk(roots, null, 0)
        return out
    }

    /** .results[0].topicId from the welcome-search response. */
    fun firstTopicId(searchJson: String): String? {
        return try {
            val results = JSONObject(searchJson).optJSONArray("results") ?: return null
            val first = results.optJSONObject(0) ?: return null
            first.optString("topicId", NULL_STR).takeIf { it.isNotBlank() && it != NULL_STR }
        } catch (e: Exception) {
            null
        }
    }

    /** .bodyHtml of a vw-topic section response, stripped of the surrounding html document. */
    fun bodyHtml(sectionJson: String): String? {
        return try {
            val html = JSONObject(sectionJson).optString("bodyHtml", "")
            if (html.isBlank()) null else cleanDocument(html)
        } catch (e: Exception) {
            null
        }
    }

    /** Remove DOCTYPE/html/head/body wrappers so only content markup remains. */
    fun cleanDocument(html: String): String = html
        .replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
        .replace(Regex("(?is)<head>.*?</head>"), "")
        .replace(Regex("(?is)</?html[^>]*>"), "")
        .replace(Regex("(?is)</?body[^>]*>"), "")
        .trim()

    private val MEDIA_SRC = Regex("""(?:data-src|src)="([^"]*?/public/media\?[^"]*?)"""")
    private val KEY_PARAM = Regex("""[?&]key=([^&"]+)""")
    private val LANG_PARAM = Regex("""[?&]lang=([^&"]+)""")

    /**
     * All distinct media keys referenced by a section. The 2026 platform emits absolute
     * lazy-load URLs: data-src="https://digital-manual.skoda-auto.com/default/public/media?lang=X&amp;key=K".
     */
    fun mediaRefs(html: String): List<MediaRef> {
        return MEDIA_SRC.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1].replace("&amp;", "&")
            val key = KEY_PARAM.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaRef(key, LANG_PARAM.find(url)?.groupValues?.get(1))
        }.distinctBy { it.key }.toList()
    }

    /** Rewrite lazy-load data-src attributes so a JS-disabled WebView actually loads images. */
    fun activateImages(html: String): String = html.replace("data-src=", "src=")

    /** Wrap section markup in a dark-theme-friendly standalone document. */
    fun wrapForDisplay(bodyHtml: String, title: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta charset="utf-8">
        <style>
        body{background:transparent;color:#DCE6EC;font-family:Roboto,sans-serif;font-size:15px;line-height:1.55;margin:12px}
        img{max-width:100%;height:auto}
        table{width:100%;border-collapse:collapse;margin:8px 0}
        td,th{border:1px solid #33474F;padding:5px 6px;text-align:left;vertical-align:top}
        th{color:#00E5FF;font-weight:600}
        a{color:#00E5FF}
        h1,h2,h3{color:#FFFFFF;line-height:1.3}
        ul,ol{padding-left:20px}
        </style>
        </head><body>$bodyHtml</body></html>
    """.trimIndent()

    /** Magic-byte MIME sniffing - portal media keys are GUIDs with no file extension. */
    fun guessMime(bytes: ByteArray): String {
        if (bytes.size < 12) return "application/octet-stream"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
            String(bytes, 0, 6) == "GIF87a" || String(bytes, 0, 6) == "GIF89a" -> "image/gif"
            String(bytes, 0, 4) == "%PDF" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /** ~90-char context window around the first case-insensitive match, for search results. */
    fun snippet(text: String, query: String): String {
        val idx = text.lowercase().indexOf(query.lowercase())
        if (idx < 0) return text.take(90)
        val start = maxOf(0, idx - 30)
        val end = minOf(text.length, idx + query.length + 60)
        return (if (start > 0) "..." else "") + text.substring(start, end) + (if (end < text.length) "..." else "")
    }
}
