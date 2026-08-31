package com.example.data

import com.example.model.RecordingMetadata
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import java.io.File
import java.io.FileWriter
import java.util.Locale

object CsvExporter {

    /**
     * Exports raw transaction records to CSV format.
     * Columns:
     * session_id, timestamp_utc, timestamp_monotonic_ms, direction, can_id, request_hex, response_hex,
     * service, pid, parameter, raw_payload, decoded_value, unit, status, error
     */
    fun exportTransactionsToCsv(
        file: File,
        metadata: RecordingMetadata,
        transactions: List<TransactionRecord>
    ) {
        FileWriter(file).use { writer ->
            // Header row
            writer.append("session_id,timestamp_utc,timestamp_monotonic_ms,direction,can_id,request_hex,response_hex,service,pid,parameter,raw_payload,decoded_value,unit,status,error\n")

            for (tx in transactions) {
                val canId = if (tx.direction == com.example.model.Direction.TX) tx.canTxId else tx.canRxId
                val decodedValStr = tx.decodedValue?.let { String.format(Locale.US, "%.3f", it) } ?: tx.decodedValueDisplay

                val row = listOf(
                    escapeCsv(metadata.sessionId),
                    escapeCsv(tx.timestampUtc),
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
                    escapeCsv(tx.errorMessage ?: "")
                ).joinToString(",")

                writer.append(row).append("\n")
            }
        }
    }

    /**
     * Exports synchronized telemetry samples to CSV format.
     * Columns:
     * timestamp_utc, RPM, speed_kmh, engine_load_pct, MAP_kPa, throttle_pct, accelerator_pct,
     * coolant_C, IAT_C, ambient_C, fuel_rate_L_h, engine_torque_pct, voltage_V, fuel_pressure_raw, boost_pressure_raw
     */
    fun exportSynchronizedSamplesToCsv(
        file: File,
        samples: List<SynchronizedSample>
    ) {
        FileWriter(file).use { writer ->
            // Header row
            writer.append("timestamp_utc,RPM,speed_kmh,engine_load_pct,MAP_kPa,throttle_pct,accelerator_pct,coolant_C,IAT_C,ambient_C,fuel_rate_L_h,engine_torque_pct,voltage_V,fuel_pressure_raw,boost_pressure_raw\n")

            for (s in samples) {
                val row = listOf(
                    escapeCsv(s.timestampUtc),
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
                    escapeCsv(s.boostPressureRaw ?: "")
                ).joinToString(",")

                writer.append(row).append("\n")
            }
        }
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
