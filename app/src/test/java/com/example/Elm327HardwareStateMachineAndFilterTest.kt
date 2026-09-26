package com.example

import com.example.bluetooth.ElmTransport
import com.example.model.*
import com.example.protocol.CodingLabCodec
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Strict ELM327 Hardware State Machine & Physical CAN Acceptance Filter Integration Test.
 *
 * This test suite models the REAL physical behavior of an ELM327 / STN OBD adapter:
 *  1. ISO 15765-4 (ATSP6) hardware receive filters default to 7E8..7EF.
 *  2. Sending ATSH 714 (EPS) without ATCRA 77E causes the adapter hardware to DROP
 *     the 77E response frame and return "NO DATA".
 *  3. Sending ATCRA 77E instructs the adapter hardware to accept the 77E frame.
 *  4. Switching back to 7E0 requires clearing the custom filter via ATCRA so 7E8 frames
 *     are not dropped by a stale 77E filter.
 *  5. UDS Service 0x22 requests sent to functional broadcast 7DF are ignored by ECUs.
 */
class Elm327HardwareStateMachineAndFilterTest {

    /**
     * Stateful ELM327 hardware emulator that enforces physical CAN transceivers and acceptance masks.
     */
    class StrictElm327HardwareEmulator : ElmTransport {
        var isConnectedState = true
        var currentTxHeader: String = "7E0"
        var currentRxFilter: String? = null // null means default 7E8..7EF
        val commandHistory = mutableListOf<String>()

        override val isConnected: Boolean get() = isConnectedState

        override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse {
            val cmd = command.trim()
            commandHistory.add(cmd)
            val clean = cmd.replace(" ", "").uppercase()

            if (clean == "ATZ") {
                currentTxHeader = "7E0"
                currentRxFilter = null
                return ElmResponse("ELM327 v1.5\r\n>", listOf("ELM327 v1.5"), ResponseStatus.OK, true, 10L)
            }
            if (clean.startsWith("ATSH")) {
                currentTxHeader = clean.removePrefix("ATSH")
                return ElmResponse("OK\r\n>", listOf("OK"), ResponseStatus.OK, true, 5L)
            }
            if (clean == "ATCRA") {
                currentRxFilter = null // Clears filter back to default 7E8-7EF
                return ElmResponse("OK\r\n>", listOf("OK"), ResponseStatus.OK, true, 5L)
            }
            if (clean.startsWith("ATCRA")) {
                currentRxFilter = clean.removePrefix("ATCRA")
                return ElmResponse("OK\r\n>", listOf("OK"), ResponseStatus.OK, true, 5L)
            }
            if (clean.startsWith("AT")) {
                return ElmResponse("OK\r\n>", listOf("OK"), ResponseStatus.OK, true, 5L)
            }

            // UDS Service 0x22 request sent to broadcast 7DF -> ECUs ignore UDS 0x22 on broadcast!
            if (currentTxHeader == "7DF" && clean.startsWith("22")) {
                return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
            }

            // EPS Steering Assist Module 44 (TX 714 -> RX 77E)
            if (currentTxHeader == "714") {
                if (currentRxFilter == "77E") {
                    if (clean == "220200") {
                        val line = "77E 05 62 02 00 00 A4" // +16.4 deg
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 20L)
                    }
                    if (clean == "22F190") {
                        val line = "77E 10 14 62 F1 90 54 4D 42"
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 20L)
                    }
                } else {
                    // Physical ELM327 hardware drops 77E because filter is still looking for 7E8-7EF!
                    return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
                }
            }

