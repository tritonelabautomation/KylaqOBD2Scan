package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.manual.FlatNode
import com.example.manual.ManualBook
import com.example.manual.ManualJson
import com.example.manual.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class ManualPhase { DISCONNECTED, CONNECTING, READY, OFFLINE_READY, ERROR }

data class ManualUiState(
    val phase: ManualPhase = ManualPhase.DISCONNECTED,
    val identifier: String = "",
    val language: String = "en_IN",
    val bookTitle: String? = null,
    val configLabel: String? = null,
    val visibleNodes: List<FlatNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val openLinkTarget: String? = null,
    val openLabel: String? = null,
    val openHtml: String? = null,
    val sectionLoading: Boolean = false,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val downloading: Boolean = false,
    val downloadDone: Int = 0,
    val downloadTotal: Int = 0,
    val downloadLabel: String = "",
    val statusLine: String = "",
    val errorMessage: String? = null,
    val offlineInfo: String = "",
    val cacheAvailable: Boolean = false
)

/**
 * Owner's Manual screen state. Talks to ManualRepository (official Skoda digital-manual
 * portal). UI honesty rules honoured: every string shown comes from the portal payload or
 * from this app's own cache bookkeeping - nothing is synthesised while disconnected except
 * the explicit OFFLINE CACHE badge over genuinely cached content.
 */
class ManualViewModel(app: Application) : AndroidViewModel(app) {

    init { AppContainer.init(app) }

    private val repo = AppContainer.manualRepository
    val state = MutableStateFlow(ManualUiState())

