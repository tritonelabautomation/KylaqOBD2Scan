package com.example.analysis

import androidx.compose.ui.graphics.Color
import com.example.data.FuelLogCodec
import com.example.data.RefuelBunkRecord
import com.example.data.TripUserOverride
import java.util.Locale

/**
 * Fuel Brand & Batch Tagger for Trips.
 *
 * Automatically resolves which fuel batch (petrol bunk brand, grade, and additive)
 * was in the tank during every drive, with support for driver overrides and quick tagging:
 * - Nayara Energy (Teal / Emerald)
 * - IOCL / IndianOil (Saffron / Orange)
 * - Jio-bp (Electric Blue / Green)
 * - Shell (Red / Yellow)
 * - BPCL / Bharat Petroleum (Royal Blue / Yellow)
 * - HPCL / Hindustan Petroleum (Deep Blue / Red)
 */
object FuelBrandTagger {

    enum class FuelBrand(
        val displayName: String,
        val primaryColorHex: Long,
        val secondaryColorHex: Long,
        val defaultGrade: String
    ) {
        NAYARA("Nayara", 0xFF00897B, 0xFF00E676, "X95"),
        IOCL("IOCL", 0xFFE65100, 0xFFFF9800, "XP95"),
        JIO_BP("Jio-bp", 0xFF0078FF, 0xFF00C853, "Active Petrol"),
        SHELL("Shell", 0xFFD50000, 0xFFFFD600, "V-Power"),
        BPCL("BPCL", 0xFF1565C0, 0xFFFFD600, "Speed 97"),
        HPCL("HPCL", 0xFF0D47A1, 0xFFD32F2F, "Power 95"),
        GENERIC("Petrol", 0xFF00E5FF, 0xFF30D158, "Regular");

        val primaryColor: Color get() = Color(primaryColorHex)
        val secondaryColor: Color get() = Color(secondaryColorHex)
    }

    data class FuelTagInfo(
        val stationName: String,
        val brand: FuelBrand,
        val grade: String,
        val additiveName: String? = null,
        val additiveDosageMl: Double? = null,
        val pricePerL: Double = 108.5,
        val isManualOverride: Boolean = false
    ) {
        val hasAdditive: Boolean
            get() = !additiveName.isNullOrBlank() && !additiveName.equals("None", ignoreCase = true)

        val shortBrandName: String
            get() = brand.displayName

        /** Formatted badge label: "Nayara X95 + mileX", "IOCL XP95", "Jio-bp Petrol" */
        val displayBadge: String
            get() = buildString {
                append(shortBrandName)
                if (grade.isNotBlank() && !grade.equals(FuelLogCodec.GRADE_UNKNOWN, ignoreCase = true)) {
                    append(" ").append(grade)
                }
                if (hasAdditive && !additiveName.isNullOrBlank()) {
                    val addShort = when {
                        additiveName.contains("mileX", ignoreCase = true) -> "mileX"
                        additiveName.contains("Liqui", ignoreCase = true) -> "LiquiMoly"
                        else -> additiveName.take(8)
                    }
                    append(" + ").append(addShort)
                }
            }

        val fullStationAndGrade: String
            get() = buildString {
                append(stationName)
                if (grade.isNotBlank()) append(" (").append(grade).append(")")
                if (hasAdditive && !additiveName.isNullOrBlank()) {
                    append(" + ").append(additiveName)
                    additiveDosageMl?.let { append(" [").append(String.format(Locale.US, "%.0fml", it)).append("]") }
                }
            }
    }

