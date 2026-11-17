package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.manual.ManualJson
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.ManualPhase
import com.example.ui.viewmodel.ManualViewModel
import java.io.ByteArrayInputStream

/**
 * Owner's Manual - official Skoda digital manual, in-app.
 *
 * The portal content is streamed from Skoda's servers with the owner's own VIN (the same
 * handshake the skoda-auto.co.in web portal performs) and cached privately on-device for
 * offline personal use. Nothing is bundled in the APK. (c) Skoda Auto a.s.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerManualScreen(
    onBack: () -> Unit,
    vm: ManualViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = state.openHtml != null || state.openLinkTarget != null) { vm.closeSection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Owner's Manual", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CyberCyan)
                    }
                },
                actions = {
                    val bookReady = state.phase == ManualPhase.READY || state.phase == ManualPhase.OFFLINE_READY
                    if (bookReady && !state.downloading) {
                        IconButton(onClick = { vm.startDownloadAll() }) {
                            Icon(Icons.Default.CloudDownload, "Download for offline", tint = NeonEmerald)
                        }
                    }
                    if (state.downloading) {
                        IconButton(onClick = { vm.stopDownload() }) {
                            Icon(Icons.Default.Stop, "Stop download", tint = WarningRed)
                        }
                    }
                    if (bookReady) {
                        IconButton(onClick = { vm.setSearchMode(!state.searchMode) }) {
                            Icon(Icons.Default.Search, "Search manual", tint = if (state.searchMode) NeonEmerald else CyberCyan)
                        }
                    }
                    if (state.cacheAvailable || state.phase != ManualPhase.DISCONNECTED) {
                        IconButton(onClick = { vm.clearCache() }) {
                            Icon(Icons.Default.DeleteSweep, "Clear offline cache", tint = TextSecondaryDark)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.openLinkTarget != null -> SectionViewer(vm = vm)
                else -> Column(Modifier.fillMaxSize()) {
                    ConnectCard(vm = vm, onOpenPortal = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.portalUrl)))
                        }
                    })
                    if (state.downloading) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            val frac = if (state.downloadTotal > 0) {
                                state.downloadDone.toFloat() / state.downloadTotal.toFloat()
                            } else 0f
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = NeonEmerald
                            )
                            Text(
                                "Offline pack ${state.downloadDone}/${state.downloadTotal} - ${state.downloadLabel}",
                                color = TextSecondaryDark, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (state.searchMode) {
                        SearchPanel(vm = vm)
                    } else {
                        TocList(vm = vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(vm: ManualViewModel, onOpenPortal: () -> Unit) {
    val state by vm.state.collectAsState()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Official Skoda digital manual",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                when (state.phase) {
                    ManualPhase.READY -> StatusPill("LIVE SESSION", NeonEmerald)
                    ManualPhase.OFFLINE_READY -> StatusPill("OFFLINE CACHE", CyberCyan)
                    ManualPhase.CONNECTING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CyberCyan)
                    ManualPhase.ERROR -> StatusPill("NO SESSION", WarningRed)
                    ManualPhase.DISCONNECTED -> StatusPill("NOT CONNECTED", TextSecondaryDark)
                }
            }
            if (state.bookTitle != null) {
                Text(state.bookTitle ?: "", color = CyberCyan, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (state.openLinkTarget == null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.identifier,
                    onValueChange = vm::setIdentifier,
                    label = { Text("VIN or manual part number") },
                    placeholder = { Text("e.g. TMBJG6NW8S5012345 or 657012738AR") },
                    singleLine = true,
                    enabled = state.phase != ManualPhase.CONNECTING,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en_IN" to "English (IN)", "en_GB" to "English (GB)").forEach { (code, label) ->
                        FilterChip(
                            selected = state.language == code,
                            onClick = { vm.setLanguage(code) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberCyan.copy(alpha = 0.18f),
                                selectedLabelColor = CyberCyan
                            )
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = vm::connect,
                        enabled = state.phase != ManualPhase.CONNECTING && state.identifier.trim().length >= 6
                    ) {
                        Text(if (state.phase == ManualPhase.CONNECTING) "Connecting..." else "Connect to portal")
                    }
                    if (state.cacheAvailable && state.phase != ManualPhase.READY && state.phase != ManualPhase.OFFLINE_READY) {
                        OutlinedButton(onClick = vm::useCache) { Text("Use offline cache") }
                    }
                    TextButton(onClick = onOpenPortal) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp), tint = CyberCyan)
                        Spacer(Modifier.width(4.dp))
                        Text("Official portal", fontSize = 11.sp, color = CyberCyan)
                    }
                }
            }
            if (state.errorMessage != null) {
                Text(
                    state.errorMessage ?: "",
                    color = WarningRed, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (state.statusLine.isNotBlank()) {
                Text(state.statusLine, color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (state.offlineInfo.isNotBlank() && state.cacheAvailable) {
                Text(state.offlineInfo, color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                "Content (c) Skoda Auto a.s. - streamed from the official digital-manual portal with your own VIN " +
                    "and stored privately on this device for personal offline use. The app bundles no manual content.",
                color = TextSecondaryDark.copy(alpha = 0.75f), fontSize = 9.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun TocList(vm: ManualViewModel) {
    val state by vm.state.collectAsState()
    if (state.phase != ManualPhase.READY && state.phase != ManualPhase.OFFLINE_READY) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    "Connect with your VIN to browse the official manual,\nor load the offline cache.",
                    color = TextSecondaryDark, fontSize = 12.sp
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(state.visibleNodes, key = { it.pathId }) { n ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.onNodeClick(n) }
                    .padding(start = (10 + n.depth * 14).dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
            ) {
                if (n.hasChildren) {
                    // Chevron toggles without opening the section body (nodes can have both).
                    Icon(
                        if (n.pathId in state.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        "Expand",
                        Modifier.size(22.dp).clickable { vm.toggleExpand(n) },
                        tint = CyberCyan
                    )
                } else {
                    Spacer(Modifier.width(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    n.label,
                    color = if (n.linkTarget != null) Color.White else CyberCyan,
                    fontSize = if (n.depth == 0) 14.sp else 12.5.sp,
                    fontWeight = if (n.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(vm: ManualViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = vm::setSearchQuery,
            label = { Text("Search TOC + cached pages") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { vm.setSearchMode(false) }) { Icon(Icons.Default.Close, "Close search", tint = TextSecondaryDark) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            if (state.searching) "Searching..."
            else "Deep page search covers sections already in the offline cache (cloud-download icon fetches everything).",
            color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 18.dp)
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.searchHits) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hit.linkTarget != null) { vm.openHit(hit) }
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hit.label.ifBlank { hit.linkTarget ?: "" },
                            color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (hit.fromBody) "PAGE" else "TOC",
                            color = if (hit.fromBody) NeonEmerald else CyberCyan, fontSize = 9.sp
                        )
                    }
                    Text(hit.snippet, color = TextSecondaryDark, fontSize = 11.sp)
                }
            }
            if (!state.searching && state.searchQuery.trim().length >= 2 && state.searchHits.isEmpty()) {
                item {
                    Text("No matches yet.", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionViewer(vm: ManualViewModel) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = { vm.closeSection() }) { Icon(Icons.Default.Close, "Close section", tint = CyberCyan) }
            Text(
                state.openLabel ?: "",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
        }
        when {
            state.sectionLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyberCyan)
            }
            state.openHtml == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Section not available.\nConnect to the portal or download the offline pack first.",
                    color = TextSecondaryDark, fontSize = 12.sp
                )
            }
            else -> ManualWebView(html = state.openHtml ?: "", vm = vm)
        }
    }
}

/**
 * JS-disabled WebView over portal markup. Image requests (/public/media?...key=) are
 * intercepted and served from the private cache (or the portal when online), so sections
 * render identically offline. shouldInterceptRequest runs on a WebView background thread,
 * hence the blocking media lookup is safe here.
 */
@Composable
private fun ManualWebView(html: String, vm: ManualViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url ?: return null
                        if (!url.path.orEmpty().endsWith("/public/media")) return null
                        val key = url.getQueryParameter("key") ?: return null
                        val lang = url.getQueryParameter("lang")
                        val bytes = vm.mediaBlocking(key, lang) ?: return null
                        return WebResourceResponse(ManualJson.guessMime(bytes), "binary", ByteArrayInputStream(bytes))
                    }
                }
            }
        },
        update = { wv ->
            // Load only when the document actually changed (recomposition-safe).
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(
                    "https://digital-manual.skoda-auto.com/",
                    html, "text/html", "utf-8", null
                )
            }
        }
    )
}
