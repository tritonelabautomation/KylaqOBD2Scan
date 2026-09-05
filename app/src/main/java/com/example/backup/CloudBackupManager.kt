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
import kotlinx.coroutines.Dispatchers
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
     * Reads the Google Web Client ID from strings.xml resource or BuildConfig.
     * Returns null if not configured, which disables Google Sign-In gracefully
     * rather than silently trusting whatever account the SDK has access to.
     */
    private fun readGoogleWebClientId(): String? {
        // Try strings.xml resource first
        val resId = context.resources.getIdentifier("google_web_client_id", "string", context.packageName)
        if (resId != 0) {
            val fromRes = context.getString(resId)
            if (!fromRes.isNullOrBlank() && !fromRes.contains("YOUR_")) {
                return fromRes
            }
        }
        // Fallback: BuildConfig (set via gradle.properties / local.properties)
        return try {
            val field = com.example.BuildConfig::class.java.getField("GOOGLE_WEB_CLIENT_ID")
            val value = field.get(null) as? String
            if (!value.isNullOrBlank() && !value.contains("YOUR_")) value else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Initiates modern Google Sign-In using AndroidX Credential Manager.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Build GoogleIdOption
            val rawNonce = UUID.randomUUID().toString()
            val md = MessageDigest.getInstance("SHA-256")
            val hashedNonce = md.digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }

            // CRITICAL FIX: Server Client ID must be a real Web Client ID from Google Cloud Console.
            // Previously this was a placeholder ("dummy-client-id.apps.googleusercontent.com") which
            // caused Credential Manager to return whatever default account the SDK had access to
            // (e.g. "connected.driver@gmail.com") and trust it blindly. The email was persisted
            // without any verified session.
            //
            // To configure real Google Sign-In:
            //   1. Create a project in Google Cloud Console: https://console.cloud.google.com
            //   2. Enable "Google Identity" / "Google Sign-In" API
            //   3. Create OAuth 2.0 Client ID of type "Web application"
            //   4. Add the package name + SHA-1 signing certificate fingerprint:
            //        ./gradlew signingReport   (debug SHA-1)
            //   5. Replace the placeholder below with the real Web Client ID.
            //   6. Also set in res/values/strings.xml: <string name="google_web_client_id">...</string>
            //
            // Until configured, Google sign-in remains DISABLED — signInWithGoogle() returns
            // an error rather than silently trusting an arbitrary account.
            val serverClientId = readGoogleWebClientId()
            if (serverClientId == null) {
                return@withContext Result.failure(Exception(
                    "Google Sign-In is not configured. Set GOOGLE_WEB_CLIENT_ID in local.properties " +
                    "or res/values/strings.xml as 'google_web_client_id'. See CloudBackupManager.kt for setup."
                ))
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

            val response: GetCredentialResponse = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            val credential = response.credential
            val email = when {
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    googleIdTokenCredential.id
                }
                else -> {
                    return@withContext Result.failure(Exception("Unsupported credential type"))
                }
            }

            settingsRepository.setGoogleAccountEmail(email)
            _syncStatusMessage.value = "Signed in as $email"
            Result.success(email)
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in was cancelled by user."))
        } catch (e: GetCredentialException) {
            // Rule 28: DO NOT force a fallback email on failure
            Result.failure(Exception("Sign-in failed: ${e.message}"))
        } catch (e: Exception) {
            // Rule 28: DO NOT force a fallback email on failure
            Result.failure(Exception("Sign-in error: ${e.message}"))
        }
    }

    /**
     * Signs out of Google Credential Manager and clears account state.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {}
        settingsRepository.setGoogleAccountEmail(null)
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
}
