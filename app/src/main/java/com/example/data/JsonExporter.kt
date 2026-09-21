package com.example.data

import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import org.json.JSONObject
import java.io.File

object JsonExporter {

    fun exportToJson(
        file: File,
        metadata: RecordingMetadata,
        transactions: List<TransactionRecord>
    ) {
        file.parentFile?.mkdirs()

        val metaObj = JSONObject().apply {
            put("sessionId", metadata.sessionId)
            put("sessionName", metadata.sessionName)
            put("vehicle", metadata.vehicle)
            put("vehicleId", metadata.vehicleId ?: JSONObject.NULL)
            put("profile", metadata.profile)
            put("adapter", metadata.adapter)
            put("protocol", metadata.protocol)
            put("canBitrate", metadata.canBitrate)
            put(
                "startTimeUtc",
                com.example.data.RecordTime.normalizeToIst(null, metadata.startTimeUtc)
                    ?: metadata.startTimeUtc
            )
            put(
                "endTimeUtc",
                metadata.endTimeUtc?.let {
                    com.example.data.RecordTime.normalizeToIst(null, it) ?: it
                } ?: ""
            )
            put("appVersion", metadata.appVersion)
            put("totalTransactions", transactions.size)
            put("maxAltitudeM", metadata.maxAltitudeM ?: JSONObject.NULL)
            put("minAltitudeM", metadata.minAltitudeM ?: JSONObject.NULL)
            put("minVoltageV", metadata.minVoltageV ?: JSONObject.NULL)
            put("maxVoltageV", metadata.maxVoltageV ?: JSONObject.NULL)
        }

        // STREAMING: previous built JSONArray of 59k objects + toString(2) = >100MB string → OOM at 256MB heap
        // Owner crash 2026-09-21 09:22 IST OOM + 17:45 IST ENOENT (file not created because OOM aborted).
        // Now write one tx at a time, peak = one JSONObject.
        try {
            file.bufferedWriter().use { writer ->
                writer.write("{\n")
                writer.write("  \"sessionMetadata\": ")
                writer.write(metaObj.toString(2))
                writer.write(",\n  \"transactions\": [\n")
                for (idx in transactions.indices) {
                    val tx = transactions[idx]
                    val txObj = JSONObject().apply {
                        put("id", tx.id)
                        put(
                            "timestampUtc",
                            com.example.data.RecordTime.normalizeToIst(tx.timestampMonotonic, tx.timestampUtc)
                                ?: tx.timestampUtc
                        )
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
                    writer.write("    ")
                    writer.write(txObj.toString())
                    if (idx < transactions.lastIndex) writer.write(",")
                    writer.write("\n")
                    if (idx % 5000 == 0) writer.flush()
                }
                writer.write("  ]\n}\n")
            }
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
    }

    fun nullableDouble(obj: JSONObject, key: String): Double? =
        if (obj.has(key) && !obj.isNull(key)) {
            obj.optDouble(key).takeIf { !it.isNaN() }
        } else null
}
