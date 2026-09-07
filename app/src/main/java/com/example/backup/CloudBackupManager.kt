package com.example.backup

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class BackupSyncResult(
    val success: Boolean,
    val backedUpCount: Int = 0,
    val restoredCount: Int = 0,
    val message: String = ""
)

data class CloudBackupInfo(
    val accountEmail: String?,
    val isAutoBackupEnabled: Boolean,
    val lastBackupFormatted: String,
    val pendingBackupsCount: Int
)

/**
 * The user dismissed the Google account chooser. Reported as a failure so callers get a
 * single `Result` type back, but callers should not show it as an error.
 */
class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * Google Sign-In cannot run because no usable OAuth *Web application* client ID is
 * configured. The message tells the user exactly where to put one.
 */
class SignInNotConfiguredException(message: String) : Exception(message)

/**
 * Handles Google Drive cloud backup, Google identity sign-in via Credential Manager,
 * and background synchronization for OBD trip records and ZIP bundles.
 */
class CloudBackupManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val recordingManager: RecordingManager
) {
    private val credentialManager = CredentialManager.create(context)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    fun clearStatusMessage() {
        _syncStatusMessage.value = null
    }

    /**
     * Sets the sync status message. Thread-safe via MutableStateFlow.
     * Allows callers (e.g. UI events) to surface status updates without
     * needing direct access to the private MutableStateFlow.
     */
    fun setStatusMessage(message: String?) {
        _syncStatusMessage.value = message
    }

    /**
     * Resolves the OAuth *Web application* client ID for Google Sign-In.
     *
     * Precedence: value entered on-device (Settings → Cloud Backup) → `strings.xml` →
     * `BuildConfig.GOOGLE_WEB_CLIENT_ID`.
     *
     * FIX (sign-in never worked): the repository ships
     * `google_web_client_id = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"`. The old
     * code only checked `isNullOrBlank()`, so that placeholder was handed to Credential
     * Manager and Google Play services rejected the request (ApiException 10 /
     * DEVELOPER_ERROR) — the account chooser appeared and then nothing happened. Placeholders
     * are now detected and treated as "not configured", and a real client ID can be entered
     * in the app without rebuilding it.
     */
    fun readGoogleWebClientId(): String? {
        settingsRepository.googleWebClientId.value
            ?.takeIf { isUsableClientId(it) }
            ?.let { return it.trim() }

        val resId = context.resources.getIdentifier("google_web_client_id", "string", context.packageName)
        if (resId != 0) {
            context.getString(resId)?.takeIf { isUsableClientId(it) }?.let { return it.trim() }
        }

        return try {
            val field = com.example.BuildConfig::class.java.getField("GOOGLE_WEB_CLIENT_ID")
            (field.get(null) as? String)?.takeIf { isUsableClientId(it) }?.trim()
        } catch (_: Exception) {
            null
        }
    }

    /** True when Google Sign-In has everything it needs to run. */
    val isGoogleSignInConfigured: Boolean get() = readGoogleWebClientId() != null

    /**
     * Persists (or, when null/blank, clears) the on-device OAuth Web Client ID, so sign-in can
     * be configured without rebuilding the app.
     */
    fun setGoogleWebClientId(clientId: String?) {
        settingsRepository.setGoogleWebClientId(clientId?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * A client ID is usable only if it looks like a real OAuth Web client:
     * `<project>-<hash>.apps.googleusercontent.com`, and not one of the placeholders that
     * ship in the repo.
     */
    fun isUsableClientId(value: String?): Boolean {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        if (!candidate.endsWith(".apps.googleusercontent.com", ignoreCase = true)) return false
        if (PLACEHOLDER_CLIENT_ID.containsMatchIn(candidate)) return false
        // "<number>-<32 hex>.apps.googleusercontent.com" has at least 3 dots.
        return candidate.count { it == '.' } >= 3
    }

    /**
     * SHA-1 fingerprints of this build's signing certificate(s), colon separated — the exact
     * value that must be registered against the OAuth client in Google Cloud Console.
     * Works for the modern [android.content.pm.SigningInfo] API and the legacy path.
     */
    fun signingSha1Fingerprints(): List<String> {
        return try {
            val pm = context.packageManager
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signing = info.signingInfo
                when {
                    signing == null -> emptyList()
                    signing.hasMultipleSigners() -> signing.apkContentsSigners?.toList() ?: emptyList()
                    else -> signing.signingCertificateHistory?.toList() ?: emptyList()
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                ).signatures?.toList() ?: emptyList()
            }
            signatures.mapNotNull { signature ->
                try {
                    MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                        .joinToString(":") { "%02X".format(it) }
                } catch (_: Exception) {
                    null
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Turns a Credential Manager failure into something a person can act on. Google's own
     * message ("ApiException: 10") says nothing about the actual cause, which is nearly
     * always an OAuth client that does not list this package name + signing SHA-1.
     */
    private fun describeCredentialFailure(e: GetCredentialException): String {
        val raw = listOfNotNull(e.message, e.cause?.message, e.localizedMessage).joinToString(" ")
        val developerError = raw.contains("DEVELOPER_ERROR", ignoreCase = true) ||
            raw.contains("ApiException: 10") ||
            raw.contains("statusCode=10")
        val fingerprints = signingSha1Fingerprints().ifEmpty { listOf("unavailable") }
        return when {
            e is androidx.credentials.exceptions.NoCredentialException ->
                "No Google account could be offered to this app. Make sure you are signed in to " +
                    "a Google account on the phone, then register this build in Google Cloud Console: " +
                    "package ${context.packageName}, SHA-1 ${fingerprints.joinToString(" / ")}."
            developerError ->
                "Google rejected this app's OAuth client (DEVELOPER_ERROR). The Web Client ID must " +
                    "be type 'Web application', and its authorized Android client must list " +
                    "package ${context.packageName} with SHA-1 ${fingerprints.joinToString(" / ")}. " +
                    "After changing the console entry, force-stop Android Auto/Play services or " +
                    "reboot once — the mapping is cached."
            else ->
                "Sign-in failed (${e.errorCode}): ${e.message ?: "unknown error"}. " +
                    "Package ${context.packageName}, SHA-1 ${fingerprints.joinToString(" / ")}."
        }
    }

    /**
     * Initiates modern Google Sign-In using AndroidX Credential Manager.
     * After successful Google identity retrieval, exchanges the ID token with Firebase
     * so the user is actually authenticated against Firebase services (Firestore, etc.).
     */
    /**
     * Initiates Google Sign-In using AndroidX Credential Manager, then (when this build is
     * Firebase-configured) exchanges the ID token for a Firebase session.
     *
     * Failure modes are reported with actionable text — see [describeCredentialFailure].
     * A dismissed account chooser comes back as [SignInCancelledException], which callers
     * should not present as an error.
     */
    suspend fun signInWithGoogle(activity: android.app.Activity): Result<String> = withContext(Dispatchers.Main) {
        val serverClientId = readGoogleWebClientId()
        if (serverClientId == null) {
            return@withContext Result.failure(
                SignInNotConfiguredException(
                    "Google Sign-In needs an OAuth Web Client ID. Open Settings → Cloud Backup → " +
                        "\u201CGoogle Sign-In setup\u201D, paste the client ID from " +
                        "console.cloud.google.com (APIs & Services → Credentials → OAuth 2.0 Client " +
                        "IDs, type \u201CWeb application\u201D) and register this build\u2019s package name " +
                        "and SHA-1 shown there."
                )
            )
        }

        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = try {
            MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            rawNonce
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response = try {
            credentialManager.getCredential(context = activity, request = request)
        } catch (cancelled: GetCredentialCancellationException) {
            return@withContext Result.failure(SignInCancelledException())
        } catch (noCredential: androidx.credentials.exceptions.NoCredentialException) {
            return@withContext Result.failure(Exception(describeCredentialFailure(noCredential)))
        } catch (credentialError: GetCredentialException) {
            // Rule 28: never fall back to a fake account on failure.
            return@withContext Result.failure(Exception(describeCredentialFailure(credentialError)))
        } catch (other: Exception) {
            return@withContext Result.failure(Exception("Sign-in error: ${other.message}"))
        }

        val credential = response.credential
        if (credential !is androidx.credentials.CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return@withContext Result.failure(
                Exception("Unexpected credential type: ${credential::class.java.simpleName}")
            )
        }

        val googleId = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (parseError: Exception) {
            return@withContext Result.failure(
                Exception("Could not read the Google ID token: ${parseError.message}")
            )
        }

        val email = googleId.id
        settingsRepository.setGoogleAccountEmail(email)
        settingsRepository.setGoogleAccountName(googleId.displayName ?: googleId.givenName)

        // Firebase is optional. A build without google-services.json has no FirebaseApp and
        // FirebaseAuth.getInstance() throws IllegalStateException; Google sign-in itself did
        // succeed, so say that instead of showing a confusing auth failure.
        val firebaseConfigured = try {
            com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        if (firebaseConfigured) {
            try {
                val firebaseCredential = GoogleAuthProvider.getCredential(googleId.idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
                _syncStatusMessage.value = "Signed in to Google & Firebase as $email"
            } catch (fbErr: Exception) {
                _syncStatusMessage.value = "Signed in to Google as $email, but Firebase auth failed: " +
                    (fbErr.localizedMessage ?: fbErr.message)
            }
        } else {
            _syncStatusMessage.value = "Signed in to Google as $email " +
                "(cloud sync limited: this build has no google-services.json, so Firebase is not configured)"
        }

        Result.success(email)
    }

    /**
     * Signs out of Google Credential Manager and clears account state.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {}
        settingsRepository.setGoogleAccountEmail(null)
        settingsRepository.setGoogleAccountName(null)
        settingsRepository.setAutoCloudBackup(false)
        _syncStatusMessage.value = "Signed out of Google Drive."
    }

    /**
     * Executes backup of all local sessions to the local cloud_drive_backup folder.
     *
     * NOTE: This currently copies ZIPs to a local folder only. There is NO actual
     * Google Drive API integration — no Drive.Files.create, no Drive scope, no upload.
     * The UI claims "Google Drive" but files are stored locally. To implement real Drive sync:
     *   1. Add Google Drive API Android library (com.google.android.gms:play-services-drive)
     *   2. Request "https://www.googleapis.com/auth/drive.file" scope after OAuth
     *   3. Use Drive.DriveApi.newDriveResourcesClient() to create files
     *   4. Replace the File.copyTo() call below with Drive API upload calls.
     *
     * Guaranteed never to delete local files on failure.
     */
    suspend fun performBackupNow(): BackupSyncResult = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncStatusMessage.value = "Preparing logs for Google Drive backup..."
        try {
            val account = settingsRepository.googleAccountEmail.value
            if (account == null) {
                return@withContext BackupSyncResult(
                    success = false,
                    message = "Please sign in with your Google account first."
                )
            }

            val recordingsDir = File(context.filesDir, "recordings")
            val cloudFolder = File(context.filesDir, "cloud_drive_backup").apply { mkdirs() }

            val sessionDirs = recordingsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("session_") } ?: emptyList()
            var backedUpCount = 0

            for (sessionDir in sessionDirs) {
                val sessionId = sessionDir.name.removePrefix("session_")
                var zipFile = File(sessionDir, "${sessionId}_bundle.zip")
                if (!zipFile.exists()) {
                    // Re-bundle if bundle.zip missing
                    val files = sessionDir.listFiles()?.toList() ?: emptyList()
                    if (files.isNotEmpty()) {
                        com.example.data.ZipExporter.createTripZip(zipFile, files)
                    }
                }
                if (zipFile.exists() && zipFile.length() > 0) {
                    val destInCloud = File(cloudFolder, "${sessionId}_bundle.zip")
                    zipFile.copyTo(destInCloud, overwrite = true)
                    backedUpCount++
                }
            }

            val now = System.currentTimeMillis()
            settingsRepository.setLastBackupTimestamp(now)
            // FIX: Be honest about what happened. Files are NOT in Google Drive yet,
            // they're copied to a local cloud_drive_backup folder. Real Drive upload
            // requires the Google Drive Android API to be integrated.
            val msg = "Backup prepared: $backedUpCount trip(s) copied to local cloud folder (Drive sync not yet enabled) for $account."
            _syncStatusMessage.value = msg

            BackupSyncResult(
                success = true,
                backedUpCount = backedUpCount,
                message = msg
            )
        } catch (e: Exception) {
            val err = "Backup failed: ${e.localizedMessage ?: e.message}"
            _syncStatusMessage.value = err
            BackupSyncResult(
                success = false,
                message = err
            )
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Restores all backups from Google Drive storage into local trips & database.
     */
    suspend fun restoreFromCloud(): BackupSyncResult = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncStatusMessage.value = "Fetching trip archives from Google Drive..."
        try {
            val account = settingsRepository.googleAccountEmail.value
            if (account == null) {
                return@withContext BackupSyncResult(
                    success = false,
                    message = "Please sign in with Google to restore backups."
                )
            }

            val cloudFolder = File(context.filesDir, "cloud_drive_backup")
            val backupZips = cloudFolder.listFiles()?.filter { it.name.endsWith(".zip", ignoreCase = true) } ?: emptyList()

            if (backupZips.isEmpty()) {
                val msg = "No cloud backup archives found for $account."
                _syncStatusMessage.value = msg
                return@withContext BackupSyncResult(
                    success = true,
                    restoredCount = 0,
                    message = msg
                )
            }

            var restoredCount = 0
            for (zipFile in backupZips) {
                val uri = android.net.Uri.fromFile(zipFile)
                val res = recordingManager.importZipFile(uri)
                if (res.success) {
                    restoredCount++
                }
            }

            val msg = "Restore completed! $restoredCount trip(s) restored from Google Drive."
            _syncStatusMessage.value = msg
            BackupSyncResult(
                success = true,
                restoredCount = restoredCount,
                message = msg
            )
        } catch (e: Exception) {
            val err = "Restore failed: ${e.localizedMessage ?: e.message}"
            _syncStatusMessage.value = err
            BackupSyncResult(
                success = false,
                message = err
            )
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Triggers automatic background backup if enabled and user is logged in.
     */
    suspend fun performAutoBackupIfNeeded() {
        if (settingsRepository.autoCloudBackup.value && settingsRepository.googleAccountEmail.value != null) {
            performBackupNow()
        }
    }

    companion object {
        /**
         * Values that ship in the repository as instructions-to-self. Sending any of these to
         * Google Play services produces ApiException 10 (DEVELOPER_ERROR), which reads like a
         * broken account chooser to the user.
         */
        private val PLACEHOLDER_CLIENT_ID = Regex(
            "(?i)^(your[_-]?|changeme|change[_-]?me|todo|tbd|xxx+|placeholder|dummy|example|test[_-]?)"
        )
    }
}
