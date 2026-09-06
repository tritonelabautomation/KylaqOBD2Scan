package com.example.protocol

/**
 * VIN Candidate data class representing a validated VIN from a specific ECU.
 */
data class VinCandidate(
    val canId: String?,
    val vin: String,
    val isAuthoritative: Boolean
)

/**
 * Result of VIN selection by authority policy.
 */
sealed class VinSelectionResult {
    data class Success(val vin: String) : VinSelectionResult()
    data class Ambiguous(val reason: String) : VinSelectionResult()
    object Unavailable : VinSelectionResult()
}