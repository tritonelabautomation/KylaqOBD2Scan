package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Detailed Refuel Event with Petrol Bunk, Dispenser Nozzle & Auto-Cut Off Tracking.
 */
data class RefuelBunkRecord(
    val idMs: Long = System.currentTimeMillis(),
    val timestampUtc: String = RecordTime.stamp(idMs),
    val stationName: String,
    val nozzleId: String = "Nozzle #1",
    val fuelGrade: String = "X95",
    val autoCutPercent: Double, // PID 012F % at auto-cut off
    val startLevelPercent: Double, // PID 012F % before refuel started
    val pumpLitres: Double, // Ground truth from dispenser / receipt OCR
    val floatDeltaLitres: Double, // Calculated from PID 012F float delta * 50.0L
    val pricePerL: Double = 0.0,
    val totalCost: Double = 0.0,
    val odometerKm: Double? = null,
    val midSinceRefuelKm: Double? = null,
    val midRangeKm: Double? = null,
    val midEconomyKmL: Double? = null,
    val receiptPhotoUri: String? = null,
    val midPhotoUri: String? = null,
    val note: String = ""
) {
    /** Float Sensor Error vs Ground Truth Pump Liters */
    val floatErrorPct: Double
        get() = if (pumpLitres > 0.05) ((pumpLitres - floatDeltaLitres) / pumpLitres) * 100.0 else 0.0

    /** Effective Calibrated Tank Capacity derived from this refuel fill */
    val calibratedTankCapacityL: Double
        get() {
            val deltaPct = autoCutPercent - startLevelPercent
            return if (deltaPct > 5.0 && pumpLitres > 0.5) (pumpLitres / (deltaPct / 100.0)) else 50.0
        }
}

data class BunkNozzleStat(
    val stationName: String,
    val nozzleId: String,
    val fillCount: Int,
    val avgAutoCutPercent: Double,
    val minAutoCutPercent: Double,
    val maxAutoCutPercent: Double,
    val avgFloatErrorPct: Double,
    val avgCalibratedTankL: Double,
    val lastFillDate: String
)

/**
 * Persistence store & Analytics engine for Petrol Bunks & Dispenser Nozzles.
 */
object BunkNozzleStore {

    private const val PREFS_NAME = "bunk_nozzle_store"
    private const val KEY_RECORDS = "refuel_bunk_records"

    fun save(context: Context, record: RefuelBunkRecord) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getAll(context).filterNot { it.idMs == record.idMs }.toMutableList()
        current.add(0, record)
        val jsonArray = JSONArray()
        current.take(300).forEach { jsonArray.put(encodeToJson(it)) }
        prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
    }

    fun getAll(context: Context): List<RefuelBunkRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<RefuelBunkRecord>()
            for (i in 0 until array.length()) {
                decodeFromJson(array.getJSONObject(i))?.let { list.add(it) }
            }
            list.sortedByDescending { it.idMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun delete(context: Context, idMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = getAll(context).filterNot { it.idMs == idMs }
        val jsonArray = JSONArray()
        updated.forEach { jsonArray.put(encodeToJson(it)) }
        prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
    }

    /**
     * Aggregates stats per Bunk & Nozzle to evaluate auto-cut off variations.
     */
    fun statsPerBunkAndNozzle(context: Context): List<BunkNozzleStat> {
        val records = getAll(context)
        if (records.isEmpty()) return emptyList()

        return records.groupBy { "${it.stationName.trim()} | ${it.nozzleId.trim()}" }
            .map { (_, group) ->
                val first = group.first()
                val cuts = group.map { it.autoCutPercent }
                val errors = group.map { it.floatErrorPct }
                val caps = group.map { it.calibratedTankCapacityL }

                BunkNozzleStat(
                    stationName = first.stationName,
                    nozzleId = first.nozzleId,
                    fillCount = group.size,
                    avgAutoCutPercent = cuts.average(),
                    minAutoCutPercent = cuts.minOrNull() ?: first.autoCutPercent,
                    maxAutoCutPercent = cuts.maxOrNull() ?: first.autoCutPercent,
                    avgFloatErrorPct = errors.average(),
                    avgCalibratedTankL = caps.average(),
                    lastFillDate = first.timestampUtc.take(10)
                )
            }.sortedByDescending { it.fillCount }
    }

    private fun encodeToJson(r: RefuelBunkRecord): JSONObject {
        val json = JSONObject()
        json.put("idMs", r.idMs)
        json.put("timestampUtc", r.timestampUtc)
        json.put("stationName", r.stationName)
        json.put("nozzleId", r.nozzleId)
        json.put("fuelGrade", r.fuelGrade)
        json.put("autoCutPercent", r.autoCutPercent)
        json.put("startLevelPercent", r.startLevelPercent)
        json.put("pumpLitres", r.pumpLitres)
        json.put("floatDeltaLitres", r.floatDeltaLitres)
        json.put("pricePerL", r.pricePerL)
        json.put("totalCost", r.totalCost)
        r.odometerKm?.let { json.put("odometerKm", it) }
        r.midSinceRefuelKm?.let { json.put("midSinceRefuelKm", it) }
        r.midRangeKm?.let { json.put("midRangeKm", it) }
        r.midEconomyKmL?.let { json.put("midEconomyKmL", it) }
        r.receiptPhotoUri?.let { json.put("receiptPhotoUri", it) }
        r.midPhotoUri?.let { json.put("midPhotoUri", it) }
        json.put("note", r.note)
        return json
    }

    private fun decodeFromJson(json: JSONObject): RefuelBunkRecord? {
        return try {
            RefuelBunkRecord(
                idMs = json.optLong("idMs", System.currentTimeMillis()),
                timestampUtc = json.optString("timestampUtc", ""),
                stationName = json.optString("stationName", "Petrol Pump"),
                nozzleId = json.optString("nozzleId", "Nozzle #1"),
                fuelGrade = json.optString("fuelGrade", "X95"),
                autoCutPercent = json.optDouble("autoCutPercent", 100.0),
                startLevelPercent = json.optDouble("startLevelPercent", 0.0),
                pumpLitres = json.optDouble("pumpLitres", 0.0),
                floatDeltaLitres = json.optDouble("floatDeltaLitres", 0.0),
                pricePerL = json.optDouble("pricePerL", 0.0),
                totalCost = json.optDouble("totalCost", 0.0),
                odometerKm = json.optDouble("odometerKm", Double.NaN).takeIf { !it.isNaN() },
                midSinceRefuelKm = json.optDouble("midSinceRefuelKm", Double.NaN).takeIf { !it.isNaN() },
                midRangeKm = json.optDouble("midRangeKm", Double.NaN).takeIf { !it.isNaN() },
                midEconomyKmL = json.optDouble("midEconomyKmL", Double.NaN).takeIf { !it.isNaN() },
                receiptPhotoUri = json.optString("receiptPhotoUri", null)?.takeIf { it.isNotBlank() && it != "null" },
                midPhotoUri = json.optString("midPhotoUri", null)?.takeIf { it.isNotBlank() && it != "null" },
                note = json.optString("note", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
