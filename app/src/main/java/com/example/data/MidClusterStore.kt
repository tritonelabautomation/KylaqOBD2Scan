package com.example.data

import android.content.Context
import com.example.analysis.MidClusterData
import org.json.JSONObject

/**
 * Persistence store for user-attached MID cluster photos and OCR telemetry.
 *
 * Saves extracted / verified MID data per tripId into SharedPreferences so they survive
 * app restarts, updates, and Drive backups.
 */
object MidClusterStore {

    private const val PREFS_NAME = "mid_cluster_store"
    private const val KEY_PREFIX = "mid_"

    fun save(context: Context, data: MidClusterData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = encodeToJson(data)
        prefs.edit().putString("$KEY_PREFIX${data.tripId}", json).apply()
    }

    fun get(context: Context, tripId: String): MidClusterData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_PREFIX$tripId", null) ?: return null
        return decodeFromJson(raw)
    }

    fun remove(context: Context, tripId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$KEY_PREFIX$tripId").apply()
    }

    fun encodeToJson(data: MidClusterData): String {
        val json = JSONObject()
        json.put("tripId", data.tripId)
        json.put("timestampUtc", data.timestampUtc)
        json.put("durationMinutes", data.durationMinutes)
        json.put("durationText", data.durationText)
        json.put("distanceKm", data.distanceKm)
        json.put("avgFuelEconomyKmL", data.avgFuelEconomyKmL)
        json.put("avgSpeedKmh", data.avgSpeedKmh)
        data.totalOdometerKm?.let { json.put("totalOdometerKm", it) }
        data.rangeKm?.let { json.put("rangeKm", it) }
        data.ambientTempC?.let { json.put("ambientTempC", it) }
        json.put("mode", data.mode)
        data.timeOfDay?.let { json.put("timeOfDay", it) }
        data.photoUri?.let { json.put("photoUri", it) }
        return json.toString()
    }

    fun decodeFromJson(jsonString: String): MidClusterData? {
        return try {
            val json = JSONObject(jsonString)
            MidClusterData(
                tripId = json.optString("tripId", ""),
                timestampUtc = json.optString("timestampUtc", ""),
                durationMinutes = json.optInt("durationMinutes", 0),
                durationText = json.optString("durationText", "0:00 h"),
                distanceKm = json.optDouble("distanceKm", 0.0),
                avgFuelEconomyKmL = json.optDouble("avgFuelEconomyKmL", 0.0),
                avgSpeedKmh = json.optDouble("avgSpeedKmh", 0.0),
                totalOdometerKm = json.optDouble("totalOdometerKm", Double.NaN).takeIf { !it.isNaN() },
                rangeKm = json.optDouble("rangeKm", Double.NaN).takeIf { !it.isNaN() },
                ambientTempC = json.optDouble("ambientTempC", Double.NaN).takeIf { !it.isNaN() },
                mode = json.optString("mode", "Since start"),
                timeOfDay = json.optString("timeOfDay", null)?.takeIf { it.isNotBlank() && it != "null" },
                photoUri = json.optString("photoUri", null)?.takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            null
        }
    }
}
