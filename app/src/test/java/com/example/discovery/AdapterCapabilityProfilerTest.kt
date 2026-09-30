package com.example.discovery

import com.example.bluetooth.ElmResponse
import com.example.bluetooth.ElmTransport
import com.example.model.ResponseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdapterCapabilityProfilerTest {

    private class MockBenchmarkTransport(
        private val responseMap: Map<String, ElmResponse>
    ) : ElmTransport {
        override val isConnected: Boolean = true

        override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse {
            val cmdTrim = command.trim()
            return responseMap[cmdTrim] ?: ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L)
        }

        override suspend fun connect(deviceAddress: String): Boolean = true
        override fun disconnect() {}
    }

    @Test
    fun testBenchmark_IdentifiesAuthenticStnAdapterWithMultiFrameIsoTp() = runBlocking {
        val responses = mapOf(
            "ATZ" to ElmResponse(ResponseStatus.OK, listOf("ELM327 v1.4b"), "ELM327 v1.4b", 10L),
            "ATI" to ElmResponse(ResponseStatus.OK, listOf("OBDLink MX+ v4.2.0"), "OBDLink MX+ v4.2.0", 10L),
            "AT@1" to ElmResponse(ResponseStatus.OK, listOf("OBDLink MX+"), "OBDLink MX+", 10L),
            "AT@2" to ElmResponse(ResponseStatus.OK, listOf("STN2120"), "STN2120", 10L),
            "STI" to ElmResponse(ResponseStatus.OK, listOf("STN2120 v4.2.0"), "STN2120 v4.2.0", 10L),
            "ATSH 7E0" to ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L),
            "ATCRA 7E8" to ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L),
            "ATFC SH 7E0" to ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L),
            "ATFC SD 30 00 00" to ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L),
            "ATFC SM 1" to ElmResponse(ResponseStatus.OK, listOf("OK"), "OK", 10L),
            "22 F1 90" to ElmResponse(
                ResponseStatus.OK,
                listOf(
                    "7E8 10 14 62 F1 90 4D 45 58",
                    "7E8 21 4B 50 45 50 43 32 54",
                    "7E8 22 47 30 32 38 38 35 35"
                ),
                "7E8 10 14 62 F1 90 4D 45 58\n7E8 21 4B 50 45 50 43 32 54\n7E8 22 47 30 32 38 38 35 35",
                25L
            ),
            "22 02 00" to ElmResponse(ResponseStatus.OK, listOf("77E 05 62 02 00 00 00"), "77E 05 62 02 00 00 00", 15L)
        )

        val transport = MockBenchmarkTransport(responses)
        val report = AdapterCapabilityProfiler.runBenchmark(transport)

        assertTrue(report.isStnChip)
        assertFalse(report.isCloneOrCounterfeit)
        assertTrue(report.customHeadersSupported)
        assertTrue(report.flowControlSupported)
        assertTrue(report.multiFrameIsoTpVerified)
        assertEquals("MEXKPEPC2TG028855", report.vinMultiFrameReassembled)
        assertEquals(Module44ProbeStatus.RESPONDED_POSITIVE, report.module44Status)
    }

    @Test
    fun testBenchmark_DetectsCloneAdapterAndNrcOutOfRangeOnModule44() = runBlocking {
        val responses = mapOf(
            "ATZ" to ElmResponse(ResponseStatus.OK, listOf("ELM327 v1.5"), "ELM327 v1.5", 10L),
            "ATI" to ElmResponse(ResponseStatus.OK, listOf("ELM327 v1.5"), "ELM327 v1.5", 10L),
            "AT@1" to ElmResponse(ResponseStatus.ERROR, listOf("?"), "?", 10L),
            "AT@2" to ElmResponse(ResponseStatus.ERROR, listOf("?"), "?", 10L),
            "STI" to ElmResponse(ResponseStatus.ERROR, listOf("?"), "?", 10L),
            "ATFC SH 7E0" to ElmResponse(ResponseStatus.ERROR, listOf("?"), "?", 10L),
            "22 F1 90" to ElmResponse(ResponseStatus.NO_DATA, emptyList(), "NO DATA", 50L),
            "09 02" to ElmResponse(ResponseStatus.OK, listOf("7E8 01 02", "7E8 02 03"), "7E8 01 02\n7E8 02 03", 20L),
            "22 02 00" to ElmResponse(ResponseStatus.OK, listOf("77E 03 7F 22 31"), "77E 03 7F 22 31", 15L)
        )

        val transport = MockBenchmarkTransport(responses)
        val report = AdapterCapabilityProfiler.runBenchmark(transport)

        assertFalse(report.isStnChip)
        assertTrue(report.isCloneOrCounterfeit)
        assertEquals(Module44ProbeStatus.NRC_OUT_OF_RANGE_31, report.module44Status)
    }
}
