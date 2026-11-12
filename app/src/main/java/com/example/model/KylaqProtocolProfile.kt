package com.example.model

import android.util.Log

/**
 * Authoritative protocol profile for the Skoda Kylaq 1.0 TSI (EA211).
 *
 * Full VW Group MQB-A0 multi-ECU physical addressing matrix covering Powertrain,
 * Transmission, Chassis/ABS, Power Steering (EPS), Climatronic, CAN Gateway,
 * Central Electrics (BCM), Airbag, and Instrument Cluster.
 */
object KylaqProtocolProfile {
    private const val TAG = "KylaqProtocolProfile"

    const val VEHICLE_NAME = "Skoda Kylaq"
    const val ENGINE_NAME = "1.0 TSI (EA211)"
    const val PROTOCOL_NAME = "ISO 15765-4 CAN"
    const val CAN_ID_TYPE = "11-bit"
    const val BITRATE_BAUD = 500_000
    const val BITRATE_DISPLAY = "500 kbit/s"
    const val ELM_PROTOCOL_COMMAND = "ATSP6"

    val DEFAULT_CAN_PROTOCOL = CanProtocol.ISO_15765_11B_500K

    const val FUNCTIONAL_REQUEST_ID = "7DF"
    val PHYSICAL_REQUEST_RANGE = listOf("7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7")
    val TYPICAL_RESPONSE_RANGE = listOf("7E8", "7E9", "7EA", "7EB", "7EC", "7ED", "7EE", "7EF")

    const val ISO_TP_ENABLED = true
    const val NORMAL_ADDRESSING = true

    /**
     * Standard ECU mapping for VW Group MQB platform.
     * Maps RX CAN response IDs to corresponding TX CAN request headers.
     */
    val STANDARD_ECU_MAPPING = mapOf(
        "7E8" to "7E0", // Module 01: Engine ECM (Bosch MED17.1.27)
        "7E9" to "7E1", // Module 02: Transmission TCU (Aisin AQ250 6-AT)
        "7EA" to "7E2",
        "7EB" to "7E3",
        "7EC" to "7E4",
        "7ED" to "7E5",
        "7EE" to "7E6",
        "7EF" to "7E7",
        "77D" to "713", // Module 03: ABS / ESP / Brakes (J104)
        "77E" to "714", // Module 44: Steering Assist / EPS (J500)
        "77B" to "711", // Module 08: Air Conditioning / Climatronic (J255)
        "77A" to "710", // Module 19: CAN Gateway (J533)
        "772" to "708", // Module 09: Central Electrics / BCM (J519)
        "77F" to "715", // Module 15: Airbag / Module 17: Instruments (J285)
        "780" to "716", // Module 76: Park Assist (J446)
        "773" to "740"  // Module 5F: Information Electronics / MIB3
    )

    /**
     * Known physical TX CAN headers across all MQB modules.
     */
    val ALL_PHYSICAL_TX_HEADERS = setOf(
        "7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7",
        "713", "714", "711", "710", "708", "715", "716", "740"
    )

    private val dynamicEcuMapping = mutableMapOf<String, String>()

    val DEFAULT_INIT_SEQUENCE = listOf(
        "ATZ",
        "ATE0",
        "ATL0",
        "ATS0",
        "ATH1",
        "ATSP6"
    )

    const val PROTOCOL_VERIFICATION_COMMAND = "0100"

    val FALLBACK_PROTOCOLS = listOf(
        CanProtocol.ISO_15765_29B_500K,
        CanProtocol.ISO_15765_11B_250K,
        CanProtocol.ISO_15765_29B_250K,
        CanProtocol.AUTO
    )

    fun registerEcuMapping(rxCanId: String, txCanId: String) {
        val rx = rxCanId.uppercase()
        val tx = txCanId.uppercase()
        if (rx != tx) {
            dynamicEcuMapping[rx] = tx
            Log.d(TAG, "Registered dynamic ECU mapping: $rx -> $tx")
        }
    }

    fun clearDynamicMappings() {
        dynamicEcuMapping.clear()
    }

    fun getAllEcuMappings(): Map<String, String> {
        return STANDARD_ECU_MAPPING + dynamicEcuMapping.toMap()
    }

    fun getPhysicalRequestId(rxCanId: String): String {
        val upper = rxCanId.uppercase()
        // If rxCanId is ALREADY a valid TX CAN header, return it directly
        if (upper in ALL_PHYSICAL_TX_HEADERS || upper in PHYSICAL_REQUEST_RANGE || upper in STANDARD_ECU_MAPPING.values) {
            return upper
        }
        STANDARD_ECU_MAPPING[upper]?.let { return it }
        dynamicEcuMapping[upper]?.let { return it }
        val idx = TYPICAL_RESPONSE_RANGE.indexOf(upper)
        if (idx in PHYSICAL_REQUEST_RANGE.indices) {
            return PHYSICAL_REQUEST_RANGE[idx]
        }
        Log.w(TAG, "No TX mapping for ECU at $rxCanId, using functional broadcast (7DF)")
        return FUNCTIONAL_REQUEST_ID
    }

    fun getExpectedRxId(txCanId: String): String? {
        val upper = txCanId.uppercase()
        // If already an RX CAN ID, return it
        if (upper in STANDARD_ECU_MAPPING.keys || upper in TYPICAL_RESPONSE_RANGE) {
            return upper
        }
        return STANDARD_ECU_MAPPING.entries.firstOrNull { it.value == upper }?.key
    }

    fun calculateRequestIdFromResponse(rxCanId: String): String? {
        val upper = rxCanId.uppercase()
        STANDARD_ECU_MAPPING[upper]?.let { return it }
        if (upper !in TYPICAL_RESPONSE_RANGE) return null
        val idx = TYPICAL_RESPONSE_RANGE.indexOf(upper)
        return PHYSICAL_REQUEST_RANGE.getOrNull(idx)
    }

    fun isKnownResponseId(canId: String): Boolean {
        val upper = canId.uppercase()
        return upper in TYPICAL_RESPONSE_RANGE ||
               upper in STANDARD_ECU_MAPPING.keys ||
               upper in dynamicEcuMapping.keys
    }
}
