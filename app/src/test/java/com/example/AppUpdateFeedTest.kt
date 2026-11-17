package com.example

import com.example.update.AppUpdateFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app updater feed logic (owner 2026-09-16: "update available I click it will
 * automatically fetch latest update from GitHub ... similar to playstore").
 *
 * The point of these tests is the fail-closed rules: a feed that is incomplete,
 * downgraded to plain HTTP, or missing a verifiable checksum must never produce an
 * installable update, because whatever reaches the package installer replaces the
 * owner's whole app - including every trip and raw log on the phone.
 */
class AppUpdateFeedTest {

    private val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private fun feed(
        versionCode: Int = 10234,
        apkUrl: String = "https://github.com/tritonelabautomation/KylaqOBD2Scan/releases/latest/download/KylaqOBD2Scan.apk",
        sha256: String = sha,
        versionName: String = "1.0.234"
    ): String = """
        {
          "schema": 1,
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkUrl": "$apkUrl",
          "assetName": "KylaqOBD2Scan.apk",
          "sha256": "$sha256",
          "sizeBytes": 45187321,
          "publishedAt": "2026-09-16T04:05:06Z",
          "branch": "arena/01a07c37-kylaqobd2scan",
          "commitSha": "12f5a5a5",
          "commitSubject": "feat(trips): persist GPS altitude per trip",
          "notes": "Build 1.0.234 (versionCode 10234)"
        }
    """.trimIndent()

    // ── parsing ───────────────────────────────────────────────────────────────────

    @Test
    fun `a complete feed parses into every field`() {
        val info = AppUpdateFeed.parse(feed())
        assertTrue(info != null)
        assertEquals(1, info!!.schema)
        assertEquals(10234, info.versionCode)
        assertEquals("1.0.234", info.versionName)
        assertEquals(sha, info.sha256)
        assertEquals(45187321L, info.sizeBytes)
        assertEquals("2026-09-16T04:05:06Z", info.publishedAt)
        assertEquals("arena/01a07c37-kylaqobd2scan", info.branch)
        assertEquals("12f5a5a5", info.commitSha)
        assertEquals("feat(trips): persist GPS altitude per trip", info.commitSubject)
        assertTrue(info.apkUrl.startsWith("https://github.com/tritonelabautomation/KylaqOBD2Scan/"))
    }

    @Test
    fun `build label shows the version and the short commit`() {
        assertEquals("1.0.234 · 12f5a5a", AppUpdateFeed.parse(feed())!!.buildLabel())
    }

    @Test
    fun `garbage feeds are rejected instead of half-parsed`() {
        assertNull(AppUpdateFeed.parse(null))
        assertNull(AppUpdateFeed.parse(""))
        assertNull(AppUpdateFeed.parse("   "))
        assertNull(AppUpdateFeed.parse("not json"))
        assertNull(AppUpdateFeed.parse("{\"versionCode\": }"))
        assertNull(AppUpdateFeed.parse("[]"))
    }

    @Test
    fun `a feed without a usable versionCode is not an update`() {
        assertNull(AppUpdateFeed.parse(feed(versionCode = 0)))
        assertNull(AppUpdateFeed.parse(feed(versionCode = -7)))
        assertNull(AppUpdateFeed.parse("""{"apkUrl":"https://x/y.apk","sha256":"$sha"}"""))
    }

    @Test
    fun `an apk url that is not https is rejected outright`() {
        assertNull(AppUpdateFeed.parse(feed(apkUrl = "http://github.com/x/y.apk")))
        assertNull(AppUpdateFeed.parse(feed(apkUrl = "file:///sdcard/KylaqOBD2Scan.apk")))
        assertNull(AppUpdateFeed.parse(feed(apkUrl = "content://downloads/apk")))
        assertNull(AppUpdateFeed.parse(feed(apkUrl = "/releases/latest/download/KylaqOBD2Scan.apk")))
        assertNull(AppUpdateFeed.parse(feed(apkUrl = "")))
    }

    @Test
    fun `an apk that cannot be checksum-verified is never installable`() {
        assertNull(AppUpdateFeed.parse(feed(sha256 = "")))
        assertNull(AppUpdateFeed.parse(feed(sha256 = sha.dropLast(1))))   // 63 chars
        assertNull(AppUpdateFeed.parse(feed(sha256 = sha + "aa")))        // 66 chars
        assertNull(AppUpdateFeed.parse(feed(sha256 = "z".repeat(64))))    // not hex
        assertNull(AppUpdateFeed.parse(feed(sha256 = "not-a-digest")))
    }

    @Test
    fun `an uppercase digest is accepted and normalised to lower case`() {
        val info = AppUpdateFeed.parse(feed(sha256 = sha.uppercase()))
        assertTrue(info != null)
        assertEquals(sha, info!!.sha256)
    }

    @Test
    fun `a missing versionName falls back to the build number`() {
        val info = AppUpdateFeed.parse(feed(versionName = ""))
        assertEquals("build 10234", info!!.versionName)
    }

    // ── version comparison ────────────────────────────────────────────────────────

    @Test
    fun `only a strictly newer build counts as an update`() {
        val info = AppUpdateFeed.parse(feed(versionCode = 10234))
        assertTrue(AppUpdateFeed.isNewerThan(info, 10233))
        assertFalse(AppUpdateFeed.isNewerThan(info, 10234))
        assertFalse(AppUpdateFeed.isNewerThan(info, 10235))
        assertFalse(AppUpdateFeed.isNewerThan(null, 0))
    }

    // ── automatic-check throttle ──────────────────────────────────────────────────

    @Test
    fun `the silent launch check is throttled but a manual check never is`() {
        val interval = AppUpdateFeed.AUTO_CHECK_INTERVAL_MS
        val now = 1_800_000_000_000L
        assertTrue("never checked before", AppUpdateFeed.shouldAutoCheck(0L, now, interval))
        assertTrue("checked long ago", AppUpdateFeed.shouldAutoCheck(now - interval - 1, now, interval))
        assertFalse("checked a minute ago", AppUpdateFeed.shouldAutoCheck(now - 60_000L, now, interval))
        assertFalse("exactly at the boundary is not yet due", AppUpdateFeed.shouldAutoCheck(now - interval, now - 1, interval))
        assertTrue("clock moved backwards", AppUpdateFeed.shouldAutoCheck(now, now - 3_600_000L, interval))
    }

    // ── integrity helpers ─────────────────────────────────────────────────────────

    @Test
    fun `sha-256 matches the published reference vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AppUpdateFeed.sha256Hex("abc".byteInputStream())
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AppUpdateFeed.sha256Hex("".byteInputStream())
        )
    }

    @Test
    fun `digest comparison is case-insensitive but rejects malformed expectations`() {
        val digest = AppUpdateFeed.sha256Hex("abc".byteInputStream())
        assertTrue(AppUpdateFeed.sha256Matches(digest.uppercase(), digest))
        assertTrue(AppUpdateFeed.sha256Matches(" $digest ", digest))
        assertFalse(AppUpdateFeed.sha256Matches(digest, sha))
        assertFalse("a malformed expectation must never 'match'", AppUpdateFeed.sha256Matches("nope", "nope"))
        assertFalse(AppUpdateFeed.sha256Matches("", ""))
    }

    // ── presentation helpers ──────────────────────────────────────────────────────

    @Test
    fun `sizes are human and locale-stable`() {
        assertEquals("unknown size", AppUpdateFeed.humanSize(0L))
        assertEquals("unknown size", AppUpdateFeed.humanSize(-5L))
        assertEquals("512 B", AppUpdateFeed.humanSize(512L))
        assertEquals("2 KB", AppUpdateFeed.humanSize(2048L))
        assertEquals("5.0 MB", AppUpdateFeed.humanSize(5L * 1024L * 1024L))
        assertEquals("43.1 MB", AppUpdateFeed.humanSize(45187321L))
        assertEquals("2.00 GB", AppUpdateFeed.humanSize(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `progress is a fraction while the total is known and null while it is not`() {
        assertEquals(0.5f, AppUpdateFeed.progressFraction(50L, 100L)!!, 0.0001f)
        assertEquals(1.0f, AppUpdateFeed.progressFraction(200L, 100L)!!, 0.0001f) // clamped
        assertEquals(0.0f, AppUpdateFeed.progressFraction(-5L, 100L)!!, 0.0001f)    // clamped
        assertNull(AppUpdateFeed.progressFraction(50L, 0L))
        assertNull(AppUpdateFeed.progressFraction(50L, -1L))
    }
}
