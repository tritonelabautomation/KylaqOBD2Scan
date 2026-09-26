package com.example.data

import android.content.Context
import android.net.Uri
import com.example.data.db.TripRepository
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ZipImportResult(
    val success: Boolean,
    val sessionId: String = "",
    val sessionName: String = "",
    val transactionCount: Int = 0,
    val sampleCount: Int = 0,
    val message: String = ""
)

object ZipImporter {

    private const val MAX_ZIP_UNCOMPRESSED_BYTES = 100 * 1024 * 1024L // 100MB safety ceiling

    /**
     * Imports a user-selected ZIP from a content Uri (SAF / ACTION_OPEN_DOCUMENT).
     */
    suspend fun importTripZip(
        context: Context,
        uri: Uri,
        recordingsDir: File,
        tripRepository: TripRepository
    ): ZipImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val unzippedFiles = unzipSafely(context, uri, tempDir)
            if (unzippedFiles.isEmpty()) {
                return@withContext ZipImportResult(
                    success = false,
                    message = "No valid files found inside the selected ZIP archive."
                )
            }

            val notes = mutableListOf<String>()

            // 1) Whole-app data snapshot: fuel ledger, ride/coast/tank insight logs,
            //    expenses, documents, reminders, trip plans, maintenance and settings.
            //    Backups made before 2026-09-16 simply do not contain it.
            val snapshotFile = unzippedFiles.firstOrNull {
                com.example.backup.BackupLayout.kindOf(it.name) == com.example.backup.BackupLayout.Kind.DATA_SNAPSHOT
            }
            var restoredValues = 0
            if (snapshotFile != null) {
                val snapshot = com.example.backup.AppDataSnapshot.parse(
                    runCatching { snapshotFile.readText() }.getOrNull()
                )
                if (snapshot == null) {
                    notes += "its data snapshot could not be read and was skipped"
                } else {
                    restoredValues = com.example.backup.PrefsSnapshotter.apply(context, snapshot)
                    notes += "$restoredValues settings/ledger value(s) restored - restart the app to see them on every screen"
                }
            }

            // 2) Raw logs of drives that were never finalized. Restored into files/raw_logs,
            //    where the recovery banner in Trips & Recordings can rebuild them into trips.
            val rawLogsDir = File(context.filesDir, "raw_logs").apply { mkdirs() }
            var restoredLogs = 0
            unzippedFiles
                .filter { com.example.backup.BackupLayout.kindOf(it.name) == com.example.backup.BackupLayout.Kind.UNSAVED_RAW_LOG }
                .forEach { log ->
                    val dest = File(rawLogsDir, log.name)
                    if (!dest.exists()) {
                        runCatching { log.copyTo(dest, overwrite = false) }.onSuccess { restoredLogs++ }
                    }
                }
            if (restoredLogs > 0) {
                notes += "$restoredLogs unsaved raw log(s) restored - open Trips & Recordings to rebuild them into trips"
            }

            // 3) Trip sessions. A Drive backup is ONE flat ZIP holding every session side by
            //    side, and this importer used to take the first file of each type - so it
            //    restored one trip out of many and could even mix session A's JSON with
            //    session B's CSV. Group by session id and import each group on its own.
            val tripFiles = unzippedFiles.filter {
                val kind = com.example.backup.BackupLayout.kindOf(it.name)
                kind != com.example.backup.BackupLayout.Kind.DATA_SNAPSHOT &&
                    kind != com.example.backup.BackupLayout.Kind.UNSAVED_RAW_LOG
            }
            val sessionGroups = com.example.backup.BackupLayout
                .groupBySession(tripFiles.map { it.name })
                .filterKeys { it.isNotBlank() }

