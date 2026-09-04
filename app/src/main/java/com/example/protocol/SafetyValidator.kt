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
/**
 * Explicit service categories for diagnostic safety enforcement.
 */
enum class ServiceCategory {
    READ_ONLY_OBD,   // 01, 02, 03, 05, 06, 07, 09, 0A
    READ_ONLY_UDS,   // 22 (ReadDataByIdentifier)
    CONTROL,         // 04 (Clear DTCs), 08 (Actuator / Bidirectional Control)
    WRITE,           // 2E (WriteDataByIdentifier)
    SECURITY,        // 27 (SecurityAccess / Seed-Key)
    FLASH,           // 34, 35, 36, 37 (Programming / Transfer)
    SESSION_CONTROL, // 10 (DiagnosticSessionControl), 28 (CommControl), 31 (RoutineControl), 85 (ControlDTCSetting)
    UNKNOWN
}

object SafetyValidator {

    // Read-only OBD services safe for passive logging and discovery:
    // 01: Current Powertrain Diagnostic Data
    // 02: Powertrain Freeze Frame Data
    // 03: Emission-Related Diagnostic Trouble Codes
    // 05: Oxygen Sensor Monitoring Test Results
    // 06: On-Board Monitoring Test Results
    // 07: Pending Diagnostic Trouble Codes
    // 09: Vehicle Information (VIN, Calibration ID, ECU Name)
    // 0A: Permanent Diagnostic Trouble Codes
    private val READ_ONLY_OBD_SERVICES = setOf("01", "02", "03", "05", "06", "07", "09", "0A")

    // Read-only UDS services:
    // 22: Read Data By Identifier
    private val READ_ONLY_UDS_SERVICES = setOf("22")

    // Control and clear services:
    // 04: Clear/Reset DTCs and freeze frame
    // 08: Actuator control / bidirectional component tests
    private val CONTROL_SERVICES = setOf("04", "08")

    // Dangerous write/flash/session/security services strictly blocked:
    private val WRITE_SERVICES = setOf("2E")
    private val SECURITY_SERVICES = setOf("27")
    private val FLASH_SERVICES = setOf("34", "35", "36", "37")
    private val SESSION_CONTROL_SERVICES = setOf("10", "28", "31", "85")

    /**
     * Resolves the safety category for a given 2-digit hex service identifier.
     */
    fun getServiceCategory(serviceHex: String): ServiceCategory {
        val svc = serviceHex.uppercase()
        return when {
            READ_ONLY_OBD_SERVICES.contains(svc) -> ServiceCategory.READ_ONLY_OBD
            READ_ONLY_UDS_SERVICES.contains(svc) -> ServiceCategory.READ_ONLY_UDS
            CONTROL_SERVICES.contains(svc) -> ServiceCategory.CONTROL
            WRITE_SERVICES.contains(svc) -> ServiceCategory.WRITE
            SECURITY_SERVICES.contains(svc) -> ServiceCategory.SECURITY
            FLASH_SERVICES.contains(svc) -> ServiceCategory.FLASH
            SESSION_CONTROL_SERVICES.contains(svc) -> ServiceCategory.SESSION_CONTROL
            else -> ServiceCategory.UNKNOWN
        }
    }

    /**
     * Returns true if a service category is safe for automatic execution.
     */
    fun isCategoryAutomaticallyAllowed(category: ServiceCategory): Boolean {
        return category == ServiceCategory.READ_ONLY_OBD || category == ServiceCategory.READ_ONLY_UDS
    }

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
        val category = getServiceCategory(service)

        return when (category) {
            ServiceCategory.READ_ONLY_OBD,
            ServiceCategory.READ_ONLY_UDS -> ValidationResult.Allowed

            ServiceCategory.CONTROL -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a control/clear service (Category: CONTROL) and is blocked from automatic execution."
            )

            ServiceCategory.WRITE -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a write service (Category: WRITE) and is strictly prohibited."
            )

            ServiceCategory.SECURITY -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a security access service (Category: SECURITY) and is strictly prohibited."
            )

            ServiceCategory.FLASH -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a flash/programming service (Category: FLASH) and is strictly prohibited."
            )

            ServiceCategory.SESSION_CONTROL -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is a session control service (Category: SESSION_CONTROL) and is strictly prohibited."
            )

            ServiceCategory.UNKNOWN -> ValidationResult.Rejected(
                "SAFETY BLOCK: Service 0x$service is not an approved passive read-only OBD service."
            )
        }
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
