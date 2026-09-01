package com.example.data

import android.content.Context
import com.example.data.db.TripRepository
import com.example.data.db.entities.RawLogEntity
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import com.example.model.RecordingMetadata
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class SavedRecording(
    val metadata: RecordingMetadata,
    val transactionCount: Int,
    val transactionCsvFile: File,
    val samplesCsvFile: File,
    val jsonFile: File,
    val rawLogFile: File?,
    val zipFile: File? = null
)

/**
 * Manages active recording lifecycle and saved sessions storage, backed by Room database and file system
 */
class RecordingManager(
    private val context: Context,
    private val rawLogManager: RawLogManager,
    val tripRepository: TripRepository = TripRepository(context)
) {

    private val recordingsDir: File = File(context.filesDir, "recordings").apply {
        if (!exists()) mkdirs()
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentSessionMetadata = MutableStateFlow<RecordingMetadata?>(null)
    val currentSessionMetadata: StateFlow<RecordingMetadata?> = _currentSessionMetadata.asStateFlow()

    private val _currentTransactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val currentTransactions: StateFlow<List<TransactionRecord>> = _currentTransactions.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<SavedRecording>>(emptyList())
    val savedRecordings: StateFlow<List<SavedRecording>> = _savedRecordings.asStateFlow()

    private val activeTransactionList = mutableListOf<TransactionRecord>()
    private val activeSampleList = mutableListOf<SynchronizedSample>()
    private var currentSample = SynchronizedSample(
        timestampUtc = "",
        timestampMonotonic = 0L
    )

    private var sessionStartTimestamp = 0L

    init {
        loadSavedRecordings()
    }

    fun startRecording(
        vehicleName: String = "Škoda Kylaq 1.0 TSI (EA211)",
        profileName: String = "India-Market 1.0 TSI",
        adapterName: String = "ELM327 v1.5 Bluetooth Classic",
        protocolName: String = "ISO 15765-4 CAN 11-bit 500kbps"
    ): RecordingMetadata {
        val sessionId = UUID.randomUUID().toString().take(8)
        sessionStartTimestamp = System.currentTimeMillis()
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val dateDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val defaultName = "Kylaq Run $dateDisplay"

        val metadata = RecordingMetadata(
            sessionId = sessionId,
            sessionName = defaultName,
            vehicle = vehicleName,
            profile = profileName,
            adapter = adapterName,
            protocol = protocolName,
            canBitrate = "500 kbps",
            startTimeUtc = nowUtc
        )

        synchronized(activeTransactionList) {
            activeTransactionList.clear()
            activeSampleList.clear()
        }

        _currentSessionMetadata.value = metadata
        _currentTransactions.value = emptyList()
        _isRecording.value = true

        rawLogManager.startFileLogging(sessionId)

        // Asynchronously insert initial Trip record in Room
        CoroutineScope(Dispatchers.IO).launch {
            tripRepository.insertTrip(
                TripEntity(
                    id = sessionId,
                    title = defaultName,
                    vehicleName = vehicleName,
                    adapterName = adapterName,
                    protocolName = protocolName,
                    startTimeUtc = nowUtc,
                    startTimestamp = sessionStartTimestamp,
                    status = "RECORDING"
                )
            )
        }

        return metadata
    }

    fun recordTransaction(tx: TransactionRecord) {
        if (!_isRecording.value) return

        synchronized(activeTransactionList) {
            activeTransactionList.add(tx)
            _currentTransactions.value = activeTransactionList.toList()

            // Update current synchronized sample
            val updated = when (tx.pid.uppercase()) {
                "0C" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, rpm = tx.decodedValue)
                "0D" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, speedKmh = tx.decodedValue)
                "04" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, engineLoadPct = tx.decodedValue)
                "0B" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, mapKpa = tx.decodedValue)
                "11" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, throttlePct = tx.decodedValue)
                "49" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, acceleratorPct = tx.decodedValue)
                "05" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, coolantC = tx.decodedValue)
                "0F" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, iatC = tx.decodedValue)
                "46" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, ambientC = tx.decodedValue)
                "9D" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, fuelRateLh = tx.decodedValue)
                "62" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, engineTorquePct = tx.decodedValue)
                "42" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, voltageV = tx.decodedValue)
                "6D" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, fuelPressureRaw = tx.rawPayload)
                "70" -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic, boostPressureRaw = tx.rawPayload)
                else -> currentSample.copy(timestampUtc = tx.timestampUtc, timestampMonotonic = tx.timestampMonotonic)
            }
            currentSample = updated
            activeSampleList.add(updated)
        }
    }

    suspend fun stopRecording(): SavedRecording? = withContext(Dispatchers.IO) {
        val metadata = _currentSessionMetadata.value ?: return@withContext null
        val endTimestamp = System.currentTimeMillis()
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        metadata.endTimeUtc = nowUtc

        val txList: List<TransactionRecord>
        val sampleList: List<SynchronizedSample>
        synchronized(activeTransactionList) {
            txList = activeTransactionList.toList()
            sampleList = activeSampleList.toList()
        }

        val rawLogFile = rawLogManager.stopFileLogging()

        // Generate files
        val sessionDir = File(recordingsDir, "session_${metadata.sessionId}").apply { mkdirs() }
        val txCsvFile = File(sessionDir, "${metadata.sessionId}_transactions.csv")
        val sampleCsvFile = File(sessionDir, "${metadata.sessionId}_samples.csv")
        val jsonFile = File(sessionDir, "${metadata.sessionId}.json")

        CsvExporter.exportTransactionsToCsv(txCsvFile, metadata, txList)
        CsvExporter.exportSynchronizedSamplesToCsv(sampleCsvFile, sampleList)
        JsonExporter.exportToJson(jsonFile, metadata, txList)

        // Copy raw log if available
        val destRawLog = if (rawLogFile != null && rawLogFile.exists()) {
            val dest = File(sessionDir, "${metadata.sessionId}_raw.txt")
            rawLogFile.copyTo(dest, overwrite = true)
            dest
        } else null

        // Generate full ZIP bundle
        val zipFile = File(sessionDir, "${metadata.sessionId}_bundle.zip")
        val filesToZip = listOfNotNull(txCsvFile, sampleCsvFile, jsonFile, destRawLog)
        ZipExporter.createTripZip(zipFile, filesToZip)

        // Calculate summary metrics for Room
        val maxRpm = txList.filter { it.pid.equals("0C", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val maxSpeed = txList.filter { it.pid.equals("0D", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val maxCoolant = txList.filter { it.pid.equals("05", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val voltList = txList.filter { it.pid.equals("42", ignoreCase = true) }.mapNotNull { it.decodedValue }
        val avgVolt = if (voltList.isNotEmpty()) voltList.average() else 0.0
        val detectedEcus = txList.mapNotNull { it.canRxId.takeIf { id -> id.isNotBlank() } }.distinct().joinToString(", ").ifBlank { "7E8" }
        val durationSec = maxOf(1L, (endTimestamp - sessionStartTimestamp) / 1000)

        // Save complete entities into Room Database
        val tripEntity = TripEntity(
            id = metadata.sessionId,
            title = metadata.sessionName,
            vehicleName = metadata.vehicle,
            adapterName = metadata.adapter,
            protocolName = metadata.protocol,
            startTimeUtc = metadata.startTimeUtc,
            endTimeUtc = nowUtc,
            startTimestamp = sessionStartTimestamp,
            endTimestamp = endTimestamp,
            durationSeconds = durationSec,
            status = "COMPLETED",
            sampleCount = txList.size,
            rawLogCount = txList.size,
            maxRpm = maxRpm,
            maxSpeedKmh = maxSpeed,
            maxCoolantC = maxCoolant,
            avgVoltageV = avgVolt,
            detectedEcus = detectedEcus,
            healthScore = 100
        )
        tripRepository.insertTrip(tripEntity)

        // Insert telemetry sample records
        val dbSamples = txList.mapIndexed { idx, tx ->
            TelemetrySampleEntity(
                tripId = metadata.sessionId,
                timestamp = tx.timestampMonotonic,
                timestampUtc = tx.timestampUtc,
                ecuCanId = tx.canRxId.ifBlank { "7E8" },
                pid = tx.pid,
                parameterName = tx.decodedParameter.ifBlank { "PID ${tx.pid}" },
                rawHex = tx.responseHex,
                numericValue = tx.decodedValue,
                displayValue = tx.decodedValueDisplay,
                unit = tx.unit,
                quality = "VALID",
                sequence = idx.toLong()
            )
        }
        tripRepository.insertSamples(dbSamples)

        // Auto-run local AI Doctor analysis
        try {
            tripRepository.runAiCarDoctorAnalysis(metadata.sessionId)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val saved = SavedRecording(
            metadata = metadata,
            transactionCount = txList.size,
            transactionCsvFile = txCsvFile,
            samplesCsvFile = sampleCsvFile,
            jsonFile = jsonFile,
            rawLogFile = destRawLog,
            zipFile = zipFile
        )

        _isRecording.value = false
        _currentSessionMetadata.value = null
        loadSavedRecordings()
        saved
    }

    fun loadSavedRecordings() {
        val result = mutableListOf<SavedRecording>()
        val dirs = recordingsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("session_") } ?: emptyList()

        for (dir in dirs) {
            val sessionId = dir.name.removePrefix("session_")
            val jsonFile = File(dir, "$sessionId.json")
            val txCsv = File(dir, "${sessionId}_transactions.csv")
            val sampleCsv = File(dir, "${sessionId}_samples.csv")
            val rawFile = File(dir, "${sessionId}_raw.txt")
            val zipFile = File(dir, "${sessionId}_bundle.zip")

            if (jsonFile.exists()) {
                try {
                    val jsonStr = jsonFile.readText()
                    val root = JSONObject(jsonStr)
                    val metaObj = root.getJSONObject("sessionMetadata")
                    val txArray = root.getJSONArray("transactions")

                    val meta = RecordingMetadata(
                        sessionId = metaObj.getString("sessionId"),
                        sessionName = metaObj.optString("sessionName", "Session $sessionId"),
                        vehicle = metaObj.optString("vehicle", "Škoda Kylaq 1.0 TSI"),
                        profile = metaObj.optString("profile", "India-Market 1.0 TSI"),
                        adapter = metaObj.optString("adapter", "ELM327 v1.5"),
                        protocol = metaObj.optString("protocol", "ISO 15765-4"),
                        canBitrate = metaObj.optString("canBitrate", "500 kbps"),
                        startTimeUtc = metaObj.getString("startTimeUtc"),
                        endTimeUtc = metaObj.optString("endTimeUtc", null),
                        appVersion = metaObj.optString("appVersion", "1.0")
                    )

                    result.add(
                        SavedRecording(
                            metadata = meta,
                            transactionCount = txArray.length(),
                            transactionCsvFile = txCsv,
                            samplesCsvFile = sampleCsv,
                            jsonFile = jsonFile,
                            rawLogFile = if (rawFile.exists()) rawFile else null,
                            zipFile = if (zipFile.exists()) zipFile else null
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        _savedRecordings.value = result.sortedByDescending { it.metadata.startTimeUtc }
    }

    fun renameRecording(sessionId: String, newName: String) {
        val sessionDir = File(recordingsDir, "session_$sessionId")
        val jsonFile = File(sessionDir, "$sessionId.json")
        if (jsonFile.exists()) {
            try {
                val jsonStr = jsonFile.readText()
                val root = JSONObject(jsonStr)
                val metaObj = root.getJSONObject("sessionMetadata")
                metaObj.put("sessionName", newName)
                jsonFile.writeText(root.toString(2))
                loadSavedRecordings()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteRecording(sessionId: String) {
        val sessionDir = File(recordingsDir, "session_$sessionId")
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
            loadSavedRecordings()
        }
        CoroutineScope(Dispatchers.IO).launch {
            tripRepository.deleteTrip(sessionId)
        }
    }

    fun deleteAllRecordings() {
        recordingsDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        loadSavedRecordings()
        CoroutineScope(Dispatchers.IO).launch {
            tripRepository.deleteAllTrips()
        }
    }
}


