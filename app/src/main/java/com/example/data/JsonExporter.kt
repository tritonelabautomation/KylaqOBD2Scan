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
            put("profile", metadata.profile)
            put("adapter", metadata.adapter)
            put("protocol", metadata.protocol)
            put("canBitrate", metadata.canBitrate)
            put("startTimeUtc", metadata.startTimeUtc)
            put("endTimeUtc", metadata.endTimeUtc ?: "")
            put("appVersion", metadata.appVersion)
            put("totalTransactions", transactions.size)
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
}
