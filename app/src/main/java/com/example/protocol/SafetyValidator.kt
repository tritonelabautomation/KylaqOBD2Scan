package com.example.protocol

/**
 * Enforces strict safety rules for passive data logging.
 *
 * CRITICAL SAFETY DIRECTIVE:
 * This is a passive diagnostic/logger application.
 * It must NOT perform ECU coding, adaptations, flashing, actuator tests,
 * immobilizer operations, security-key calculations, or destructive diagnostic operations.
 * Do not implement seed/key algorithms. Do not automatically enter diagnostic sessions
 * or security access. Only send explicitly allowed read-only OBD/AT commands.
 */
object SafetyValidator {

    // Allowed read-only OBD services:
    // 01: Current Powertrain Diagnostic Data
    // 02: Powertrain Freeze Frame Data
    // 09: Vehicle Information (VIN, Calibration ID, etc.)
    private val ALLOWED_OBD_SERVICES = setOf("01", "02", "09")

    // Disallowed services explicitly blocked:
    // 04: Clear DTCs / Reset Emission Data (BLOCKED)
    // 08: Control Operation of On-Board Component (BLOCKED)
    // 10: Diagnostic Session Control (BLOCKED)
    // 27: Security Access / Seed-Key (BLOCKED)
    // 2E: Write Data By Identifier (BLOCKED)
    // 31: Routine Control (BLOCKED)
    // 34: Request Download / Flash (BLOCKED)
    // 35: Request Upload (BLOCKED)
    // 36: Transfer Data / Flash Write (BLOCKED)
    // 37: Request Transfer Exit (BLOCKED)
    // 28: Communication Control (BLOCKED)
    // 85: Control DTC Setting (BLOCKED)
    private val BLOCKED_SERVICES = setOf("04", "08", "10", "27", "28", "2E", "31", "34", "35", "36", "37", "85")

    /**
     * Checks if a command is safe to send to the vehicle.
     * @return ValidationResult indicating whether the command is allowed or why it was rejected.
     */
    fun validateCommand(rawCommand: String): ValidationResult {
        val cleanCmd = rawCommand.trim().uppercase().replace(" ", "")

        if (cleanCmd.isEmpty()) {
            return ValidationResult.Rejected("Empty command")
        }

        // Allow standard safe AT commands
        if (cleanCmd.startsWith("AT")) {
            return validateAtCommand(cleanCmd)
        }

        // Check if hex command
        if (!cleanCmd.matches(Regex("^[0-9A-F]+$"))) {
            return ValidationResult.Rejected("Command contains invalid non-hex characters: $cleanCmd")
        }

        // OBD service is first 2 hex chars
        if (cleanCmd.length < 2) {
            return ValidationResult.Rejected("Command too short: $cleanCmd")
        }

        val service = cleanCmd.substring(0, 2)

        if (BLOCKED_SERVICES.contains(service)) {
            return ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a write/control/flashing/clear service and is strictly prohibited in passive logging mode."
            )
        }

        if (!ALLOWED_OBD_SERVICES.contains(service)) {
            return ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is not an approved passive read-only OBD service."
            )
        }

        return ValidationResult.Allowed
    }

    private fun validateAtCommand(cmd: String): ValidationResult {
        // Safe configuration and query AT commands
        val safeAtPrefixes = listOf(
            "ATZ",      // Reset
            "ATE0",     // Echo off
            "ATE1",     // Echo on
            "ATL0",     // Linefeeds off
            "ATL1",     // Linefeeds on
            "ATS0",     // Spaces off
            "ATS1",     // Spaces on
            "ATH0",     // Headers off
            "ATH1",     // Headers on
            "ATSP",     // Set protocol (e.g. ATSP6, ATSP0)
            "ATDP",     // Describe protocol
            "ATDPN",    // Describe protocol number
            "ATRV",     // Read voltage
            "ATSH",     // Set header (e.g. ATSH7DF, ATSH7E0)
            "ATCRA",    // Set CAN Rx Address filter
            "ATCAF",    // CAN Auto Format
            "ATST",     // Set timeout
            "ATAT",     // Adaptive timing
            "ATBI",     // Bypass initialization
            "ATBD",     // Buffer dump
            "ATI",      // Identify chip
            "AT@1",     // Device description
            "ATPC",     // Protocol close
            "ATCS",     // CAN status
            "ATMA"      // Monitor all (handled with care, but passive)
        )

        val isSafe = safeAtPrefixes.any { cmd.startsWith(it) }
        return if (isSafe) {
            ValidationResult.Allowed
        } else {
            ValidationResult.Rejected("AT Command '$cmd' is not in the approved safe read-only configuration list.")
        }
    }
}

sealed class ValidationResult {
    object Allowed : ValidationResult()
    data class Rejected(val reason: String) : ValidationResult()
}
