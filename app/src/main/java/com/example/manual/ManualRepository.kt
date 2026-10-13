package com.example.manual

import android.content.Context
import com.example.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Orchestrates the official Skoda manual portal: session handshake (VIN or part number),
 * TOC tree, per-section fetch, private offline cache and local search.
 *
 * Content policy: manual text/images are NEVER bundled in the APK or committed to this
 * repo. They are streamed at runtime from Skoda's own servers to the owner's device with
 * the owner's own VIN - the same thing the official web portal does - and cached in the
 * app's private storage for personal offline use. (c) Skoda Auto a.s.
 */
class ManualRepository(context: Context) {

    private val appContext = context.applicationContext
    val cache = ManualCache(File(appContext.filesDir, "manual_portal"))
    private val http: ManualHttp = OkHttpManualHttp()
    val client = ManualPortalClient(http)

    data class ConnectOutcome(
        val book: ManualBook?,
        val configLabel: String?,
        val error: String?,
        val tried: List<String>
    )

    data class DownloadSummary(val sections: Int, val images: Int, val failures: Int)

    companion object {
        const val PORTAL_URL = "https://www.skoda-auto.co.in/apps/manuals/Models"
        private const val SECTION_DELAY_MS = 40L
        private const val IMAGE_DELAY_MS = 20L
        private const val SEARCH_BODY_LIMIT = 40
    }

    /**
     * Walk the importer/language matrix (preferred language first): create a session,
     * resolve the manual id, pull the TOC tree. First configuration that yields a
     * non-empty tree wins and is cached for offline restarts.
     */
    suspend fun connect(identifierRaw: String, preferredLanguage: String): ConnectOutcome =
        withContext(Dispatchers.IO) {
            val id = identifierRaw.trim().uppercase()
            if (id.length < 6) {
                return@withContext ConnectOutcome(
                    null, null,
                    "Enter the 17-character VIN (no I/O/Q) or a manual part number such as 657012738AR.",
                    emptyList()
                )
            }
            val tried = mutableListOf<String>()
            var lastError = "No manual session could be created."
            for (cfg in PortalConfig.orderFor(preferredLanguage)) {
                val tag = "${cfg.label} [importer ${cfg.importerId} / ${cfg.uiLanguage}]"
                tried += tag
                if (!client.initSession(id, cfg)) {
                    lastError = "Session refused (VIN/part number not recognised by this importer)."
                    continue
                }
                val manualId = client.findManualId()
                if (manualId == null) {
                    lastError = "Session created but the portal returned no manual for this VIN."
                    continue
                }
                val treeJson = client.fetchTree(manualId)
                val roots = treeJson?.let { ManualJson.parseTrees(it) } ?: emptyList()
                if (treeJson == null || roots.isEmpty()) {
                    lastError = "Manual found but its topic tree was empty or unreadable."
                    continue
                }
                cache.saveTree(cfg.uiLanguage, treeJson)
                val title = ManualJson.stripTags(roots.first().label).ifBlank { "Owner's Manual" }
                cache.saveLastConnect(
                    ManualCache.LastConnect(id, cfg.label, cfg.uiLanguage, manualId, title, System.currentTimeMillis())
                )
                return@withContext ConnectOutcome(ManualBook(manualId, title, cfg.uiLanguage, roots), cfg.label, null, tried)
            }
            ConnectOutcome(null, null, "$lastError Tried: ${tried.joinToString(" | ")}", tried)
        }

    /** Rebuild the book from the private cache (offline restart, airplane mode). */
    fun cachedBook(): ManualBook? {
        val last = cache.loadLastConnect() ?: return null
        val treeJson = cache.loadTree(last.uiLanguage) ?: return null
        val roots = ManualJson.parseTrees(treeJson)
        if (roots.isEmpty()) return null
        return ManualBook(last.manualId, last.title, last.uiLanguage, roots)
    }

    fun lastIdentifier(): String? = cache.loadLastConnect()?.identifier

    /** Ensure a live session using the stored identifier (after app restart) - best effort. */
    private suspend fun ensureSession(lang: String): Boolean {
        if (client.activeConfig != null) return true
        val last = cache.loadLastConnect() ?: return false
        val cfg = PortalConfig.ALL.firstOrNull { it.label == last.configLabel }
            ?: PortalConfig.orderFor(lang).firstOrNull()
            ?: return false
        return client.initSession(last.identifier, cfg)
    }

