package com.example.data

import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

object JsonExporter {

    fun exportToJson(
        file: File,
        metadata: RecordingMetadata,
        transactions: List<TransactionRecord>
    ) {
        val root = JSONObject()

        // Metadata block
        val metaObj = JSONObject().apply {
            put("sessionId", metadata.sessionId)
            put("sessionName", metadata.sessionName)
            put("vehicle", metadata.vehicle)
            put("vehicleId", metadata.vehicleId ?: JSONObject.NULL)  // FIX: Include vehicleId
            put("profile", metadata.profile)
            put("adapter", metadata.adapter)
            put("protocol", metadata.protocol)
            put("canBitrate", metadata.canBitrate)
            put("startTimeUtc", metadata.startTimeUtc)
            put("endTimeUtc", metadata.endTimeUtc ?: "")
            put("appVersion", metadata.appVersion)
            put("totalTransactions", transactions.size)
            // GPS altitude window of the trip (owner 2026-09-15). Written as an explicit
            // JSON null when it was never captured, so an import can tell "no GPS altitude"
            // apart from "0 m above sea level".
            put("maxAltitudeM", metadata.maxAltitudeM ?: JSONObject.NULL)
            put("minAltitudeM", metadata.minAltitudeM ?: JSONObject.NULL)
            // Battery voltage extremes (owner pipeline task 3): explicit JSON null when the
            // trip had no voltage samples - an import must tell "never measured" from 0 V.
            put("minVoltageV", metadata.minVoltageV ?: JSONObject.NULL)
            put("maxVoltageV", metadata.maxVoltageV ?: JSONObject.NULL)
        }
        root.put("sessionMetadata", metaObj)

        // Transactions array
        val txArray = JSONArray()
        for (tx in transactions) {
            val txObj = JSONObject().apply {
                put("id", tx.id)
                put("timestampUtc", tx.timestampUtc)
                put("timestampMonotonic", tx.timestampMonotonic)
                put("direction", tx.direction.name)
                put("elmCommand", tx.elmCommand)
                put("canTxId", tx.canTxId)
                put("canRxId", tx.canRxId)
                put("requestHex", tx.requestHex)
                put("responseHex", tx.responseHex)
                put("service", tx.service)
                put("pid", tx.pid)
                put("rawPayload", tx.rawPayload)
                put("decodedParameter", tx.decodedParameter)
                put("decodedValue", tx.decodedValue ?: JSONObject.NULL)
                put("decodedValueDisplay", tx.decodedValueDisplay)
                put("unit", tx.unit)
                put("decoderVersion", tx.decoderVersion)
                put("responseStatus", tx.responseStatus.name)
                put("errorMessage", tx.errorMessage ?: JSONObject.NULL)
            }
            txArray.put(txObj)
        }
        root.put("transactions", txArray)

        FileWriter(file).use { writer ->
            writer.write(root.toString(2))
        }
    }

    /**
     * Reads an optional measurement without coercing "absent", JSON null or NaN into 0.0.
     * The import path uses this for altitude: a fabricated 0 m would show up as a real
     * measurement in the trip summary (no-fake-values rule).
     */
    fun nullableDouble(obj: JSONObject, key: String): Double? =
        if (obj.has(key) && !obj.isNull(key)) {
            obj.optDouble(key).takeIf { !it.isNaN() }
        } else {
            null
        }
}