            // ABS Module 03 (TX 713 -> RX 77D)
            if (currentTxHeader == "713") {
                if (currentRxFilter == "77D") {
                    if (clean.startsWith("2202B")) {
                        val did = clean.removePrefix("22")
                        val line = "77D 05 62 $did 00 00"
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 20L)
                    }
                } else {
                    return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
                }
            }

            // Climatronic Module 08 (TX 711 -> RX 77B)
            if (currentTxHeader == "711") {
                if (currentRxFilter == "77B") {
                    if (clean.startsWith("22028")) {
                        val did = clean.removePrefix("22")
                        val line = "77B 04 62 $did 50"
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 20L)
                    }
                } else {
                    return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
                }
            }

            // Gateway Module 19 (TX 710 -> RX 77A)
            if (currentTxHeader == "710") {
                if (currentRxFilter == "77A") {
                    if (clean.startsWith("22026")) {
                        val did = clean.removePrefix("22")
                        val line = "77A 04 62 $did 55"
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 20L)
                    }
                } else {
                    return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
                }
            }

            // Engine ECM Module 01 (TX 7E0 -> RX 7E8)
            if (currentTxHeader == "7E0") {
                if (currentRxFilter == null || currentRxFilter == "7E8") {
                    if (clean == "010C") {
                        val line = "7E8 04 41 0C 1A F8" // 1726 RPM
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 15L)
                    }
                    if (clean == "220202") {
                        val line = "7E8 04 62 02 02 78" // 80 deg C
                        return ElmResponse("$line\r\n>", listOf(line), ResponseStatus.OK, true, 15L)
                    }
                } else {
                    // Filter stuck on non-powertrain filter (e.g. 77E) drops 7E8 frame!
                    return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
                }
            }

            return ElmResponse("NO DATA\r\n>", listOf("NO DATA"), ResponseStatus.NO_DATA, true, 200L, "NO DATA")
        }

        override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> {
            return initSequence.map { it to sendCommand(it) }
        }

        override fun disconnect() {
            isConnectedState = false
        }
    }

    @Test
    fun `reproducing previous bug - sending ATSH 714 without ATCRA 77E results in NO DATA`() = runBlocking {
        val emulator = StrictElm327HardwareEmulator()
        // Simulate earlier buggy behavior: set ATSH 714 but forget ATCRA
        emulator.sendCommand("ATSH 714")
        val resp = emulator.sendCommand("22 02 00")
        assertEquals(ResponseStatus.NO_DATA, resp.status)
        assertEquals("NO DATA", resp.rawText.trim().replace("\r", "").replace("\n", "").replace(">", "").trim())
    }

    @Test
    fun `reproducing previous bug - sending UDS 0x22 to 7DF broadcast results in NO DATA`() = runBlocking {
        val emulator = StrictElm327HardwareEmulator()
        // Simulate earlier fallback to 7DF broadcast for non-powertrain UDS
        emulator.sendCommand("ATSH 7DF")
        val resp = emulator.sendCommand("22 02 00")
        assertEquals(ResponseStatus.NO_DATA, resp.status)
    }

    @Test
    fun `verified fix - proper ATSH 714 and ATCRA 77E sequence receives valid EPS frame`() = runBlocking {
        val emulator = StrictElm327HardwareEmulator()
        val txHeader = KylaqProtocolProfile.getPhysicalRequestId("77E")
        val rxFilter = KylaqProtocolProfile.getExpectedRxId(txHeader)

        assertEquals("714", txHeader)
        assertEquals("77E", rxFilter)

        emulator.sendCommand("ATSH $txHeader")
        emulator.sendCommand("ATCRA $rxFilter")
        val resp = emulator.sendCommand("22 02 00")

        assertEquals(ResponseStatus.OK, resp.status)
        assertTrue(resp.lines.any { it.contains("77E 05 62 02 00") })
    }

    @Test
    fun `verified fix - switching from EPS back to Engine cleans up CRA filter so 7E8 is received`() = runBlocking {
        val emulator = StrictElm327HardwareEmulator()

        // 1. Query EPS with 714 + ATCRA 77E
        emulator.sendCommand("ATSH 714")
        emulator.sendCommand("ATCRA 77E")
        val epsResp = emulator.sendCommand("22 02 00")
        assertEquals(ResponseStatus.OK, epsResp.status)

        // 2. Switch back to Engine 7E0 and clear CRA filter via bare ATCRA
        emulator.sendCommand("ATSH 7E0")
        emulator.sendCommand("ATCRA") // Reset filter to default 7E8-7EF
        val engineResp = emulator.sendCommand("01 0C")
        assertEquals(ResponseStatus.OK, engineResp.status)
        assertTrue(engineResp.lines.any { it.contains("7E8 04 41 0C") })
    }

    @Test
    fun `audit all catalogue UDS PIDs - every UDS DID has non-blank physical header and never maps to 7DF`() {
        val defaults = DefaultPidDefinitions.getDefaults()
        val udsPids = defaults.filter { it.service.equals("22", ignoreCase = true) }

        assertTrue("Expected at least 20 Extended UDS PIDs in catalogue", udsPids.size >= 20)

        for (pid in udsPids) {
            assertTrue("PID ${pid.id} (${pid.name}) must define non-blank canHeader", pid.canHeader.isNotBlank())
            assertTrue("PID ${pid.id} (${pid.name}) must define non-blank expectedRxId", pid.expectedRxId.isNotBlank())
            assertNotEquals("PID ${pid.id} must never have functional broadcast 7DF as canHeader", "7DF", pid.canHeader)

            val resolvedTx = KylaqProtocolProfile.getPhysicalRequestId(pid.expectedRxId)
            assertEquals("KylaqProtocolProfile must resolve ${pid.expectedRxId} to physical TX ${pid.canHeader}", pid.canHeader, resolvedTx)

            val resolvedRx = KylaqProtocolProfile.getExpectedRxId(pid.canHeader)
            assertEquals("KylaqProtocolProfile must resolve ${pid.canHeader} to expected RX ${pid.expectedRxId}", pid.expectedRxId, resolvedRx)

            assertTrue("SafetyValidator must permit ATSH ${pid.canHeader}", SafetyValidator.validateCommand("ATSH ${pid.canHeader}") is ValidationResult.Allowed)
            assertTrue("SafetyValidator must permit ATCRA ${pid.expectedRxId}", SafetyValidator.validateCommand("ATCRA ${pid.expectedRxId}") is ValidationResult.Allowed)
        }
    }

    @Test
    fun `audit all 9 MQB modules - ALL_MODULE_HEADERS fully maps to valid RX addresses in KylaqProtocolProfile`() {
        val headers = CodingLabCodec.ALL_MODULE_HEADERS
        assertEquals(9, headers.size)

        for (header in headers) {
            val rx = KylaqProtocolProfile.getExpectedRxId(header)
            assertNotNull("Header $header must map to a valid expected RX ID", rx)
            assertTrue("Header $header must map to an 11-bit CAN ID (3 chars)", rx!!.length == 3)
            val txBack = KylaqProtocolProfile.getPhysicalRequestId(rx)
            assertEquals("Reverse mapping of $rx must return $header", header, txBack)
        }
    }
}
