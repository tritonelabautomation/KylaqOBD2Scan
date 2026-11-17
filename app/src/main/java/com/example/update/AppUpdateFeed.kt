package com.example.update

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * What the rolling GitHub Release publishes next to the APK (`latest.json`).
 */
data class AppUpdateInfo(
    val schema: Int,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val branch: String,
    val commitSha: String,
    val commitSubject: String,
    val notes: String
) {
    /** One-line summary for the update card / dialog. */
    fun buildLabel(): String =
        listOf(versionName, if (commitSha.isNotBlank()) commitSha.take(7) else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * In-app updater feed logic (owner 2026-09-16: "is there a better way like update
 * available I click it will automatically fetch latest update from GitHub ... similar
 * to playstore it should update the app in background").
 *
 * Distribution model: CI publishes every successful build of the working branches to one
 * rolling GitHub Release under two stable asset names — `KylaqOBD2Scan.apk` and
 * `latest.json`. The repository is public, so the phone reads both with no token, and
 * `…/releases/latest/download/<asset>` always resolves to the newest build.
 *
 * Fail-closed rules (all unit-tested in AppUpdateFeedTest):
 *  - a feed without a positive `versionCode` is not an update,
 *  - an `apkUrl` that is not `https://` is rejected outright (never fetch over plain HTTP),
 *  - an APK whose `sha256` is absent or malformed is rejected: an update that cannot be
 *    verified is not installed, because a truncated or tampered download must never
 *    reach the package installer.
 *
 * Pure JVM — org.json is a real dependency in unit tests (see app/build.gradle.kts).
 */
object AppUpdateFeed {

    /** Bumped only if the feed format changes incompatibly. */
    const val SCHEMA = 1

    /** Automatic background check at most twice a day-ish; manual checks are never throttled. */
    const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val SHA256_HEX = Regex("^[0-9A-Fa-f]{64}$")

    /** Parses `latest.json`, returning null for anything incomplete or untrustworthy. */
    fun parse(json: String?): AppUpdateInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val versionCode = o.optInt("versionCode", 0)
            val apkUrl = o.optString("apkUrl", "").trim()
            val sha256 = o.optString("sha256", "").trim()
            if (versionCode <= 0) return null
            if (!apkUrl.startsWith("https://")) return null
            if (!SHA256_HEX.matches(sha256)) return null
            AppUpdateInfo(
                schema = o.optInt("schema", 0),
                versionCode = versionCode,
                versionName = o.optString("versionName", "").ifBlank { "build $versionCode" },
                apkUrl = apkUrl,
                sha256 = sha256.lowercase(),
                sizeBytes = o.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                publishedAt = o.optString("publishedAt", ""),
                branch = o.optString("branch", ""),
                commitSha = o.optString("commitSha", ""),
                commitSubject = o.optString("commitSubject", ""),
                notes = o.optString("notes", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** True only when the feed describes a build newer than the one installed. */
    fun isNewerThan(info: AppUpdateInfo?, installedVersionCode: Int): Boolean =
        info != null && info.versionCode > installedVersionCode

    /**
     * Throttle for the automatic launch check. A zero/negative stamp means "never
     * checked"; a stamp in the future means the clock moved backwards, so check again
     * rather than silently never checking.
     */
    fun shouldAutoCheck(
        lastCheckMs: Long,
        nowMs: Long,
        intervalMs: Long = AUTO_CHECK_INTERVAL_MS
    ): Boolean {
        if (lastCheckMs <= 0L) return true
        if (nowMs < lastCheckMs) return true
        return nowMs - lastCheckMs >= intervalMs
    }

    /** SHA-256 of a stream, lowercase hex — used to verify the downloaded APK. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Case-insensitive digest comparison (feeds may publish upper or lower case). */
    fun sha256Matches(expected: String, actual: String): Boolean =
        SHA256_HEX.matches(expected.trim()) && expected.trim().equals(actual.trim(), ignoreCase = true)

    /**
     * "18.4 MB" / "742 KB" — for the download prompt and progress row.
     * Locale-pinned: a decimal comma would both read wrong next to English UI copy and
     * break exact-string tests (a bug class this repo has already paid for once).
     */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> "unknown size"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.0f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** Download progress 0f..1f, or null when the total size is unknown. */
    fun progressFraction(receivedBytes: Long, totalBytes: Long): Float? {
        if (totalBytes <= 0L) return null
        return (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }
}
