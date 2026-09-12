package com.example.bluetooth

/** Why an RFCOMM connection to the adapter could not be established. */
enum class ConnectionFailureKind { BUSY, TIMEOUT, UNKNOWN }

/**
 * Pure classifier + user-facing guidance for adapter connect failures
 * (QA 2026-09-09, owner question: "what if the Bluetooth connection is already
 * held by a previous/other app?").
 *
 * An ELM327 exposes exactly ONE RFCOMM channel: while Torque / Car Scanner /
 * JioThings (or a second paired phone) holds it, every connect() from this app
 * fails with the classic "read failed, socket might closed or timeout,
 * read ret: -1" IOException. Instead of showing that raw text we name the cause
 * and give the actionable fix; the 10 s auto-connect loop
 * (MainViewModel.startSessionAutomation -> ObdQuickConnect) keeps retrying, so
 * the socket is grabbed the moment the other app lets go.
 */
object ConnectionFailureClassifier {

    private val BUSY_SIGNATURES = listOf(
        "read failed", "socket might closed", "read ret: -1",
        "connection refused", "service discovery failed", "broken pipe"
    )

    /**
     * BUSY is checked BEFORE timeout on purpose: the classic busy IOException
     * literally contains the word "timeout" ("socket might closed or timeout"),
     * so a timeout-first order would misclassify every occupied-adapter case.
     */
    fun classify(errorMessage: String?): ConnectionFailureKind {
        val m = errorMessage?.lowercase() ?: return ConnectionFailureKind.UNKNOWN
        return when {
            BUSY_SIGNATURES.any { m.contains(it) } -> ConnectionFailureKind.BUSY
            m.contains("timeout") || m.contains("timed out") -> ConnectionFailureKind.TIMEOUT
            else -> ConnectionFailureKind.UNKNOWN
        }
    }

    fun guidance(kind: ConnectionFailureKind, deviceName: String, errorMessage: String?): String =
        when (kind) {
            ConnectionFailureKind.BUSY ->
                "$deviceName is BUSY: its single connection is held by another app " +
                    "(Torque, Car Scanner, JioThings...) or another paired phone. Close other " +
                    "OBD apps, or unplug the adapter for 10 seconds. Auto-retry keeps knocking every 10 s."
            ConnectionFailureKind.TIMEOUT ->
                "$deviceName did not answer within 15 s - asleep, out of range, or unpowered. " +
                    "Turn the ignition ON and keep the phone near the adapter; auto-retry keeps knocking every 10 s."
            ConnectionFailureKind.UNKNOWN ->
                "Could not establish connection to $deviceName" +
                    (errorMessage?.let { " ($it)" } ?: "")
        }
}
