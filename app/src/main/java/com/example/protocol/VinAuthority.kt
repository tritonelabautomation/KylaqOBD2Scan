package com.example.protocol

/**
 * VIN ECU Authority Model for KylaqOBD2Scan.
 *
 * VIN is a vehicle-level identifier that should come from an authoritative ECU.
 * The Engine ECU (7E8) is the primary authoritative source.
 * The Transmission ECU (7E1) is a secondary authoritative source.
 *
 * Policy:
 * 1. If an authoritative ECU (7E8 or 7E1) provides a valid VIN, accept it.
 * 2. If multiple authoritative ECUs provide different VINs, fail closed (VIN Ambiguous).
 * 3. If multiple ECUs (authoritative or not) provide different VINs, fail closed.
 * 4. If only unauthorized ECUs provide valid VINs, reject (VIN Unavailable).
 * 5. If no valid VINs are received, return VIN Unavailable.
 *
 * This ensures a syntactically valid VIN from the wrong ECU cannot silently become
 * the vehicle VIN merely because it appeared first in the parsed message list.
 */
object VinAuthority {

    /**
     * Authoritative VIN ECU CAN IDs - prioritized order for selection.
     */
    private val AUTHORITATIVE_ECUS = listOf("7E8", "7E1")

    /**
     * Determines if a CAN ID corresponds to an authoritative VIN ECU.
     * Authoritative ECUs are: 7E8 (Engine), 7E1 (Transmission).
     */
    fun isAuthoritativeVinEcu(canId: String?): Boolean {
        if (canId == null) return false
        val upper = canId.uppercase()
        return upper == "7E8" || upper == "7E1"
    }

    /**
     * Gets the authority priority for a CAN ID (lower = more authoritative).
     */
    fun getAuthorityPriority(canId: String?): Int? {
        if (canId == null) return null
        val upper = canId.uppercase()
        val index = AUTHORITATIVE_ECUS.indexOf(upper)
        return if (index >= 0) index else null
    }

    /**
     * Validates an ISO-TP message as a potential VIN candidate.
     */
    fun validateVinCandidate(message: IsoTpMessage): VinCandidate? {
        if (!message.isComplete || message.isMalformed) return null
        val bytes = message.reconstructedBytes
        if (bytes.size != 20) return null
        if (bytes[0] != 0x49 || bytes[1] != 0x02 || bytes[2] != 0x01) return null
        
        val vinBytes = bytes.slice(3..19)
        val validVinChars = Regex("^[A-HJ-NPR-Z0-9]$")
        val vinChars = vinBytes.map { it.toChar() }
        if (!vinChars.all { ch -> ch.code in 0..127 && ch.toString().matches(validVinChars) }) return null
        
        val vin = vinChars.joinToString("")
        if (!vin.matches(Regex("^[A-HJ-NPR-Z0-9]{17}$"))) return null
        
        return VinCandidate(
            canId = message.canId,
            vin = vin,
            isAuthoritative = isAuthoritativeVinEcu(message.canId)
        )
    }

    /**
     * Collects all valid VIN candidates from ISO-TP messages.
     */
    fun collectVinCandidates(messages: List<IsoTpMessage>): List<VinCandidate> {
        return messages.mapNotNull { validateVinCandidate(it) }
    }

    /**
     * Selects the VIN using the ECU authority policy.
     *
     * Policy:
     * 1. If authoritative ECU(s) provide valid VINs, use the most authoritative one.
     * 2. If multiple authoritative ECUs provide the SAME VIN, accept it.
     * 3. If multiple authoritative ECUs provide DIFFERENT VINs, fail closed.
     * 4. If only unauthorized ECUs provide valid VINs, reject.
     */
    fun selectVinByAuthority(candidates: List<VinCandidate>): VinSelectionResult {
        if (candidates.isEmpty()) return VinSelectionResult.Unavailable

        val authoritativeCandidates = candidates.filter { it.isAuthoritative }
        val unauthorizedCandidates = candidates.filter { !it.isAuthoritative }

        // No authoritative ECUs responded - reject unauthorized
        if (authoritativeCandidates.isEmpty()) {
            return VinSelectionResult.Unavailable
        }

        // Sort authoritative candidates by priority (7E8 first, then 7E1)
        val sortedAuthoritative = authoritativeCandidates.sortedBy {
            getAuthorityPriority(it.canId) ?: Int.MAX_VALUE
        }

        // Get unique VINs from authoritative ECUs
        val authoritativeVinSet = authoritativeCandidates.map { it.vin }.toSet()

        // Multiple authoritative ECUs with different VINs - fail closed
        if (authoritativeVinSet.size > 1) {
            return VinSelectionResult.Ambiguous(
                "Multiple authoritative ECUs reported different VINs"
            )
        }

        val authoritativeVin = sortedAuthoritative.first().vin

        // Check unauthorized ECUs don't have conflicting VINs
        val unauthorizedVinSet = unauthorizedCandidates.map { it.vin }.toSet()
        if (unauthorizedVinSet.isNotEmpty() && !unauthorizedVinSet.all { it == authoritativeVin }) {
            return VinSelectionResult.Ambiguous(
                "Unauthorized ECUs reported different VIN than authoritative ECU"
            )
        }

        return VinSelectionResult.Success(authoritativeVin)
    }
}