            val results: List<ZipImportResult> = when {
                tripFiles.isEmpty() -> emptyList()
                sessionGroups.size > 1 -> sessionGroups.map { (sessionId, groupNames) ->
                    val groupDir = File(tempDir, "group_$sessionId").apply { mkdirs() }
                    val groupFiles = groupNames.mapNotNull { name ->
                        tripFiles.firstOrNull { it.name == name }?.also { source ->
                            runCatching { source.copyTo(File(groupDir, source.name), overwrite = true) }
                        }
                        File(groupDir, name).takeIf { it.exists() }
                    }
                    importSingleSession(groupFiles, recordingsDir, tripRepository)
                }
                // Exactly one session, or a legacy ZIP whose files carry no session id.
                else -> listOf(importSingleSession(tripFiles, recordingsDir, tripRepository))
            }

            val succeeded = results.count { it.success }
            val failed = results.size - succeeded
            val headline = results.firstOrNull { it.success } ?: results.firstOrNull()
            ZipImportResult(
                success = succeeded > 0 || (results.isEmpty() && (restoredValues > 0 || restoredLogs > 0)),
                sessionId = headline?.sessionId ?: "",
                sessionName = headline?.sessionName ?: "",
                transactionCount = results.sumOf { it.transactionCount },
                sampleCount = results.sumOf { it.sampleCount },
                message = buildString {
                    append("Imported $succeeded of ${results.size} trip(s)")
                    if (failed > 0) append(", $failed failed")
                    append(".")
                    notes.forEach { append(' ').append(it).append('.') }
                }
            )
        } catch (e: Exception) {
            ZipImportResult(
                success = false,
                message = "Import failed: ${e.localizedMessage ?: e.message}"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Imports ONE trip session from its already-unzipped files. This is the original
     * single-trip behaviour, unchanged; [importTripZip] now calls it once per session.
     */
    private suspend fun importSingleSession(
        unzippedFiles: List<File>,
        recordingsDir: File,
        tripRepository: TripRepository
    ): ZipImportResult {
        // Explicit return: unlike the old `= withContext { ... }` expression body this is a
        // block body, where a trailing try/catch is a statement, not a value.
        return try {
            // Look for JSON metadata or CSV files
            val jsonFile = unzippedFiles.firstOrNull { it.name.endsWith(".json", ignoreCase = true) }
            val txCsv = unzippedFiles.firstOrNull { it.name.contains("transaction", ignoreCase = true) && it.name.endsWith(".csv", ignoreCase = true) }
            val sampleCsv = unzippedFiles.firstOrNull { it.name.contains("sample", ignoreCase = true) && it.name.endsWith(".csv", ignoreCase = true) }
            val rawLog = unzippedFiles.firstOrNull { it.name.contains("raw", ignoreCase = true) && (it.name.endsWith(".txt", ignoreCase = true) || it.name.endsWith(".log", ignoreCase = true)) }

            var sessionId: String? = null
            var sessionName: String? = null
            var vehicle: String = "Škoda Kylaq 1.0 TSI (EA211)"
            var profile: String = "India-Market 1.0 TSI 6MT/6AT"
            var adapter: String = "ELM327 v1.5 Bluetooth Classic"
            var protocol: String = "ISO 15765-4 CAN 11-bit 500kbps"
            // Fallback stamp for a bundle whose JSON carries no start time. IST with its offset
            // (owner mandate 2026-09-17); the old literal 'Z' claimed UTC while writing local time.
            var startTimeUtc: String = com.example.data.RecordTime.stamp()
            var endTimeUtc: String? = null
            var appVersion: String = "1.0"
            // GPS altitude window of the recorded trip. Null for backups written before
            // 2026-09-15 and for trips that never had an altitude-bearing GPS fix; it is
            // never defaulted to 0.0, which would read as "sea level" on the summary card.
            var maxAltitudeM: Double? = null
            var minAltitudeM: Double? = null
            // Battery voltage extremes of the recorded trip (owner pipeline task 3). Null
            // for backups written before 2026-09-16; never defaulted to 0.0.
            var minVoltageV: Double? = null
            var maxVoltageV: Double? = null
            var startFuelPercent: Double? = null
            var endFuelPercent: Double? = null
            var fuelDeltaPercent: Double? = null

            var parsedTxCount = 0
            val txEntities = mutableListOf<TransactionRecord>()

            if (jsonFile != null && jsonFile.exists()) {
                try {
                    val root = JSONObject(jsonFile.readText())
                    if (root.has("sessionMetadata")) {
                        val metaObj = root.getJSONObject("sessionMetadata")
                        sessionId = metaObj.optString("sessionId", null)
                        sessionName = metaObj.optString("sessionName", null)
                        vehicle = metaObj.optString("vehicle", vehicle)
                        profile = metaObj.optString("profile", profile)
                        adapter = metaObj.optString("adapter", adapter)
                        protocol = metaObj.optString("protocol", protocol)
                        startTimeUtc = metaObj.optString("startTimeUtc", startTimeUtc)
                        endTimeUtc = metaObj.optString("endTimeUtc", null)
                        appVersion = metaObj.optString("appVersion", appVersion)
                        maxAltitudeM = JsonExporter.nullableDouble(metaObj, "maxAltitudeM")
                        minAltitudeM = JsonExporter.nullableDouble(metaObj, "minAltitudeM")
                        minVoltageV = JsonExporter.nullableDouble(metaObj, "minVoltageV")
                        maxVoltageV = JsonExporter.nullableDouble(metaObj, "maxVoltageV")
                        startFuelPercent = JsonExporter.nullableDouble(metaObj, "startFuelPercent")
                        endFuelPercent = JsonExporter.nullableDouble(metaObj, "endFuelPercent")
                        fuelDeltaPercent = JsonExporter.nullableDouble(metaObj, "fuelDeltaPercent")
                    }
                    if (root.has("transactions")) {
                        val txArray = root.getJSONArray("transactions")
                        parsedTxCount = txArray.length()
                        for (i in 0 until txArray.length()) {
                            val txObj = txArray.getJSONObject(i)
                            val dirStr = txObj.optString("direction", "RX")
                            val dir = try { com.example.model.Direction.valueOf(dirStr) } catch (_: Exception) { com.example.model.Direction.RX }
                            val statStr = txObj.optString("responseStatus", "OK")
                            val stat = try { com.example.model.ResponseStatus.valueOf(statStr) } catch (_: Exception) { com.example.model.ResponseStatus.OK }

                            txEntities.add(
                                TransactionRecord(
                                    id = txObj.optString("id", java.util.UUID.randomUUID().toString()),
                                    timestampUtc = txObj.optString("timestampUtc", startTimeUtc),
                                    timestampMonotonic = txObj.optLong("timestampMonotonic", 0L),
                                    direction = dir,
                                    elmCommand = txObj.optString("elmCommand", ""),
                                    canTxId = txObj.optString("canTxId", ""),
                                    canRxId = txObj.optString("canRxId", "7E8"),
                                    requestHex = txObj.optString("requestHex", ""),
                                    responseHex = txObj.optString("responseHex", ""),
                                    service = txObj.optString("service", ""),
                                    pid = txObj.optString("pid", ""),
                                    rawPayload = txObj.optString("rawPayload", ""),
                                    decodedParameter = txObj.optString("decodedParameter", ""),
                                    decodedValue = if (txObj.has("decodedValue") && !txObj.isNull("decodedValue")) txObj.optDouble("decodedValue") else null,
                                    decodedValueDisplay = txObj.optString("decodedValueDisplay", ""),
                                    unit = txObj.optString("unit", ""),
                                    responseStatus = stat,
                                    errorMessage = txObj.optString("errorMessage", null)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Fallback: Parse from transactions CSV if JSON was absent or lacked records
            if (txEntities.isEmpty() && txCsv != null && txCsv.exists()) {
                parseTransactionsFromCsv(txCsv, txEntities)
                parsedTxCount = txEntities.size
            }

            // If sessionId still absent, derive from file names or UUID
            if (sessionId.isNullOrBlank()) {
                val candidateName = jsonFile?.nameWithoutExtension ?: txCsv?.nameWithoutExtension?.substringBefore("_")
                sessionId = candidateName?.ifBlank { null } ?: java.util.UUID.randomUUID().toString().take(8)
            }
            if (sessionName.isNullOrBlank()) {
                sessionName = "Imported Trip $sessionId"
            }

            // Destination session directory
            val sessionDir = File(recordingsDir, "session_$sessionId").apply { mkdirs() }
            val destJson = File(sessionDir, "$sessionId.json")
            val destTxCsv = File(sessionDir, "${sessionId}_transactions.csv")
            val destSampleCsv = File(sessionDir, "${sessionId}_samples.csv")
            val destRaw = File(sessionDir, "${sessionId}_raw.txt")
            val destBundleZip = File(sessionDir, "${sessionId}_bundle.zip")

            // Copy files into session directory
            if (jsonFile != null && jsonFile.exists()) {
                jsonFile.copyTo(destJson, overwrite = true)
            } else {
                // Synthesize JSON if it did not exist
                val synthesizedMeta = RecordingMetadata(
                    sessionId = sessionId,
                    sessionName = sessionName,
                    vehicle = vehicle,
                    profile = profile,
                    adapter = adapter,
                    protocol = protocol,
                    startTimeUtc = startTimeUtc,
                    endTimeUtc = endTimeUtc,
                    appVersion = appVersion,
                    // A synthesized file must not drop what the imported one carried.
                    maxAltitudeM = maxAltitudeM,
                    minAltitudeM = minAltitudeM,
                    minVoltageV = minVoltageV,
                    maxVoltageV = maxVoltageV,
                    startFuelPercent = startFuelPercent,
                    endFuelPercent = endFuelPercent,
                    fuelDeltaPercent = fuelDeltaPercent
                )
                JsonExporter.exportToJson(destJson, synthesizedMeta, txEntities)
            }

            if (txCsv != null && txCsv.exists()) {
                txCsv.copyTo(destTxCsv, overwrite = true)
            } else if (txEntities.isNotEmpty()) {
                val metaForCsv = RecordingMetadata(
                    sessionId = sessionId,
                    sessionName = sessionName,
                    vehicle = vehicle,
                    profile = profile,
                    adapter = adapter,
                    protocol = protocol,
                    startTimeUtc = startTimeUtc,
                    endTimeUtc = endTimeUtc,
                    appVersion = appVersion,
                    maxAltitudeM = maxAltitudeM,
                    minAltitudeM = minAltitudeM,
                    minVoltageV = minVoltageV,
                    maxVoltageV = maxVoltageV,
                    startFuelPercent = startFuelPercent,
                    endFuelPercent = endFuelPercent,
                    fuelDeltaPercent = fuelDeltaPercent
                )
                CsvExporter.exportTransactionsToCsv(destTxCsv, metaForCsv, txEntities)
            }

            var parsedSampleCount = 0
            if (sampleCsv != null && sampleCsv.exists()) {
                sampleCsv.copyTo(destSampleCsv, overwrite = true)
                // Count lines in sample CSV
                try {
                    parsedSampleCount = maxOf(0, sampleCsv.readLines().size - 1)
                } catch (_: Exception) {}
            }

            if (rawLog != null && rawLog.exists()) {
                rawLog.copyTo(destRaw, overwrite = true)
            }

            // Ensure destination has a self-contained bundle.zip
            val filesForBundle = listOfNotNull(
                destJson.takeIf { it.exists() },
                destTxCsv.takeIf { it.exists() },
                destSampleCsv.takeIf { it.exists() },
                destRaw.takeIf { it.exists() }
            )
            if (filesForBundle.isNotEmpty()) {
                ZipExporter.createTripZip(destBundleZip, filesForBundle)
            }

            // Restore / Upsert into Room Database
            val maxRpm = txEntities.filter { it.pid.equals("0C", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
            val maxSpeed = txEntities.filter { it.pid.equals("0D", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
            val maxCoolant = txEntities.filter { it.pid.equals("05", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
            val voltList = txEntities.filter { it.pid.equals("42", ignoreCase = true) }.mapNotNull { it.decodedValue }
            val avgVolt = if (voltList.isNotEmpty()) voltList.average() else 0.0
            val detectedEcus = txEntities.mapNotNull { it.canRxId.takeIf { id -> id.isNotBlank() } }.distinct().joinToString(", ").ifBlank { "7E8" }

            if (startFuelPercent == null && txEntities.isNotEmpty()) {
                val lvlSamples = txEntities.filter { it.pid.equals("2F", ignoreCase = true) || it.pid.equals("012F", ignoreCase = true) }.mapNotNull { it.decodedValue }
                startFuelPercent = lvlSamples.firstOrNull()
                endFuelPercent = lvlSamples.lastOrNull()
                fuelDeltaPercent = if (startFuelPercent != null && endFuelPercent != null) endFuelPercent - startFuelPercent else null
            }

            // Keep the recorded time window instead of stamping every restored trip "now,
            // 60 seconds long" - duration feeds average speed, idle share and L/h trends.
            val startMillis = SessionTime.parseMillis(startTimeUtc)
            val endMillis = SessionTime.parseMillis(endTimeUtc) ?: startMillis
            val durationSec = SessionTime.durationSeconds(startTimeUtc, endTimeUtc)

            val tripEntity = TripEntity(
                id = sessionId,
                title = sessionName,
                vehicleName = vehicle,
                adapterName = adapter,
                protocolName = protocol,
                startTimeUtc = startTimeUtc,
                endTimeUtc = endTimeUtc ?: startTimeUtc,
                startTimestamp = startMillis ?: (System.currentTimeMillis() - 60000),
                endTimestamp = endMillis ?: System.currentTimeMillis(),
                durationSeconds = durationSec ?: 60L,
                status = "RESTORED",
                sampleCount = if (parsedSampleCount > 0) parsedSampleCount else txEntities.size,
                rawLogCount = txEntities.size,
                maxRpm = maxRpm,
                maxSpeedKmh = maxSpeed,
                maxCoolantC = maxCoolant,
                avgVoltageV = avgVolt,
                detectedEcus = detectedEcus,
                healthScore = 100,
                // Elevation window travels with the trip log; null stays null so the summary
                // shows the honest "-- m" instead of a made-up 0 m.
                maxAltitudeM = maxAltitudeM,
                minAltitudeM = minAltitudeM,
                minVoltageV = minVoltageV,
                maxVoltageV = maxVoltageV,
                startFuelPercent = startFuelPercent,
                endFuelPercent = endFuelPercent,
                fuelDeltaPercent = fuelDeltaPercent
            )
            tripRepository.insertTrip(tripEntity)

            // Insert telemetry samples into database if available
            if (txEntities.isNotEmpty()) {
                val dbSamples = txEntities.mapIndexed { idx, tx ->
                    TelemetrySampleEntity(
                        tripId = sessionId,
                        // A ZIP written by the live path carries uptime in timestampMonotonic, so the
                        // stamp decides the instant here too. See RecordingManager's insertSamples.
                        timestamp = com.example.data.RecordTime.instantOf(
                            tx.timestampMonotonic, tx.timestampUtc
                        ) ?: tx.timestampMonotonic,
                        timestampUtc = tx.timestampUtc,
                        ecuCanId = tx.canRxId.ifBlank { "7E8" },
                        pid = tx.pid,
                        parameterName = tx.decodedParameter.ifBlank { "PID ${tx.pid}" },
                        rawHex = tx.responseHex,
                        numericValue = tx.decodedValue,
                        displayValue = tx.decodedValueDisplay,
                        unit = tx.unit,
                        quality = "RESTORED",
                        sequence = idx.toLong()
                    )
                }
                tripRepository.insertSamples(dbSamples)
            }

            ZipImportResult(
                success = true,
                sessionId = sessionId,
                sessionName = sessionName,
                transactionCount = txEntities.size,
                sampleCount = if (parsedSampleCount > 0) parsedSampleCount else txEntities.size,
                message = "Successfully imported trip '$sessionName' ($sessionId) with ${txEntities.size} records."
            )
        } catch (e: Exception) {
            ZipImportResult(
                success = false,
                message = "Import failed: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    private fun parseTransactionsFromCsv(csvFile: File, outList: MutableList<TransactionRecord>) {
        try {
            val lines = csvFile.readLines()
            if (lines.size <= 1) return
            // Header is index 0:
            // session_id,timestamp_utc,timestamp_monotonic_ms,direction,can_id,request_hex,response_hex,service,pid,parameter,raw_payload,decoded_value,unit,status,error
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = splitCsvLine(line)
                if (parts.size >= 9) {
                    val tsUtc = parts.getOrNull(1).orEmpty()
                    val mono = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val dirStr = parts.getOrNull(3).orEmpty()
                    val canId = parts.getOrNull(4).orEmpty()
                    val reqHex = parts.getOrNull(5).orEmpty()
                    val respHex = parts.getOrNull(6).orEmpty()
                    val srv = parts.getOrNull(7).orEmpty()
                    val pid = parts.getOrNull(8).orEmpty()
                    val param = parts.getOrNull(9).orEmpty()
                    val rawPayload = parts.getOrNull(10).orEmpty()
                    val decValStr = parts.getOrNull(11).orEmpty()
                    val unit = parts.getOrNull(12).orEmpty()
                    val statStr = parts.getOrNull(13).orEmpty()
                    val err = parts.getOrNull(14)

                    val dir = try { com.example.model.Direction.valueOf(dirStr) } catch (_: Exception) { com.example.model.Direction.RX }
                    val stat = try { com.example.model.ResponseStatus.valueOf(statStr) } catch (_: Exception) { com.example.model.ResponseStatus.OK }

                    outList.add(
                        TransactionRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            timestampUtc = tsUtc,
                            timestampMonotonic = mono,
                            direction = dir,
                            elmCommand = "",
                            canTxId = if (dir == com.example.model.Direction.TX) canId else "",
                            canRxId = if (dir != com.example.model.Direction.TX) canId else "",
                            requestHex = reqHex,
                            responseHex = respHex,
                            service = srv,
                            pid = pid,
                            rawPayload = rawPayload,
                            decodedParameter = param,
                            decodedValue = decValStr.toDoubleOrNull(),
                            decodedValueDisplay = decValStr,
                            unit = unit,
                            responseStatus = stat,
                            errorMessage = err?.ifBlank { null }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            if (ch == '\"') {
                inQuotes = !inQuotes
            } else if (ch == ',' && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(ch)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    /**
     * Unzips content safely into destination folder, preventing Zip Slip path traversal.
     */
    private fun unzipSafely(context: Context, uri: Uri, destDir: File): List<File> {
        val extractedFiles = mutableListOf<File>()
        var totalBytesRead = 0L
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name.replace("\\", "/")
                    val simpleName = File(entryName).name
                    if (simpleName.isNotBlank() && !simpleName.startsWith(".")) {
                        val targetFile = File(destDir, simpleName)
                        // Zip slip verification
                        if (!targetFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            throw SecurityException("Zip Slip path traversal attempt detected in entry: ${entry.name}")
                        }

                        FileOutputStream(targetFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                totalBytesRead += len
                                if (totalBytesRead > MAX_ZIP_UNCOMPRESSED_BYTES) {
                                    throw IllegalStateException("Zip decompression size exceeded security threshold of 100MB.")
                                }
                                fos.write(buffer, 0, len)
                            }
                        }
                        extractedFiles.add(targetFile)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extractedFiles
    }
}