    /**
     * Identifies the brand enum from any raw station name string.
     */
    fun detectBrand(stationOrBrand: String?): FuelBrand {
        if (stationOrBrand.isNullOrBlank()) return FuelBrand.GENERIC
        val s = stationOrBrand.uppercase(Locale.ROOT)
        return when {
            s.contains("NAYARA") || s.contains("ESSAR") -> FuelBrand.NAYARA
            s.contains("IOCL") || s.contains("INDIANOIL") || s.contains("INDIAN OIL") || s.contains("XP95") || s.contains("XP100") -> FuelBrand.IOCL
            s.contains("JIO") || s.contains("BP") && s.contains("JIO") || s.contains("RELIANCE") -> FuelBrand.JIO_BP
            s.contains("SHELL") || s.contains("V-POWER") -> FuelBrand.SHELL
            s.contains("BPCL") || s.contains("BHARAT") || s.contains("SPEED 97") -> FuelBrand.BPCL
            s.contains("HPCL") || s.contains("HINDUSTAN") || s.contains("POWER 95") || s.contains("HP ") -> FuelBrand.HPCL
            else -> FuelBrand.GENERIC
        }
    }

    /**
     * Resolves the fuel batch tag for a trip given its start time, optional driver override,
     * historical fuel logs, and detailed bunk records.
     */
    fun resolveFuelTag(
        tripStartMs: Long,
        userOverride: TripUserOverride? = null,
        fuelLogs: List<FuelLogCodec.FuelEntry> = emptyList(),
        bunkRecords: List<RefuelBunkRecord> = emptyList()
    ): FuelTagInfo {
        // 1. Check if driver manually tagged/overrode the fuel on this trip
        if (userOverride != null && (!userOverride.fuelStation.isNullOrBlank() || !userOverride.fuelBrand.isNullOrBlank())) {
            val station = userOverride.fuelStation ?: userOverride.fuelBrand ?: "Petrol Pump"
            val brand = detectBrand(station)
            val grade = userOverride.fuelGrade ?: brand.defaultGrade
            val additive = userOverride.fuelAdditive
            return FuelTagInfo(
                stationName = station,
                brand = brand,
                grade = grade,
                additiveName = additive,
                isManualOverride = true
            )
        }

        // 2. Automatically map to the latest active refuel before or at the trip start
        val sortedFills = fuelLogs.sortedByDescending { it.idMs }
        val activeFill = sortedFills.firstOrNull { it.idMs <= tripStartMs + 3600_000L } ?: sortedFills.firstOrNull()
        val activeBunk = bunkRecords.firstOrNull { it.idMs == activeFill?.idMs }

        if (activeFill != null) {
            val station = activeFill.station.ifBlank { activeBunk?.stationName ?: "Nayara Energy" }
            val brand = detectBrand(station)
            val grade = if (activeFill.grade.isNotBlank() && activeFill.grade != FuelLogCodec.GRADE_UNKNOWN) {
                activeFill.grade
            } else {
                activeBunk?.fuelGrade ?: brand.defaultGrade
            }
            val additive = activeFill.additive ?: activeBunk?.additiveName
            val dosage = activeFill.additiveDosageMl ?: activeBunk?.additiveDosageMl

            return FuelTagInfo(
                stationName = station,
                brand = brand,
                grade = grade,
                additiveName = additive,
                additiveDosageMl = dosage,
                pricePerL = activeFill.pricePerL,
                isManualOverride = false
            )
        }

        // 3. Fallback default to Nayara Energy X95
        return FuelTagInfo(
            stationName = "Nayara Energy",
            brand = FuelBrand.NAYARA,
            grade = "X95",
            additiveName = "Dorf Ketal mileX",
            additiveDosageMl = 5.0,
            pricePerL = 108.5,
            isManualOverride = false
        )
    }

    /** List of popular fuel presets for quick 1-tap tagging */
    val PRESET_FUEL_OPTIONS = listOf(
        FuelTagInfo("Nayara Energy", FuelBrand.NAYARA, "X95", "Dorf Ketal mileX", 5.0, 108.5),
        FuelTagInfo("IOCL", FuelBrand.IOCL, "XP95", null, null, 108.5),
        FuelTagInfo("Jio-bp", FuelBrand.JIO_BP, "Active Petrol", null, null, 102.5),
        FuelTagInfo("Shell", FuelBrand.SHELL, "V-Power", null, null, 115.0),
        FuelTagInfo("BPCL", FuelBrand.BPCL, "Speed 97", null, null, 112.0),
        FuelTagInfo("HPCL", FuelBrand.HPCL, "Power 95", null, null, 108.5)
    )
}
