package com.example

import com.example.manual.FlatNode
import com.example.manual.HttpResult
import com.example.manual.ManualCache
import com.example.manual.ManualHttp
import com.example.manual.ManualJson
import com.example.manual.ManualPortalClient
import com.example.manual.PortalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Owner's Manual portal integration (official Skoda digital-manual platform, 2026 flow).
 *
 * Contract under test (mirrors King4s/skoda-manual fork of jypma/skoda-manual, Feb 2026):
 *  POST /api/entrypoint/V1/direct/  vin|partNumber + uiLanguage + importerId -> session
 *  GET  /api/users/V1/getuser       {"anonymous":false} == live session
 *  GET  /api/web/V6/search?...topic-type_|_welcome...   .results[0].topicId == manual id
 *  GET  /api/web/V6/topic?key=..&displaytype=topic      .trees == TOC
 *  GET  /api/vw-topic/V1/topic?key=..&displaytype=desktop  .bodyHtml == section
 *  GET  /public/media?lang=..&key=..                    image bytes
 *  expiry marker: "An Authentication object was not found in the SecurityContext"
 */
class OwnerManualPortalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeHttp : ManualHttp {
        val postedForms = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        val requestedUrls = mutableListOf<String>()
        var postOk = true
        var userBody = """{"anonymous":false}"""
        var mediaBytes: ByteArray? = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        private val bodies = mutableMapOf<String, ArrayDeque<HttpResult>>()

