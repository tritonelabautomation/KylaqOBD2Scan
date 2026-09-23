package com.example.crash

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads crash logs to GitHub as Issues with label `crash-auto`.
 * Works only if BuildConfig.CRASH_REPORT_TOKEN is present — a fine-grained PAT with
 * issues:write on this repo. Token is injected via env CRASH_REPORT_TOKEN at build time
 * (GitHub secret + .env locally). Empty token = no GitHub push, but local file + Drive backup remain.
 *
 * Two channels:
 * 1. Create Issue via POST /repos/{repo}/issues — agent can list via `gh issue list --label crash-auto`
 * 2. Optionally dispatch repository_dispatch `crash-report` to trigger crash-ingest workflow that commits file to crashes/inbox/
 *
 * All network on Dispatchers.IO, best-effort, never crashes the app.
 */
object GithubCrashUploader {

    private const val TAG = "GithubCrashUploader"

    suspend fun uploadIfConfigured(context: Context, file: File, crashText: String): Boolean = withContext(Dispatchers.IO) {
        val token = BuildConfig.CRASH_REPORT_TOKEN.trim()
        if (token.isBlank()) {
            Log.i(TAG, "CRASH_REPORT_TOKEN blank — skipping GitHub upload, keeping local + Drive")
            return@withContext false
        }
        val repo = BuildConfig.CRASH_REPORT_REPO.ifBlank { "tritonelabautomation/KylaqOBD2Scan" }
        return@withContext try {
            val issueOk = createIssue(token, repo, context, file, crashText)
            if (issueOk) {
                // Best-effort also dispatch workflow to commit file to crashes/inbox/
                runCatching { dispatchCrashIngest(token, repo, file, crashText) }
            }
            issueOk
        } catch (e: Exception) {
            Log.e(TAG, "GitHub upload failed", e)
            false
        }
    }

    private fun createIssue(token: String, repo: String, context: Context, file: File, crashText: String): Boolean {
        val url = URL("https://api.github.com/repos/$repo/issues")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "KylaqOBD2Scan-CrashReporter")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val title = CrashReporter.buildIssueTitle(crashText)
        val body = CrashReporter.buildIssueBody(context, file, crashText)

        val json = JSONObject().apply {
            put("title", title.take(200))
            put("body", body.take(60000))
            put("labels", JSONArray().apply {
                put("crash-auto")
                put("auto-reported")
                put("bug")
            })
        }

        conn.outputStream.use { it.write(json.toString().toByteArray()) }
        val code = conn.responseCode
        val response = try {
            conn.inputStream.bufferedReader().readText().take(1000)
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText()?.take(1000) ?: ""
        }
        Log.i(TAG, "GitHub issue create $code: $response")
        return code in 200..299
    }

    private fun dispatchCrashIngest(token: String, repo: String, file: File, crashText: String): Boolean {
        // Triggers .github/workflows/crash-ingest.yml via repository_dispatch
        val url = URL("https://api.github.com/repos/$repo/dispatches")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "KylaqOBD2Scan-CrashReporter")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
        }

        // Keep payload small — workflow will create file from this
        val payload = JSONObject().apply {
            put("event_type", "crash-report")
            put("client_payload", JSONObject().apply {
                put("file_name", file.name)
                put("crash_log", crashText.take(10000))
                put("app_version", com.example.BuildConfig.VERSION_NAME)
                put("timestamp", System.currentTimeMillis())
            })
        }

        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        Log.i(TAG, "GitHub dispatch $code")
        return code in 200..299
    }

    /**
     * Anonymous fallback: try to create anonymous gist (no token) with crash log.
     * GitHub still allows anonymous gist creation via POST /gists without auth (rate-limited).
     * Returns gist URL or null.
     */
    fun tryAnonymousGist(crashText: String): String? {
        return try {
            val url = URL("https://api.github.com/gists")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "KylaqOBD2Scan-CrashReporter")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            val json = JSONObject().apply {
                put("public", false)
                put("description", "KylaqOBD2Scan auto crash ${System.currentTimeMillis()}")
                put("files", JSONObject().apply {
                    put("crash.txt", JSONObject().apply {
                        put("content", crashText.take(8000))
                    })
                })
            }
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().readText()
                JSONObject(resp).optString("html_url")
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
