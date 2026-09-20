package com.example.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.RecordingManager
import com.example.data.ZipExporter
import com.example.data.ZipImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Drive backup WITHOUT OAuth — the Fuelio-style sync that actually works out of the box.
 *
 * Credential Manager sign-in needs an OAuth Web Client ID registered in Google Cloud Console
 * (one-time manual setup). The Storage Access Framework needs nothing: the system picker opens
 * the user's Drive (triggering the normal Google account chooser if the Drive app is signed out),
 * the user selects a folder once, and we keep a persistable permission to read/write backup ZIPs
 * there forever. Send, restore and auto-backup all run through this client.
 */
object DriveBackupClient {

    data class BackupFile(val name: String, val uri: Uri, val sizeBytes: Long, val modifiedMs: Long)

    /** Lists existing backup ZIPs in the chosen Drive folder, newest first. */
    fun listBackups(context: Context, treeUri: Uri): List<BackupFile> =
        DocumentFile.fromTreeUri(context, treeUri)
            ?.listFiles()
            ?.filter { it.isFile && (it.name ?: "").endsWith(".zip") }
            ?.map { BackupFile(it.name ?: "backup.zip", it.uri, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.modifiedMs }
            ?: emptyList()

    /**
     * Zips every recording plus the two things that used to be lost on a reinstall and
     * writes it into the chosen Drive folder. Returns the file name.
     *
     * Added 2026-09-16 so that migrating to a stably-signed build (one final uninstall)
     * and any future phone move are lossless:
     *  - `raw_logs/raw_log_*.txt` — drives killed before STOP, the only copy of those
     *    trips; after a restore the recovery banner rebuilds them.
     *  - `app_data_snapshot.json` — fuel ledger, ride/coast/tank insight logs, expenses,
     *    documents, reminders, trip plans, maintenance and settings (all of which live in
     *    SharedPreferences, not in `files/recordings/`).
     */
    suspend fun sendBackup(context: Context, treeUri: Uri, recordingManager: RecordingManager): String =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Drive folder is no longer accessible - pick it again.")
            val stamp = com.example.data.RecordTime.format("yyyyMMdd-HHmmss", System.currentTimeMillis())
            val name = "kylaq-obd-backup-$stamp.zip"
            val cacheZip = File(context.cacheDir, name)
            val snapshotFile = File(context.cacheDir, AppDataSnapshot.FILE_NAME)
            val files = recordingManager.recordingsDir.walkTopDown().filter { it.isFile }.toMutableList()

            val rawLogsDir = File(context.filesDir, "raw_logs")
            if (rawLogsDir.isDirectory) {
                files += rawLogsDir.listFiles()?.filter { it.isFile && it.name.startsWith("raw_log_") }
                    ?: emptyList()
            }

            // Crash journal (owner 2026-09-20: "No app crash logs why it has crashed"):
            // every recorded crash rides along in the backup, so the reason reaches Drive
            // even if the phone itself never opens cleanly again. ZipImporter ignores the
            // crash_* names on restore (Kind.UNKNOWN), so they can never pollute a trip.
            val crashDir = com.example.data.CrashJournal.journalDir(context)
            if (crashDir.isDirectory) {
                files += crashDir.listFiles()?.filter { it.isFile } ?: emptyList()
            }

            // Best-effort: a snapshot failure must not cost the owner their recordings.
            runCatching { PrefsSnapshotter.writeToFile(context, snapshotFile) }
                .onSuccess { files += it }

            ZipExporter.createTripZip(cacheZip, files)
            snapshotFile.delete()

            val doc = tree.createFile("application/zip", name)
                ?: error("Drive refused to create $name")
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                cacheZip.inputStream().use { it.copyTo(out) }
            } ?: error("Drive folder is not writable")
            cacheZip.delete()
            name
        }

    /** Imports a backup ZIP from Drive: every trip session, unsaved raw logs and the data snapshot. */
    suspend fun restoreBackup(context: Context, uri: Uri, recordingManager: RecordingManager): String =
        withContext(Dispatchers.IO) {
            val result = ZipImporter.importTripZip(
                context, uri, recordingManager.recordingsDir, recordingManager.tripRepository
            )
            result.toString()
        }
}