    private var book: ManualBook? = null
    private var allFlat: List<FlatNode> = emptyList()
    private var downloadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // VIN prefill from the garage (first vehicle with a valid 17-char VIN).
            val vin = runCatching { repo.knownVin() }.getOrNull()
            val cached = runCatching { repo.cachedBook() }.getOrNull()
            val lastId = repo.lastIdentifier()
            val info = repo.offlineInfo()
            val s = state.value
            state.value = s.copy(
                identifier = s.identifier.ifBlank { vin ?: lastId ?: "" },
                cacheAvailable = cached != null,
                offlineInfo = info
            )
            if (cached != null && state.value.phase == ManualPhase.DISCONNECTED) {
                withContext(Dispatchers.Main) { applyBook(cached, offline = true, info = info) }
            }
        }
    }

    private fun upd(block: (ManualUiState) -> ManualUiState) {
        state.value = block(state.value)
    }

    fun setIdentifier(v: String) = upd { it.copy(identifier = v, errorMessage = null) }

    fun setLanguage(v: String) = upd { it.copy(language = v) }

    fun connect() {
        val id = state.value.identifier
        upd { it.copy(phase = ManualPhase.CONNECTING, errorMessage = null, statusLine = "Creating portal session...") }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = repo.connect(id, state.value.language)
            // Disk reads stay on IO; only state mutation hops to Main.
            val cacheAvail = repo.cachedBook() != null
            val info = repo.offlineInfo()
            withContext(Dispatchers.Main) {
                if (outcome.book != null) {
                    applyBook(outcome.book, offline = false, outcome.configLabel, info)
                } else {
                    upd {
                        it.copy(
                            phase = ManualPhase.ERROR,
                            errorMessage = outcome.error,
                            statusLine = "",
                            cacheAvailable = cacheAvail,
                            offlineInfo = info
                        )
                    }
                }
            }
        }
    }

    fun useCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = repo.cachedBook() ?: return@launch
            val info = repo.offlineInfo()
            withContext(Dispatchers.Main) { applyBook(cached, offline = true, info = info) }
        }
    }

    private fun applyBook(b: ManualBook, offline: Boolean, configLabel: String? = null, info: String = "") {
        book = b
        allFlat = ManualJson.flatten(b.roots)
        val rootExpanded = allFlat.filter { it.depth == 0 && it.hasChildren }.map { it.pathId }.toSet()
        upd {
            it.copy(
                phase = if (offline) ManualPhase.OFFLINE_READY else ManualPhase.READY,
                bookTitle = b.title,
                configLabel = configLabel ?: it.configLabel,
                expanded = rootExpanded,
                openLinkTarget = null,
                openLabel = null,
                openHtml = null,
                searchHits = emptyList(),
                statusLine = if (offline) "Loaded from private offline cache" else "Live session - ${allFlat.size} topic rows",
                // ifBlank takes a Function0 - no inner `it`, so this reads the STATE's offlineInfo.
                offlineInfo = info.ifBlank { it.offlineInfo },
                cacheAvailable = true,
                errorMessage = null
            )
        }
        recomputeVisible()
    }

    private fun recomputeVisible() {
        val expanded = state.value.expanded
        val visibleIds = mutableSetOf<String>()
        val visible = mutableListOf<FlatNode>()
        for (n in allFlat) {
            val show = n.parentId == null || (n.parentId in expanded && n.parentId in visibleIds)
            if (show) {
                visible += n
                visibleIds += n.pathId
            }
        }
        upd { it.copy(visibleNodes = visible) }
    }

    fun toggleExpand(n: FlatNode) {
        if (!n.hasChildren) return
        upd {
            val expanded = if (n.pathId in it.expanded) it.expanded - n.pathId else it.expanded + n.pathId
            it.copy(expanded = expanded)
        }
        recomputeVisible()
    }

    fun onNodeClick(n: FlatNode) {
        if (n.linkTarget != null) openSection(n.linkTarget, n.label)
        else toggleExpand(n)
    }

    fun openSection(linkTarget: String, label: String) {
        upd { it.copy(sectionLoading = true, openLinkTarget = linkTarget, openLabel = label, openHtml = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val lang = repo.client.activeConfig?.uiLanguage ?: book?.uiLanguage ?: state.value.language
            val html = repo.sectionHtml(linkTarget, lang)
            withContext(Dispatchers.Main) {
                upd {
                    it.copy(
                        sectionLoading = false,
                        openHtml = html?.let { h -> ManualJson.wrapForDisplay(h, label) },
                        statusLine = if (html == null) "Section unavailable - needs a live session or the offline download." else it.statusLine
                    )
                }
            }
        }
    }

    fun closeSection() = upd { it.copy(openLinkTarget = null, openLabel = null, openHtml = null) }

    /**
     * Blocking media lookup for WebViewClient.shouldInterceptRequest - that callback runs
     * on the WebView's own background thread, never on the main thread.
     */
    fun mediaBlocking(key: String, lang: String?): ByteArray? = runBlocking { repo.mediaBytes(key, lang) }

    fun setSearchMode(on: Boolean) = upd {
        it.copy(searchMode = on, searchQuery = if (on) it.searchQuery else "", searchHits = emptyList())
    }

    fun setSearchQuery(q: String) {
        upd { it.copy(searchQuery = q) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            upd { it.copy(searchHits = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            upd { it.copy(searching = true) }
            val hits = repo.searchLocal(q, book)
            withContext(Dispatchers.Main) { upd { it.copy(searchHits = hits, searching = false) } }
        }
    }

    fun openHit(hit: SearchHit) {
        if (hit.linkTarget != null) openSection(hit.linkTarget, hit.label)
    }

    fun startDownloadAll() {
        val b = book ?: return
        if (state.value.downloading) return
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            upd { it.copy(downloading = true, downloadDone = 0, downloadTotal = 0, downloadLabel = "Preparing...") }
            val summary = repo.downloadAll(b) { done, total, label ->
                upd { it.copy(downloadDone = done, downloadTotal = total, downloadLabel = label) }
            }
            withContext(Dispatchers.Main) {
                upd {
                    it.copy(
                        downloading = false,
                        offlineInfo = repo.offlineInfo(),
                        statusLine = if (summary.failures == -1) {
                            "Download needs a live portal session - connect with your VIN first."
                        } else {
                            "Offline pack ready: ${summary.sections} sections, ${summary.images} images" +
                                (if (summary.failures > 0) ", ${summary.failures} failed (retry to resume)" else "")
                        }
                    )
                }
            }
        }
    }

    fun stopDownload() {
        downloadJob?.cancel()
        downloadJob = null
        upd { it.copy(downloading = false, statusLine = "Download stopped - cached parts stay available offline.") }
    }

    fun clearCache() {
        downloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearCache()
            withContext(Dispatchers.Main) {
                book = null
                allFlat = emptyList()
                upd {
                    ManualUiState(
                        identifier = it.identifier,
                        language = it.language,
                        statusLine = "Offline cache cleared."
                    )
                }
            }
        }
    }

    val portalUrl: String get() = com.example.manual.ManualRepository.PORTAL_URL
}
