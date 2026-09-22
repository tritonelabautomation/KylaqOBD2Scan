package com.example.data

import com.example.model.RecordingMetadata
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import java.io.File
import java.io.FileWriter
import java.util.Locale

object CsvExporter {

    /**
     * Column headers, exposed as constants because [SessionJournal] writes the SAME format
     * live while the drive is still happening (owner 2026-09-17: "it never ever loose the
     * logs"). Two copies of a header string is how a journal file ends up unreadable by the
     * importer that reads the finalized one; there is now exactly one.
     */
    /**
     * `altitude_m` appended last (owner 2026-09-22: "Altitude not logging still").
     *
     * The stamp existed on [com.example.model.TransactionRecord] and reached Room, but it was
     * never written HERE - and this header is the crash journal's schema too. Every path that
     * rebuilds a trip from its journal (`stopRecording` when the journal is longer than RAM,
     * `recoverJournalSession`, and `mergeSessions`, which reads each fragment's transactions
     * CSV) therefore got rows back with `altitudeM = null`, re-persisted them that way, and the
     * trip's per-row elevation - the Trends altitude channel and the trip min/max - came out
     * blank even though the drive had recorded fixes. A column at the END keeps every existing
     * file readable: old rows have no 16th cell and read back as null, exactly as before.
     */
    const val TRANSACTIONS_HEADER =
        "session_id,timestamp_utc,timestamp_monotonic_ms,direction,can_id,request_hex," +
            "response_hex,service,pid,parameter,raw_payload,decoded_value,unit,status,error,altitude_m"

    /**
     * altitude_m appended last (owner 2026-09-15) so existing column order stays stable;
     * `fuel_level_pct` appended after it (owner 2026-09-22: "Fuel percentage at the start of
     * trip & end of trip is also not available on trip logs") for the same reason.
     */
    const val SAMPLES_HEADER =
        "timestamp_utc,RPM,speed_kmh,engine_load_pct,MAP_kPa,throttle_pct,accelerator_pct," +
            "coolant_C,IAT_C,ambient_C,fuel_rate_L_h,engine_torque_pct,voltage_V," +
            "fuel_pressure_raw,boost_pressure_raw,altitude_m,fuel_level_pct"

    /**
     * One transaction row, WITHOUT the trailing newline. Used by both the batch export and the
     * live journal, so a row written mid-drive and a row written at STOP are byte-identical.
     */
    fun transactionRow(sessionId: String, tx: TransactionRecord): String {
        val canId = if (tx.direction == com.example.model.Direction.TX) tx.canTxId else tx.canRxId
        val decodedValStr = tx.decodedValue?.let { String.format(Locale.US, "%.3f", it) }
            ?: tx.decodedValueDisplay
        return listOf(
            escapeCsv(sessionId),
            // IST on the way out - see RecordTime.normalizeToIst.
            escapeCsv(RecordTime.normalizeToIst(tx.timestampMonotonic, tx.timestampUtc) ?: tx.timestampUtc),
            tx.timestampMonotonic.toString(),
            escapeCsv(tx.direction.name),
            escapeCsv(canId),
            escapeCsv(tx.requestHex),
            escapeCsv(tx.responseHex),
            escapeCsv(tx.service),
            escapeCsv(tx.pid),
            escapeCsv(tx.decodedParameter),
            escapeCsv(tx.rawPayload),
            escapeCsv(decodedValStr),
            escapeCsv(tx.unit),
            escapeCsv(tx.responseStatus.name),
            escapeCsv(tx.errorMessage ?: ""),
            // Last column, so a file written before it exists still reads back (see
            // TRANSACTIONS_HEADER). Blank = no accuracy-gated GPS fix carried altitude at this
            // instant; never 0.0, which would read as sea level.
            tx.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        ).joinToString(",")
    }

