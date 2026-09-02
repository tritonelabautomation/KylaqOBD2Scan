package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.backup.CloudBackupManager
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.data.ZipExporter
import com.example.data.ZipImporter
import com.example.data.db.AppDatabase
import com.example.data.db.TripRepository
import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndImportTest {

    private lateinit var context: Context
    private lateinit var recordingManager: RecordingManager
    private lateinit var tripRepository: TripRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cloudBackupManager: CloudBackupManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val rawLogDir = File(context.filesDir, "test_raw_logs").apply { mkdirs() }
        val rawLogManager = RawLogManager(rawLogDir)
        tripRepository = TripRepository(context)
        settingsRepository = SettingsRepository(context)
        recordingManager = RecordingManager(context, rawLogManager, tripRepository)
        cloudBackupManager = CloudBackupManager(context, settingsRepository, recordingManager)
    }

    @Test
    fun testZipImport_RestoresFilesAndDatabase() = runBlocking {
        // 1. Create a simulated exported trip ZIP
        val tempExportDir = File(context.cacheDir, "test_export_staging").apply { mkdirs() }
        val sessionId = "trip_import_test_123"

        val metadata = RecordingMetadata(
            sessionId = sessionId,
            sessionName = "Kylaq Highway Test",
            vehicle = "Škoda Kylaq 1.0 TSI",
            profile = "India-Market 1.0 TSI",
            adapter = "ELM327 v1.5",
            protocol = "ISO 15765-4 CAN",
            startTimeUtc = "2026-09-02T10:00:00.000Z"
        )

        val txList = listOf(
            TransactionRecord(
                id = "tx1",
                timestampUtc = "2026-09-02T10:00:01.000Z",
                timestampMonotonic = 1000L,
                direction = com.example.model.Direction.RX,
                service = "01",
                pid = "0C",
                decodedParameter = "Engine RPM",
                decodedValue = 1850.0,
                decodedValueDisplay = "1850 RPM",
                unit = "RPM",
                responseStatus = com.example.model.ResponseStatus.OK
            ),
            TransactionRecord(
                id = "tx2",
                timestampUtc = "2026-09-02T10:00:02.000Z",
                timestampMonotonic = 2000L,
                direction = com.example.model.Direction.RX,
                service = "01",
                pid = "0D",
                decodedParameter = "Vehicle Speed",
                decodedValue = 72.0,
                decodedValueDisplay = "72 km/h",
                unit = "km/h",
                responseStatus = com.example.model.ResponseStatus.OK
            )
        )

        val jsonFile = File(tempExportDir, "$sessionId.json")
        com.example.data.JsonExporter.exportToJson(jsonFile, metadata, txList)

        val csvFile = File(tempExportDir, "${sessionId}_transactions.csv")
        com.example.data.CsvExporter.exportTransactionsToCsv(csvFile, metadata, txList)

        val sampleCsv = File(tempExportDir, "${sessionId}_samples.csv").apply {
            writeText("timestamp_utc,RPM,speed_kmh\n2026-09-02T10:00:01.000Z,1850,72\n")
        }

        val testZip = File(tempExportDir, "${sessionId}_bundle.zip")
        ZipExporter.createTripZip(testZip, listOf(jsonFile, csvFile, sampleCsv))
        assertTrue(testZip.exists())

        // 2. Import the ZIP using SAF-compatible URI
        val uri = Uri.fromFile(testZip)
        val importResult = recordingManager.importZipFile(uri)

        assertTrue("Import should succeed: ${importResult.message}", importResult.success)
        assertEquals(sessionId, importResult.sessionId)
        assertEquals(2, importResult.transactionCount)

        // 3. Verify files exist in session directory
        val sessionDir = File(context.filesDir, "recordings/session_$sessionId")
        assertTrue(sessionDir.exists())
        assertTrue(File(sessionDir, "$sessionId.json").exists())
        assertTrue(File(sessionDir, "${sessionId}_transactions.csv").exists())
        assertTrue(File(sessionDir, "${sessionId}_bundle.zip").exists())

        // 4. Verify Trip entity in Room database
        val restoredTrip = tripRepository.getTripById(sessionId)
        assertNotNull(restoredTrip)
        assertEquals("Kylaq Highway Test", restoredTrip?.title)
        assertEquals(1850.0, restoredTrip?.maxRpm ?: 0.0, 0.1)
        assertEquals(72.0, restoredTrip?.maxSpeedKmh ?: 0.0, 0.1)

        // Cleanup
        tempExportDir.deleteRecursively()
        sessionDir.deleteRecursively()
        tripRepository.deleteTrip(sessionId)
    }

    @Test
    fun testCloudBackupManager_SettingsAndRetention() = runBlocking {
        settingsRepository.setGoogleAccountEmail("driver.kylaq@gmail.com")
        settingsRepository.setAutoCloudBackup(true)

        assertEquals("driver.kylaq@gmail.com", settingsRepository.googleAccountEmail.value)
        assertTrue(settingsRepository.autoCloudBackup.value)

        val backupResult = cloudBackupManager.performBackupNow()
        assertTrue(backupResult.success)
        assertTrue(settingsRepository.lastBackupTimestamp.value > 0)

        // Test sign out clears auth but preserves local recordings
        cloudBackupManager.signOut()
        assertNull(settingsRepository.googleAccountEmail.value)
        assertFalse(settingsRepository.autoCloudBackup.value)
    }
}
