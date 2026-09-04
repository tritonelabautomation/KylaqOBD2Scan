package com.example.model

/**
 * Authoritative protocol profile for the Škoda Kylaq 1.0 TSI (EA211).
 *
 * Experimentally confirmed CAN configuration:
 * - Vehicle: Škoda Kylaq
 * - Engine: 1.0 TSI (EA211)
 * - Protocol: ISO 15765-4 CAN
 * - CAN identifier: 11-bit (Standard)
 * - Bitrate: 500,000 baud (500 kbit/s)
 * - ELM327 protocol: ATSP6
 * - Functional request ID: 0x7DF
 * - Physical request range: 0x7E0–0x7E7
 * - Typical physical response range: 0x7E8–0x7EF
 * - ISO-TP: enabled (multi-frame reassembly)
 * - Addressing: Normal 11-bit addressing
 */
object KylaqProtocolProfile {
    const val VEHICLE_NAME = "Škoda Kylaq"
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
     * Safe adapter initialization sequence for ELM327 communicating with Škoda Kylaq.
     */
    val DEFAULT_INIT_SEQUENCE = listOf(
        "ATZ",   // Reset adapter
        "ATE0",  // Echo off
        "ATL0",  // Linefeeds off
        "ATS0",  // Spaces off (cleaner throughput)
        "ATH1",  // Headers on (required for CAN ID identification: 7E8, 7E9, etc.)
        "ATSP6"  // Force ISO 15765-4 CAN (11-bit / 500k)
    )

    /**
     * Harmless standard OBD request used to verify protocol communication with the vehicle.
     */
    const val PROTOCOL_VERIFICATION_COMMAND = "0100"

    /**
     * Fallback protocols available if ATSP6 communication fails to verify.
     */
    val FALLBACK_PROTOCOLS = listOf(
        CanProtocol.ISO_15765_29B_500K,
        CanProtocol.ISO_15765_11B_250K,
        CanProtocol.ISO_15765_29B_250K,
        CanProtocol.AUTO
    )

    /**
     * Resolves the physical CAN request address for an observed physical CAN response ID.
     */
    fun getPhysicalRequestId(rxCanId: String): String {
        val upper = rxCanId.uppercase()
        val idx = TYPICAL_RESPONSE_RANGE.indexOf(upper)
        return if (idx in PHYSICAL_REQUEST_RANGE.indices) {
            PHYSICAL_REQUEST_RANGE[idx]
        } else {
            FUNCTIONAL_REQUEST_ID
        }
    }
}
