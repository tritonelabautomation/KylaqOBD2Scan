package com.example.data

import android.content.Context
import org.json.JSONObject

/**
 * Persistence store for user-specified manual overrides for a trip:
 * 1. Transmission Drive Mode: "SPORT (S)" vs "DRIVE (D)" vs "ECO (D)" vs null (Auto).
 * 2. AC / Ventilation State: "OFF" vs "ON" vs null (Auto).
 * 3. Window State: true if window was rolled down.
 * 4. Driver Notes / Context.
 *
 * Saves data per tripId in SharedPreferences with JSON serialization so inputs survive
 * app restarts, updates, process recreation, and backups.
 */
data class TripUserOverride(
    val tripId: String,
    /** "SPORT (S)", "DRIVE (D)", "ECO (D)", or null for Auto-detected */
    val driveMode: String? = null,
    /** "OFF", "ON", or null for Auto-detected */
    val acState: String? = null,
    /** True if driver drove with window rolled down for natural ventilation */
    val windowRolledDown: Boolean = false,
    val note: String? = null,
    val fuelBrand: String? = null,
    val fuelStation: String? = null,
    val fuelGrade: String? = null,
    val fuelAdditive: String? = null
)

object TripUserOverrideStore {

    private const val PREFS_NAME = "trip_user_overrides"
    private const val KEY_PREFIX = "override_"

    fun get(context: Context, tripId: String): TripUserOverride {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_PREFIX$tripId", null) ?: return TripUserOverride(tripId)
        return decode(tripId, raw)
    }

    fun save(context: Context, override: TripUserOverride) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_PREFIX${override.tripId}", encode(override)).apply()
    }

    fun remove(context: Context, tripId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$KEY_PREFIX$tripId").apply()
    }

    fun encode(override: TripUserOverride): String {
        val json = JSONObject()
        override.driveMode?.let { json.put("driveMode", it) }
        override.acState?.let { json.put("acState", it) }
        json.put("windowRolledDown", override.windowRolledDown)
        override.note?.let { json.put("note", it) }
        override.fuelBrand?.let { json.put("fuelBrand", it) }
        override.fuelStation?.let { json.put("fuelStation", it) }
        override.fuelGrade?.let { json.put("fuelGrade", it) }
        override.fuelAdditive?.let { json.put("fuelAdditive", it) }
        return json.toString()
    }

    fun decode(tripId: String, raw: String): TripUserOverride {
        return try {
            val json = JSONObject(raw)
            TripUserOverride(
                tripId = tripId,
                driveMode = json.optString("driveMode", null)?.takeIf { it.isNotBlank() && it != "null" },
                acState = json.optString("acState", null)?.takeIf { it.isNotBlank() && it != "null" },
                windowRolledDown = json.optBoolean("windowRolledDown", false),
                note = json.optString("note", null)?.takeIf { it.isNotBlank() && it != "null" },
                fuelBrand = json.optString("fuelBrand", null)?.takeIf { it.isNotBlank() && it != "null" },
                fuelStation = json.optString("fuelStation", null)?.takeIf { it.isNotBlank() && it != "null" },
                fuelGrade = json.optString("fuelGrade", null)?.takeIf { it.isNotBlank() && it != "null" },
                fuelAdditive = json.optString("fuelAdditive", null)?.takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            TripUserOverride(tripId)
        }
    }
}