        /** Queue responses for URLs containing [urlPart]; the last one repeats forever. */
        fun whenever(urlPart: String, vararg results: HttpResult) {
            bodies[urlPart] = ArrayDeque(results.toList())
        }

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): Boolean {
            postedForms += Triple(url, form, headers)
            return postOk
        }

        override fun get(url: String, headers: Map<String, String>): HttpResult {
            requestedUrls += url
            bodies.entries.firstOrNull { url.contains(it.key) }?.value?.let { q ->
                if (q.isEmpty()) return HttpResult(200, "")
                return if (q.size == 1) q.first() else q.removeFirst()
            }
            if (url.contains("getuser")) return HttpResult(200, userBody)
            return HttpResult(200, "{}")
        }

        override fun getBytes(url: String, headers: Map<String, String>): ByteArray? {
            requestedUrls += url
            return if (url.contains("/public/media")) mediaBytes else null
        }
    }

    private val validVin = "TMBJG6NW8S5012345" // 17 chars, no I/O/Q

    // ── PortalConfig ─────────────────────────────────────────────────────────────

    @Test
    fun `vin pattern accepts 17 chars without IOQ and rejects everything else`() {
        assertTrue(PortalConfig.VIN_PATTERN.matches(validVin))
        assertFalse(PortalConfig.VIN_PATTERN.matches("TMBIG6NW8S5012345")) // contains I
        assertFalse(PortalConfig.VIN_PATTERN.matches("TMBJG6NW8S5O12345")) // contains O
        assertFalse(PortalConfig.VIN_PATTERN.matches("TMBJG6NW8S501234"))   // 16 chars
        assertFalse(PortalConfig.VIN_PATTERN.matches("657012738AR"))       // part number
    }

    @Test
    fun `india config is first for en_IN and carries importer 663`() {
        val order = PortalConfig.orderFor("en_IN")
        assertEquals("663", order.first().importerId)
        assertEquals("en_IN", order.first().uiLanguage)
        assertTrue(order.first().origin.contains("skoda-auto.co.in"))
        // Denmark (004, proven by the King4s fork) must remain as the tail fallback.
        assertEquals("004", order.last().importerId)
        assertEquals(PortalConfig.ALL.size, order.size)
    }

    @Test
    fun `orderFor prefers the requested language but keeps full matrix`() {
        val order = PortalConfig.orderFor("en_GB")
        assertTrue(order.take(2).all { it.uiLanguage == "en_GB" })
        assertEquals("en_IN", order.last().uiLanguage)
    }

    // ── ManualPortalClient: session handshake ────────────────────────────────────

    @Test
    fun `initSession posts vin form for a VIN and verifies getuser`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        assertTrue(client.initSession(validVin.lowercase(), PortalConfig.INDIA_EN_IN))

        val (url, form, headers) = http.postedForms.single()
        assertTrue(url.endsWith("/api/entrypoint/V1/direct/"))
        assertEquals(validVin, form["vin"])                       // upper-cased
        assertNull(form["partNumber"])
        assertEquals("en_IN", form["uiLanguage"])
        assertEquals("663", form["importerId"])
        assertEquals("https://www.skoda-auto.co.in", headers["Origin"])
        assertTrue(http.requestedUrls.any { it.contains("/api/users/V1/getuser") })
        assertEquals(PortalConfig.INDIA_EN_IN, client.activeConfig)
        assertEquals(validVin, client.activeIdentifier)
    }

    @Test
    fun `initSession posts partNumber form for non-VIN identifiers`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        assertTrue(client.initSession("657012738ar", PortalConfig.DK_EN_GB))
        val form = http.postedForms.single().second
        assertEquals("657012738AR", form["partNumber"])
        assertNull(form["vin"])
        assertEquals("004", form["importerId"])
    }

    @Test
    fun `anonymous getuser means no session`() {
        val http = FakeHttp().apply { userBody = """{"anonymous":true}""" }
        val client = ManualPortalClient(http)
        assertFalse(client.initSession(validVin, PortalConfig.INDIA_EN_IN))
        assertNull(client.activeConfig)
    }

    @Test
    fun `post failure aborts before getuser`() {
        val http = FakeHttp().apply { postOk = false }
        val client = ManualPortalClient(http)
        assertFalse(client.initSession(validVin, PortalConfig.INDIA_EN_IN))
        assertFalse(http.requestedUrls.any { it.contains("getuser") })
    }

    // ── ManualPortalClient: manual resolution + payload calls ───────────────────

    @Test
    fun `findManualId resolves results0 topicId through the search endpoint`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        http.whenever(
            "/api/web/V6/search",
            HttpResult(200, """{"results":[{"topicId":"kylaq_manual_1_en_IN","label":"Owner's Manual"}]}""")
        )
        assertEquals("kylaq_manual_1_en_IN", client.findManualId())
        val searchUrl = http.requestedUrls.first { it.contains("V6/search") }
        assertTrue(searchUrl.contains("facetfilters=topic-type_%7C_welcome"))
        assertTrue(searchUrl.contains("lang=en_IN"))
    }

    @Test
    fun `findManualId returns null on empty results`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        http.whenever("/api/web/V6/search", HttpResult(200, """{"results":[]}"""))
        assertNull(client.findManualId())
    }

    @Test
    fun `fetchTree hits V6 topic with displaytype topic`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        val treeJson = """{"trees":[{"label":"Kylaq","linkTarget":null,"children":[]}]}"""
        http.whenever("/api/web/V6/topic", HttpResult(200, treeJson))
        assertEquals(treeJson, client.fetchTree("kylaq_manual_1_en_IN"))
        val url = http.requestedUrls.first { it.contains("V6/topic") }
        assertTrue(url.contains("key=kylaq_manual_1_en_IN"))
        assertTrue(url.contains("displaytype=topic"))
        assertTrue(url.contains("language=en_IN"))
    }

    @Test
    fun `expired session re-authenticates once and retries the payload`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        val expired = HttpResult(200, ManualPortalClient.EXPIRED_MARKER)
        val good = HttpResult(200, """{"trees":[{"label":"Root","children":[]}]}""")
        http.whenever("/api/web/V6/topic", expired, good)

        val tree = client.fetchTree("manual_x")
        assertNotNull(tree)
        assertTrue(tree!!.contains("trees"))
        // entrypoint hit twice: initial session + one re-auth after the expiry marker.
        assertEquals(2, http.postedForms.count { it.first.contains("entrypoint") })
    }

    @Test
    fun `double expiry gives up instead of looping`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        val expired = HttpResult(200, ManualPortalClient.EXPIRED_MARKER)
        http.whenever("/api/vw-topic/V1/topic", expired, expired, expired)
        assertNull(client.fetchSectionJson("sect_1"))
    }

    @Test
    fun `fetchMedia builds lang and key query`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        client.initSession(validVin, PortalConfig.INDIA_EN_IN)
        val bytes = client.fetchMedia("abc-123")
        assertNotNull(bytes)
        val url = http.requestedUrls.first { it.contains("/public/media") }
        assertTrue(url.contains("lang=en_IN"))
        assertTrue(url.contains("key=abc-123"))
    }

    @Test
    fun `calls without a session are refused locally - no network`() {
        val http = FakeHttp()
        val client = ManualPortalClient(http)
        assertNull(client.findManualId())
        assertNull(client.fetchTree("x"))
        assertNull(client.fetchSectionJson("x"))
        assertNull(client.fetchMedia("x"))
        assertTrue(http.requestedUrls.isEmpty())
    }

    // ── ManualJson parsing ───────────────────────────────────────────────────────

    private val sampleTree = """
        {"trees":[
          {"label":"<b>Kylaq</b> Owner's Manual","linkTarget":"null","children":[
            {"label":"Introduction","linkTarget":"sec_intro_1_en_IN","children":[
              {"label":"Safety first","linkTarget":"sec_safety_1_en_IN","children":[]}
            ]},
            {"label":"Driving","linkTarget":"sec_drive_1_en_IN","children":[]}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `parseTrees strips label html and normalises string-null linkTarget`() {
        val roots = ManualJson.parseTrees(sampleTree)
        assertEquals(1, roots.size)
        assertEquals("Kylaq Owner's Manual", roots[0].label)
        assertNull(roots[0].linkTarget) // JSON string "null" must become real null
        assertEquals(2, roots[0].children.size)
        assertEquals("sec_safety_1_en_IN", roots[0].children[0].children[0].linkTarget)
    }

    @Test
    fun `parseTrees survives garbage input`() {
        assertTrue(ManualJson.parseTrees("not json").isEmpty())
        assertTrue(ManualJson.parseTrees("{}").isEmpty())
        assertTrue(ManualJson.parseTrees("""{"trees":[]}""").isEmpty())
    }

    @Test
    fun `flatten yields dfs order with depths and stable path ids`() {
        val flat = ManualJson.flatten(ManualJson.parseTrees(sampleTree))
        assertEquals(4, flat.size)
        assertEquals(listOf(0, 1, 2, 1), flat.map { it.depth })
        assertEquals(listOf("0", "0.0", "0.0.0", "0.1"), flat.map { it.pathId })
        assertEquals("0", flat[1].parentId)
        assertTrue(flat[0].hasChildren)
        assertFalse(flat[3].hasChildren)
    }

    @Test
    fun `visible-list rule shows children only under fully expanded ancestors`() {
        val flat = ManualJson.flatten(ManualJson.parseTrees(sampleTree))
        val expanded = setOf("0") // root expanded, "Introduction" collapsed
        val visibleIds = mutableSetOf<String>()
        val visible = flat.filter { n ->
            val show = n.parentId == null || (n.parentId in expanded && n.parentId in visibleIds)
            if (show) visibleIds += n.pathId
            show
        }
        assertEquals(listOf("0", "0.0", "0.1"), visible.map { it.pathId })
    }

    @Test
    fun `firstTopicId parses and rejects`() {
        assertEquals("t1", ManualJson.firstTopicId("""{"results":[{"topicId":"t1"}]}"""))
        assertNull(ManualJson.firstTopicId("""{"results":[]}"""))
        assertNull(ManualJson.firstTopicId("""{"nope":1}"""))
        assertNull(ManualJson.firstTopicId("junk"))
    }

    @Test
    fun `bodyHtml unwraps the portal html document`() {
        val json = """{"bodyHtml":"<!DOCTYPE html>\n<html lang=\"en\">\n<head><title>x</title></head>\n<body><p>Check <b>engine</b> oil.</p></body>\n</html>"}"""
        val body = ManualJson.bodyHtml(json)
        assertNotNull(body)
        assertTrue(body!!.contains("<p>Check <b>engine</b> oil.</p>"))
        assertFalse(body.contains("<html"))
        assertFalse(body.contains("DOCTYPE"))
        assertFalse(body.contains("<head>"))
        assertNull(ManualJson.bodyHtml("""{"bodyHtml":""}"""))
        assertNull(ManualJson.bodyHtml("junk"))
    }

    @Test
    fun `mediaRefs extracts deduplicated keys from absolute 2026-style data-src urls`() {
        val html = """
            <img data-src="https://digital-manual.skoda-auto.com/default/public/media?lang=en_IN&amp;key=img-aaa" alt="x">
            <img data-src="https://digital-manual.skoda-auto.com/default/public/media?lang=en_IN&amp;key=img-bbb">
            <img data-src="https://digital-manual.skoda-auto.com/default/public/media?lang=en_IN&amp;key=img-aaa">
            <img src="https://digital-manual.skoda-auto.com/public/media?lang=en_IN&key=img-ccc">
            <img src="images/logo.png">
        """.trimIndent()
        val refs = ManualJson.mediaRefs(html)
        assertEquals(listOf("img-aaa", "img-bbb", "img-ccc"), refs.map { it.key })
        assertEquals("en_IN", refs[0].lang)
    }

    @Test
    fun `activateImages rewrites data-src so a JS-disabled WebView loads pictures`() {
        val html = """<img data-src="/public/media?lang=en_IN&amp;key=k1">"""
        val out = ManualJson.activateImages(html)
        assertTrue(out.contains("""src="/public/media"""))
        assertFalse(out.contains("data-src"))
    }

    @Test
    fun `wrapForDisplay produces a standalone dark-theme document`() {
        val doc = ManualJson.wrapForDisplay("<p>Oil</p>", "Engine oil")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("<p>Oil</p>"))
        assertTrue(doc.contains("viewport"))
    }

    @Test
    fun `guessMime sniffs magic bytes`() {
        assertEquals("image/jpeg", ManualJson.guessMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("image/png", ManualJson.guessMime(png))
        val webp = "RIFFxxxxWEBP".toByteArray()
        assertEquals("image/webp", ManualJson.guessMime(webp))
        assertEquals("application/octet-stream", ManualJson.guessMime("hello world!".toByteArray()))
        assertEquals("application/octet-stream", ManualJson.guessMime(byteArrayOf(1, 2)))
    }

    @Test
    fun `snippet centres the match with ellipses`() {
        val text = "x".repeat(50) + "torque converter" + "y".repeat(100)
        val s = ManualJson.snippet(text, "TORQUE")
        assertTrue(s.startsWith("..."))
        assertTrue(s.endsWith("..."))
        assertTrue(s.lowercase().contains("torque converter"))
        assertTrue(s.length < text.length)
    }

    // ── ManualCache ──────────────────────────────────────────────────────────────

    @Test
    fun `cache roundtrips sections keeping the original linkTarget`() {
        val cache = ManualCache(tmp.newFolder("m1"))
        val key = "sec?weird&key=with/special=chars"
        cache.saveSection(key, """{"bodyHtml":"<p>hi</p>"}""")
        assertTrue(cache.hasSection(key))
        assertEquals("""{"bodyHtml":"<p>hi</p>"}""", cache.loadSection(key))
        val all = cache.allSections()
        assertEquals(1, all.size)
        assertEquals(key, all[0].first) // hashed filenames must not lose the key
        assertEquals(1, cache.sectionCount())
    }

    @Test
    fun `cache roundtrips media trees and lastConnect then clears`() {
        val root = tmp.newFolder("m2")
        val cache = ManualCache(root)
        cache.saveMedia("img-aaa", byteArrayOf(1, 2, 3))
        assertTrue(cache.hasMedia("img-aaa"))
        assertEquals(3, cache.loadMedia("img-aaa")!!.size)
        cache.saveTree("en_IN", """{"trees":[]}""")
        assertEquals("""{"trees":[]}""", cache.loadTree("en_IN"))
        assertEquals(listOf("en_IN"), cache.treeLangs())

        cache.saveLastConnect(
            ManualCache.LastConnect("VIN1", "India portal - English (IN)", "en_IN", "mid", "Kylaq Manual", 42L)
        )
        val last = cache.loadLastConnect()
        assertNotNull(last)
        assertEquals("VIN1", last!!.identifier)
        assertEquals("mid", last.manualId)
        assertEquals(42L, last.savedAtMs)

        assertTrue(cache.approxSizeBytes() > 0)
        cache.clear()
        assertEquals(0, cache.sectionCount())
        assertEquals(0, cache.mediaCount())
        assertNull(cache.loadTree("en_IN"))
        assertNull(cache.loadLastConnect())
    }

    @Test
    fun `distinct keys never collide on hashed filenames`() {
        val cache = ManualCache(tmp.newFolder("m3"))
        cache.saveSection("key-a", """{"bodyHtml":"A"}""")
        cache.saveSection("key-b", """{"bodyHtml":"B"}""")
        assertEquals(2, cache.sectionCount())
        assertTrue(cache.loadSection("key-a")!!.endsWith("A\"}"))
        assertTrue(cache.loadSection("key-b")!!.endsWith("B\"}"))
        assertNull(cache.loadSection("key-c"))
    }

    @Test
    fun `flat node data class exposes what the UI renders`() {
        val n = FlatNode("0.1", "0", 1, "Driving", "sec_drive", false)
        assertEquals("Driving", n.label)
        assertEquals("sec_drive", n.linkTarget)
        assertFalse(n.hasChildren)
    }
}
