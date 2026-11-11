package com.example.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and installs updates published on the project's rolling GitHub Release.
 *
 * Honest scope note: only a system installer (Play Store / device owner) may replace an
 * app with no user interaction. A sideloaded app can do everything up to that line by
 * itself — check, download in the background, verify, stage — and then hand the APK to
 * Android's package installer, which shows one confirmation. So the owner's flow is:
 * tap "Update" → progress bar → one system "Install?" prompt → app reopens in place with
 * all data, logs and permissions intact. No uninstall, no PC, no ZIP extraction.
 *
 * Three guards keep that promise honest:
 *  1. [download] verifies the SHA-256 published in `latest.json`; a truncated or altered
 *     download is deleted and never offered to the installer.
 *  2. [installedSignatureMatchesApk] detects the one case where an in-place update is
 *     impossible — the installed build was signed with a *different* key (every build
 *     before 2026-09-16 used a throwaway per-runner debug keystore). Instead of firing an
 *     installer that is guaranteed to fail with a cryptic "App not installed", the UI can
 *     explain that a one-time reinstall is needed and point at the backup/export first.
 *  3. [install] streams the verified APK into a [PackageInstaller] session. The older
 *     `ACTION_VIEW` + FileProvider path is what ColorOS/Oppo/Realme (and several other
 *     OEM skins) answer with "App not installed" after showing Installing… — they re-read
 *     the content:// URI after parsing the label and the grant is already gone. A session
 *     copies the bytes up front, so the confirmation sheet no longer has to reopen the
 *     file, and [UpdateInstallActivity] surfaces the real status code if it still fails.
 */
class UpdateManager(context: Context) {

    class UpdateException(message: String) : Exception(message)

    private val appContext: Context = context.applicationContext

    val updatesDir: File
        get() = File(appContext.filesDir, "updates").apply { if (!exists()) mkdirs() }

    /**
     * Fetches and parses `latest.json`. Null means the check itself failed (offline,
     * GitHub unreachable, or a malformed/incomplete feed) — which the UI must not report
     * as "you are up to date", so the version comparison is left to the caller.
     */
    fun fetchFeed(): AppUpdateInfo? = AppUpdateFeed.parse(httpGet(BuildConfig.UPDATE_FEED_URL))

