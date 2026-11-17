package com.example.manual

import java.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Official Skoda digital manual portal client (2026 platform).
 *
 * Provenance / method (verified 2026-09-15):
 *  - original crawl flow: github.com/jypma/skoda-manual (Nov 2023, required hand-copied
 *    browser cookies; its /w/ show-URL path is now "Forbidden - access restricted")
 *  - working 2026 flow: github.com/King4s/skoda-manual fork (Feb 2026, pushed Sep 2026):
 *    the portal issues an anonymous per-VIN session through the SAME entrypoint API the
 *    skoda.dk / skoda-auto.co.in "Owner's Manuals" web apps use - no cookies to hunt,
 *    no login. Contract:
 *      POST /api/entrypoint/V1/direct/   form: vin=<17ch>|partNumber=<pn>&uiLanguage=<l>&importerId=<id>
 *      GET  /api/users/V1/getuser        -> {"anonymous":false} when the session is live
 *      GET  /api/web/V6/search?query=&facetfilters=topic-type_|_welcome&lang=<l>&page=0&pageSize=200
 *                                        -> .results[0].topicId = manual root key
 *      GET  /api/web/V6/topic?key=<manualId>&displaytype=topic&language=<l>&query=undefined
 *                                        -> .trees = full TOC (label/linkTarget/children)
 *      GET  /api/vw-topic/V1/topic?key=<linkTarget>&displaytype=desktop&language=<l>
 *                                        -> .bodyHtml = one section
 *      GET  /public/media?lang=<l>&key=<k> -> image bytes
 *    Session expiry marker in ANY payload: "An Authentication object was not found in
 *    the SecurityContext" -> re-POST the entrypoint and retry once.
 *
 * Legal posture: content is fetched at RUNTIME to the owner's device with the owner's own
 * VIN, exactly like the official web portal does, and cached privately for personal
 * offline use. No manual content is bundled in the APK or committed to this repository.
 */

/** One importer (market) configuration for the entrypoint handshake. */
data class PortalConfig(
    val importerId: String,
    val uiLanguage: String,
    val origin: String,
    val referer: String,
    val label: String
) {
    companion object {
        /** VIN = 17 chars, no I/O/Q (ISO 3779). Anything else is treated as a part number. */
        val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{17}$")

        /** India (bid 663 - visible in manual.skoda-auto.com/663/en-IN/... portal URLs). */
        val INDIA_EN_IN = PortalConfig(
            importerId = "663",
            uiLanguage = "en_IN",
            origin = "https://www.skoda-auto.co.in",
            referer = "https://www.skoda-auto.co.in/apps/manuals/Models",
            label = "India portal - English (IN)"
        )
        val INDIA_EN_GB = INDIA_EN_IN.copy(uiLanguage = "en_GB", label = "India portal - English (GB)")

        /** Denmark (004) - the configuration proven end-to-end by the King4s fork (Feb 2026). */
        val DK_EN_GB = PortalConfig(
            importerId = "004",
            uiLanguage = "en_GB",
            origin = "https://www.skoda.dk",
            referer = "https://www.skoda.dk/apps/manuals/Models",
            label = "Global fallback portal - English (GB)"
        )

        val ALL = listOf(INDIA_EN_IN, INDIA_EN_GB, DK_EN_GB)

        /** Preferred language first, then the rest of the matrix as fallbacks. */
        fun orderFor(preferredLanguage: String): List<PortalConfig> {
            val preferred = ALL.filter { it.uiLanguage == preferredLanguage }
            val rest = ALL.filterNot { it.uiLanguage == preferredLanguage }
            return preferred + rest
        }
    }
}

data class HttpResult(val code: Int, val body: String)

/** Minimal HTTP surface so the whole portal protocol is unit-testable without sockets. */
interface ManualHttp {
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): Boolean
    fun get(url: String, headers: Map<String, String>): HttpResult
    fun getBytes(url: String, headers: Map<String, String>): ByteArray?
}

/** OkHttp implementation with an in-memory session cookie jar (per app process). */
class OkHttpManualHttp : ManualHttp {

    private class SessionCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                val list = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store[url.host]?.toList().orEmpty() }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (k, v) -> header(k, v) }
        // Browser-like UA: the portal rejects obvious non-browser agents.
        header("User-Agent", UA)
        header("Accept", "application/json, text/plain, */*")
        return this
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): Boolean {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).withHeaders(headers).build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val request = Request.Builder().url(url).get().withHeaders(headers).build()
        return try {
            client.newCall(request).execute().use { resp ->
                HttpResult(resp.code, runCatching { resp.body?.string() }.getOrNull() ?: "")
            }
        } catch (e: IOException) {
            HttpResult(-1, "")
        }
    }

    override fun getBytes(url: String, headers: Map<String, String>): ByteArray? {
        val request = Request.Builder().url(url).get()
            .withHeaders(headers)
            .header("Accept", "image/avif,image/webp,*/*")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                // An expired session answers image requests with the auth-error TEXT.
                if (bytes.size < 512 && String(bytes).contains(ManualPortalClient.EXPIRED_MARKER)) null
                else bytes
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}