    /** One synchronized-sample row, WITHOUT the trailing newline. See [transactionRow]. */
    fun sampleRow(s: SynchronizedSample): String = listOf(
        // IST on the way out - see RecordTime.normalizeToIst.
        escapeCsv(RecordTime.normalizeToIst(s.timestampMonotonic, s.timestampUtc) ?: s.timestampUtc),
        s.rpm?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.engineLoadPct?.let { String.format(Locale.US, "%.2f", it) } ?: "",
        s.mapKpa?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.throttlePct?.let { String.format(Locale.US, "%.2f", it) } ?: "",
        s.acceleratorPct?.let { String.format(Locale.US, "%.2f", it) } ?: "",
        s.coolantC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.iatC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.ambientC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.fuelRateLh?.let { String.format(Locale.US, "%.3f", it) } ?: "",
        s.engineTorquePct?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        s.voltageV?.let { String.format(Locale.US, "%.3f", it) } ?: "",
        escapeCsv(s.fuelPressureRaw ?: ""),
        escapeCsv(s.boostPressureRaw ?: ""),
        s.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
        // Tank level (PID 012F) at this sample: what makes the trip's start/end fuel percentage
        // reconstructible from the log files alone, not only from the summary (owner 2026-09-22).
        s.fuelLevelPct?.let { String.format(Locale.US, "%.1f", it) } ?: ""
    ).joinToString(",")

    /**
     * Exports raw transaction records to CSV format.
     * Columns: [TRANSACTIONS_HEADER] - session_id, timestamp_utc, timestamp_monotonic_ms,
     * direction, can_id, request_hex, response_hex, service, pid, parameter, raw_payload,
     * decoded_value, unit, status, error, altitude_m
     */
    fun exportTransactionsToCsv(
        file: File,
        metadata: RecordingMetadata,
        transactions: List<TransactionRecord>
    ) {
        FileWriter(file).use { writer ->
            // Header row
            writer.append(TRANSACTIONS_HEADER).append("\n")
            for (tx in transactions) {
                writer.append(transactionRow(metadata.sessionId, tx)).append("\n")
            }
        }
    }

    /**
     * Exports synchronized telemetry samples to CSV format.
     * Columns: [SAMPLES_HEADER] - timestamp_utc, RPM, speed_kmh, engine_load_pct, MAP_kPa,
     * throttle_pct, accelerator_pct, coolant_C, IAT_C, ambient_C, fuel_rate_L_h,
     * engine_torque_pct, voltage_V, fuel_pressure_raw, boost_pressure_raw, altitude_m,
     * fuel_level_pct
     */
    fun exportSynchronizedSamplesToCsv(
        file: File,
        samples: List<SynchronizedSample>
    ) {
        FileWriter(file).use { writer ->
            // Header row
            // altitude_m appended last (owner 2026-09-15): existing column order stays stable
            // for anything that already reads these files, and a blank cell means "no GPS
            // altitude for this sample" rather than 0 m.
            writer.append(SAMPLES_HEADER).append("\n")
            for (sample in samples) {
                writer.append(sampleRow(sample)).append("\n")
            }
        }
    }

    // ------------------------------------------------------------------
    // READERS
    //
    // Recovery needs to turn a persisted CSV back into records. `ZipImporter` already had a
    // private transactions parser for imported bundles; the crash journal (owner 2026-09-17:
    // "it never ever loose the logs") writes the SAME schema live, so both readers live here
    // next to the writers that define the format. A row this file can write, this file can read.
    // ------------------------------------------------------------------

