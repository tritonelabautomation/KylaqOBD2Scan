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

    /** Zips every recording and writes it into the chosen Drive folder. Returns the file name. */
    suspend fun sendBackup(context: Context, treeUri: Uri, recordingManager: RecordingManager): String =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Drive folder is no longer accessible - pick it again.")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "kylaq-obd-backup-$stamp.zip"
            val cacheZip = File(context.cacheDir, name)
            val files = recordingManager.recordingsDir.walkTopDown().filter { it.isFile }.toList()
            ZipExporter.createTripZip(cacheZip, files)
            val doc = tree.createFile("application/zip", name)
                ?: error("Drive refused to create $name")
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                cacheZip.inputStream().use { it.copyTo(out) }
            } ?: error("Drive folder is not writable")
            cacheZip.delete()
            name
        }

    /** Imports a backup ZIP from Drive back into the app (trips + telemetry). */
    suspend fun restoreBackup(context: Context, uri: Uri, recordingManager: RecordingManager): String =
        withContext(Dispatchers.IO) {
            val result = ZipImporter.importTripZip(
                context, uri, recordingManager.recordingsDir, recordingManager.tripRepository
            )
            result.toString()
        }
}