/**
 * Stateless-ish portal protocol driver. Holds the live session (cookie jar lives in the
 * ManualHttp impl) plus the identifier/config so any call can silently re-auth once when
 * the server reports an expired SecurityContext.
 */
class ManualPortalClient(
    private val http: ManualHttp,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    var activeConfig: PortalConfig? = null
        private set
    var activeIdentifier: String? = null
        private set

    private fun headers(): Map<String, String> {
        val cfg = activeConfig
        return if (cfg == null) emptyMap() else mapOf("Origin" to cfg.origin, "Referer" to cfg.referer)
    }

    /** POST the entrypoint; returns true only when getuser confirms a non-anonymous session. */
    fun initSession(identifierRaw: String, config: PortalConfig): Boolean {
        val id = identifierRaw.trim().uppercase()
        if (id.isEmpty()) return false
        val form = if (PortalConfig.VIN_PATTERN.matches(id)) {
            mapOf("vin" to id, "uiLanguage" to config.uiLanguage, "importerId" to config.importerId)
        } else {
            mapOf("partNumber" to id, "uiLanguage" to config.uiLanguage, "importerId" to config.importerId)
        }
        val posted = http.postForm(
            "$baseUrl/api/entrypoint/V1/direct/",
            form,
            mapOf("Origin" to config.origin, "Referer" to config.referer)
        )
        if (!posted) return false
        activeConfig = config
        activeIdentifier = id
        val check = http.get("$baseUrl/api/users/V1/getuser", headers())
        if (!check.body.contains("\"anonymous\":false")) {
            activeConfig = null
            activeIdentifier = null
            return false
        }
        return true
    }

    /** GET with automatic single re-auth when the payload carries the expired-session marker. */
    private fun getWithSession(url: String): HttpResult? {
        val cfg = activeConfig ?: return null
        val id = activeIdentifier ?: return null
        val first = http.get(url, headers())
        if (!first.body.contains(EXPIRED_MARKER)) return first
        if (!initSession(id, cfg)) return null
        val second = http.get(url, headers())
        return if (second.body.contains(EXPIRED_MARKER)) null else second
    }

    private fun bytesWithSession(url: String): ByteArray? {
        val cfg = activeConfig ?: return null
        val id = activeIdentifier ?: return null
        val first = http.getBytes(url, headers())
        if (first != null) return first
        if (!initSession(id, cfg)) return null
        return http.getBytes(url, headers())
    }

    /** .results[0].topicId of the welcome-topic search = the manual root key for this session. */
    fun findManualId(): String? {
        val lang = activeConfig?.uiLanguage ?: return null
        val url = "$baseUrl/api/web/V6/search?query=&facetfilters=topic-type_%7C_welcome&lang=$lang&page=0&pageSize=200"
        val json = getWithSession(url)?.body ?: return null
        return ManualJson.firstTopicId(json)
    }

    /** Full TOC tree JSON (.trees) for the manual root key. */
    fun fetchTree(manualId: String): String? {
        val lang = activeConfig?.uiLanguage ?: return null
        val url = "$baseUrl/api/web/V6/topic?key=$manualId&displaytype=topic&language=$lang&query=undefined"
        val result = getWithSession(url) ?: return null
        return result.body.takeIf { it.isNotBlank() && !it.contains(EXPIRED_MARKER) }
    }

    /** One section's topic JSON (contains .bodyHtml). */
    fun fetchSectionJson(linkTarget: String, language: String? = null): String? {
        val lang = language ?: activeConfig?.uiLanguage ?: return null
        val url = "$baseUrl/api/vw-topic/V1/topic?key=$linkTarget&displaytype=desktop&language=$lang"
        val result = getWithSession(url) ?: return null
        return result.body.takeIf { it.isNotBlank() && !it.contains(EXPIRED_MARKER) }
    }

    /** Image bytes for a media key. */
    fun fetchMedia(key: String, language: String? = null): ByteArray? {
        val lang = language ?: activeConfig?.uiLanguage ?: return null
        return bytesWithSession("$baseUrl/public/media?lang=$lang&key=$key")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://digital-manual.skoda-auto.com"
        const val EXPIRED_MARKER = "An Authentication object was not found in the SecurityContext"
    }
}
