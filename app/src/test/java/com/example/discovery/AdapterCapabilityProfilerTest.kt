package com.example.discovery

import com.example.bluetooth.Elm327Parser
import com.example.bluetooth.ElmResponse
import com.example.bluetooth.ElmTransport
import com.example.bluetooth.RawLogListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdapterCapabilityProfilerTest {

    private class MockBenchmarkTransport(
        private val responseMap: Map<String, String>
    ) : ElmTransport {
        override val isConnected: Boolean = true
        override val deviceAddress: String = "00:11:22:33:44:55"

        override suspend fun connect(): Boolean = true
        override suspend fun disconnect() {}

        override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse {
            val cmdTrim = command.trim()
            val raw = responseMap[cmdTrim] ?: "OK\n>"
            return Elm327Parser.parse(raw, 10L)
        }

        override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> = emptyList()
        override fun setRawLogListener(listener: RawLogListener?) {}
    }

    @Test
    fun testBenchmark_IdentifiesAuthenticStnAdapterWithMultiFrameIsoTp() = runBlocking {
        val responses = mapOf(
            "ATZ" to "ELM327 v1.4b\n>",
            "ATI" to "OBDLink MX+ v4.2.0\n>",
            "AT@1" to "OBDLink MX+\n>",
            "AT@2" to "STN2120\n>",
            "STI" to "STN2120 v4.2.0\n>",
            "ATSH 7E0" to "OK\n>",
            "ATCRA 7E8" to "OK\n>",
            "ATFC SH 7E0" to "OK\n>",
            "ATFC SD 30 00 00" to "OK\n>",
            "ATFC SM 1" to "OK\n>",
            "22 F1 90" to "7E8 10 14 62 F1 90 4D 45 58\n7E8 21 4B 50 45 50 43 32 54\n7E8 22 47 30 32 38 38 35 35\n>",
            "22 02 00" to "77E 05 62 02 00 00 00\n>"
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
            "ATZ" to "ELM327 v1.5\n>",
            "ATI" to "ELM327 v1.5\n>",
            "AT@1" to "?\n>",
            "AT@2" to "?\n>",
            "STI" to "?\n>",
            "ATFC SH 7E0" to "?\n>",
            "22 F1 90" to "NO DATA\n>",
            "09 02" to "7E8 01 02\n7E8 02 03\n>",
            "22 02 00" to "77E 03 7F 22 31\n>"
        )

        val transport = MockBenchmarkTransport(responses)
        val report = AdapterCapabilityProfiler.runBenchmark(transport)

        assertFalse(report.isStnChip)
        assertTrue(report.isCloneOrCounterfeit)
        assertEquals(Module44ProbeStatus.NRC_OUT_OF_RANGE_31, report.module44Status)
    }
}