    /**
     * Section markup ready for the WebView: cache first, network second.
     * Returns activateImages()-rewritten HTML (data-src -> src, JS-disabled WebView).
     */
    suspend fun sectionHtml(linkTarget: String, lang: String): String? = withContext(Dispatchers.IO) {
        val cached = cache.loadSection(linkTarget)
        val json = cached ?: run {
            if (!ensureSession(lang)) return@withContext null
            client.fetchSectionJson(linkTarget, lang)?.also { cache.saveSection(linkTarget, it) }
        }
        json?.let { ManualJson.bodyHtml(it) }?.let { ManualJson.activateImages(it) }
    }

    /** Media bytes for the WebView interceptor: cache first, portal second. */
    suspend fun mediaBytes(key: String, lang: String?): ByteArray? = withContext(Dispatchers.IO) {
        cache.loadMedia(key) ?: run {
            val effectiveLang = lang ?: cache.loadLastConnect()?.uiLanguage ?: return@withContext null
            if (!ensureSession(effectiveLang)) return@withContext null
            client.fetchMedia(key, effectiveLang)?.also { cache.saveMedia(key, it) }
        }
    }

    /**
     * Download every section (and its images) into the private cache so the whole manual
     * works offline and full-text search covers every page. Cooperative: checks
     * coroutine cancellation between requests and throttles politely.
     */
    suspend fun downloadAll(
        book: ManualBook,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): DownloadSummary = withContext(Dispatchers.IO) {
        if (!ensureSession(book.uiLanguage)) {
            return@withContext DownloadSummary(0, 0, -1)
        }
        val targets = ManualJson.flatten(book.roots).filter { it.linkTarget != null }
        var done = 0
        var images = 0
        var failures = 0
        for (node in targets) {
            onProgress(done, targets.size, node.label)
            val key = node.linkTarget!!
            if (!cache.hasSection(key)) {
                val json = client.fetchSectionJson(key, book.uiLanguage)
                if (json == null) {
                    failures++
                } else {
                    cache.saveSection(key, json)
                    ManualJson.bodyHtml(json)?.let { html ->
                        for (ref in ManualJson.mediaRefs(html)) {
                            if (!cache.hasMedia(ref.key)) {
                                client.fetchMedia(ref.key, ref.lang ?: book.uiLanguage)?.let {
                                    cache.saveMedia(ref.key, it)
                                    images++
                                }
                                delay(IMAGE_DELAY_MS)
                            }
                        }
                    }
                }
                delay(SECTION_DELAY_MS)
            }
            done++
        }
        onProgress(targets.size, targets.size, "Complete")
        DownloadSummary(targets.size - failures, images, failures)
    }

    /**
     * Local search: TOC labels always, cached section bodies when present (complete after
     * "Download all"). No unverified server search endpoints are used - honest scope.
     */
    suspend fun searchLocal(query: String, book: ManualBook?): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val hits = mutableListOf<SearchHit>()
        book?.let { b ->
            ManualJson.flatten(b.roots).forEach { n ->
                if (n.label.contains(q, ignoreCase = true)) {
                    hits += SearchHit(n.linkTarget, n.label, ManualJson.snippet(n.label, q), fromBody = false)
                }
            }
        }
        for ((linkTarget, payload) in cache.allSections()) {
            if (hits.size >= SEARCH_BODY_LIMIT) break
            val html = ManualJson.bodyHtml(payload) ?: continue
            val text = ManualJson.stripTags(html)
            if (text.contains(q, ignoreCase = true)) {
                val label = ManualJson.stripTags(
                    runCatching { org.json.JSONObject(payload).optString("title", "") }.getOrDefault("")
                ).ifBlank { linkTarget.take(24) }
                hits += SearchHit(linkTarget, label, ManualJson.snippet(text, q), fromBody = true)
            }
        }
        hits.take(SEARCH_BODY_LIMIT + 40)
    }

    /** VIN prefill: first garage vehicle whose stored VIN looks like a real 17-char VIN. */
    suspend fun knownVin(): String? = withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.getInstance(appContext).newEntitiesDao().getAllVehicles().first()
                .mapNotNull { it.vin?.trim()?.uppercase() }
                .firstOrNull { PortalConfig.VIN_PATTERN.matches(it) }
        }.getOrNull()
    }

    fun offlineInfo(): String {
        val sections = cache.sectionCount()
        val images = cache.mediaCount()
        val mb = cache.approxSizeBytes() / (1024.0 * 1024.0)
        return "%d sections - %d images - %.1f MB cached".format(sections, images, mb)
    }

    fun clearCache() = cache.clear()
}