    /**
     * Reads a transactions CSV - either a finalized export or a live journal file - back into
     * [TransactionRecord]s. Rows that are short, blank or corrupt are skipped, never fatal: a
     * journal cut off mid-write by a process kill has a truncated LAST line by definition, and
     * losing that one row must not cost the rest of the drive.
     */
    fun readTransactionsFromCsv(file: File): List<TransactionRecord> {
        val out = mutableListOf<TransactionRecord>()
        val lines = runCatching { file.readLines() }.getOrNull() ?: return out
        if (lines.size <= 1) return out
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val p = splitCsvLine(line)
            if (p.size < 9) continue
            val dirStr = p.getOrNull(3).orEmpty()
            val canId = p.getOrNull(4).orEmpty()
            val direction = runCatching { com.example.model.Direction.valueOf(dirStr) }
                .getOrDefault(com.example.model.Direction.RX)
            val status = runCatching { com.example.model.ResponseStatus.valueOf(p.getOrNull(13).orEmpty()) }
                .getOrDefault(com.example.model.ResponseStatus.OK)
            val stamp = p.getOrNull(1).orEmpty()
            val mono = p.getOrNull(2)?.toLongOrNull() ?: (RecordTime.parseMillis(stamp) ?: 0L)
            val valueStr = p.getOrNull(11).orEmpty()
            out.add(
                TransactionRecord(
                    timestampUtc = stamp,
                    timestampMonotonic = mono,
                    direction = direction,
                    canTxId = if (direction == com.example.model.Direction.TX) canId else "",
                    canRxId = if (direction != com.example.model.Direction.TX) canId else "",
                    requestHex = p.getOrNull(5).orEmpty(),
                    responseHex = p.getOrNull(6).orEmpty(),
                    service = p.getOrNull(7).orEmpty(),
                    pid = p.getOrNull(8).orEmpty(),
                    rawPayload = p.getOrNull(10).orEmpty(),
                    decodedParameter = p.getOrNull(9).orEmpty(),
                    decodedValue = valueStr.toDoubleOrNull(),
                    decodedValueDisplay = valueStr,
                    unit = p.getOrNull(12).orEmpty(),
                    responseStatus = status,
                    errorMessage = p.getOrNull(14)?.ifBlank { null },
                    // Column 16, added 2026-09-22: the GPS altitude this row was stamped with.
                    // A file written before the column existed simply has no 16th cell and reads
                    // back null - the honest blank it always was, never 0.0.
                    altitudeM = p.getOrNull(15)?.toDoubleOrNull()
                )
            )
        }
        return out
    }

    /**
     * Reads a samples CSV - finalized export or live journal - back into [SynchronizedSample]s.
     *
     * The samples schema has no monotonic column, so the row's own stamp is parsed for the
     * ordering key. That is exact for IST stamps (they carry their offset) and for legacy `...Z`
     * stamps ([RecordTime.parseMillis] honours both), so a recovered trip keeps its true timeline.
     */
    fun readSamplesFromCsv(file: File): List<SynchronizedSample> {
        val out = mutableListOf<SynchronizedSample>()
        val lines = runCatching { file.readLines() }.getOrNull() ?: return out
        if (lines.size <= 1) return out
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val p = splitCsvLine(line)
            if (p.size < 15) continue
            val stamp = p[0]
            out.add(
                SynchronizedSample(
                    timestampUtc = stamp,
                    timestampMonotonic = RecordTime.parseMillis(stamp) ?: 0L,
                    rpm = p[1].toDoubleOrNull(),
                    speedKmh = p[2].toDoubleOrNull(),
                    engineLoadPct = p[3].toDoubleOrNull(),
                    mapKpa = p[4].toDoubleOrNull(),
                    throttlePct = p[5].toDoubleOrNull(),
                    acceleratorPct = p[6].toDoubleOrNull(),
                    coolantC = p[7].toDoubleOrNull(),
                    iatC = p[8].toDoubleOrNull(),
                    ambientC = p[9].toDoubleOrNull(),
                    fuelRateLh = p[10].toDoubleOrNull(),
                    engineTorquePct = p[11].toDoubleOrNull(),
                    voltageV = p[12].toDoubleOrNull(),
                    fuelPressureRaw = p[13].ifBlank { null },
                    boostPressureRaw = p[14].ifBlank { null },
                    altitudeM = p.getOrNull(15)?.toDoubleOrNull(),
                    // Column 17, added 2026-09-22 (owner: "Fuel percentage at the start of trip &
                    // end of trip is also not available on trip logs"). Absent in every older file,
                    // so it reads back null and the trip reports an honest blank.
                    fuelLevelPct = p.getOrNull(16)?.toDoubleOrNull()
                )
            )
        }
        return out
    }

    /** RFC-4180-ish split: commas inside quotes stay inside their cell. */
    private fun splitCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { tokens.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