    /** versionCode of the build currently installed. */
    fun installedVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * Downloads the APK to app-private storage, reporting progress, then verifies it.
     * Throws [UpdateException] on any network, size or integrity problem; the partial
     * file is always removed so a broken APK can never be installed later.
     */
    fun download(
        info: AppUpdateInfo,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        val target = File(updatesDir, "KylaqOBD2Scan-${info.versionCode}.apk")
        if (target.exists()) target.delete()

        val conn = openConnection(info.apkUrl)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException("GitHub returned HTTP $code for the APK download")
            }
            val declared = if (conn.contentLengthLong > 0L) conn.contentLengthLong else info.sizeBytes
            var received = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received, declared)
                    }
                }
            }

            if (declared > 0L && received != declared) {
                target.delete()
                throw UpdateException(
                    "Download was truncated (${AppUpdateFeed.humanSize(received)} of " +
                        "${AppUpdateFeed.humanSize(declared)})"
                )
            }
            if (received <= 0L) {
                target.delete()
                throw UpdateException("Download was empty")
            }

            // Verify what actually landed on disk, not what the network claimed.
            val actual = AppUpdateFeed.sha256Hex(target.inputStream())
            if (!AppUpdateFeed.sha256Matches(info.sha256, actual)) {
                target.delete()
                throw UpdateException(
                    "Checksum mismatch - the downloaded APK does not match the SHA-256 " +
                        "published with the release, so it was discarded"
                )
            }

            pruneStaleDownloads(keepVersionCode = info.versionCode)
            return target
        } catch (e: UpdateException) {
            if (target.exists()) target.delete()
            throw e
        } catch (e: Exception) {
            if (target.exists()) target.delete()
            throw UpdateException("Download failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Compares the signing certificate of the installed app with the one inside the
     * downloaded APK. `true` → in-place update will work. `false` → Android will refuse
     * (signature conflict) and a one-time uninstall/reinstall is required. `null` → could
     * not be determined, so the UI should just try and let the installer speak.
     *
     * Tries both [PackageManager.GET_SIGNING_CERTIFICATES] and the older
     * [PackageManager.GET_SIGNATURES]: `getPackageArchiveInfo` on several OEM builds
     * (ColorOS included) returns a PackageInfo whose `signingInfo` is null for the
     * modern flag, which used to make us skip the mismatch warning and hand a
     * guaranteed-to-fail APK to the installer.
     */
    @Suppress("DEPRECATION")
    fun installedSignatureMatchesApk(apk: File): Boolean? {
        return try {
            val pm = appContext.packageManager
            val installed = firstReadableSignature(pm, installed = true, apkPath = null)
            val candidate = firstReadableSignature(pm, installed = false, apkPath = apk.absolutePath)
            if (installed == null || candidate == null) null
            else java.security.MessageDigest.isEqual(installed, candidate)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun firstReadableSignature(
        pm: PackageManager,
        installed: Boolean,
        apkPath: String?
    ): ByteArray? {
        val flagSets = ArrayList<Int>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flagSets.add(PackageManager.GET_SIGNING_CERTIFICATES)
            flagSets.add(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        }
        flagSets.add(PackageManager.GET_SIGNATURES)
        for (flags in flagSets) {
            val info = try {
                if (installed) pm.getPackageInfo(appContext.packageName, flags)
                else pm.getPackageArchiveInfo(apkPath ?: return null, flags)
            } catch (_: Exception) {
                null
            }
            val bytes = firstSignatureOf(info)
            if (bytes != null) return bytes
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun firstSignatureOf(info: PackageInfo?): ByteArray? {
        if (info == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            if (signing != null) {
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers?.firstOrNull()?.toByteArray()?.let { return it }
            }
        }
        return info.signatures?.firstOrNull()?.toByteArray()
    }

    /** Android 8+ needs "install unknown apps" granted for this app before installing. */
    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
            // Some OEM builds omit the settings page; fall back to the generic screen.
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Hands the verified APK to Android's package installer (one system confirmation).
     *
     * Prefers a [PackageInstaller] session so OEM skins cannot lose the FileProvider
     * grant mid-install. Falls back to `ACTION_VIEW` only if creating a session throws.
     */
    fun install(apk: File) {
        if (!apk.exists() || apk.length() <= 0L) {
            throw UpdateException("Staged APK is missing - tap Download & install again")
        }
        try {
            installViaSession(apk)
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            try {
                installViaViewIntent(apk)
            } catch (_: Exception) {
                throw UpdateException(
                    "Could not open the installer: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun installViaSession(apk: File) {
        val installer = appContext.packageManager.packageInstaller
        for (info in installer.mySessions) {
            try {
                installer.abandonSession(info.sessionId)
            } catch (_: Exception) {
            }
        }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(appContext.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setDontKillApp(true)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    session.fsync(output)
                }
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            )
            val statusIntent = Intent(appContext, UpdateInstallActivity::class.java).apply {
                // Distinct data so FLAG_UPDATE_CURRENT cannot collapse two sessions.
                data = Uri.parse("kylaq-update://session/$sessionId")
            }
            val pending = PendingIntent.getActivity(
                appContext,
                sessionId,
                statusIntent,
                pendingFlags
            )
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Last-resort path for the handful of firmwares that throw from [PackageInstaller]
     * itself. Explicitly grants the content URI to every resolver that claims the APK
     * MIME type — the FLAG on the Intent is not enough on ColorOS.
     */
    private fun installViaViewIntent(apk: File) {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = android.content.ClipData.newRawUri("update", uri)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        appContext.packageManager.queryIntentActivities(intent, 0).forEach { resolve ->
            try {
                appContext.grantUriPermission(resolve.activityInfo.packageName, uri, grantFlags)
            } catch (_: Exception) {
            }
        }
        appContext.startActivity(intent)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            // GitHub rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "KylaqOBD2Scan/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
            setRequestProperty("Accept", "*/*")
        }

    private fun httpGet(url: String): String? = try {
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Keeps only the newest APK; the app-private dir must not accumulate 40 MB builds. */
    private fun pruneStaleDownloads(keepVersionCode: Int) {
        val keep = "KylaqOBD2Scan-$keepVersionCode.apk"
        updatesDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") && f.name != keep) f.delete()
        }
    }
}
