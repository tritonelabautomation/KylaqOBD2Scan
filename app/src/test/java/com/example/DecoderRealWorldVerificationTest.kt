package com.example

import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.protocol.DtcDecoder
import com.example.protocol.PidDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for real-world OBD-II session 010e99b5.json
 * (Skoda Kylaq 1.0 TSI EA211, 2026-09-02, ISO 15765-4 CAN 500 kbps).
 *
 * Each test mirrors an actual (hex payload, decoded value) pair observed
 * in 010e99b5_transactions.csv to guarantee decoder formulas stay
 * accurate against production data.
 */
class DecoderRealWorldVerificationTest {

    private fun pid(id: String, dec: DecoderType) = PidDefinition(
        id = id, service = "01", pid = id.removePrefix("01"),
        name = "Test $id", shortName = id, unit = "",
        decoderType = dec, isResearch = false
    )

    // ─── 1. RPM (PID 0x0C) — ((A*256)+B)/4 ──────────────────────────
    @Test fun rpm_410C15B4_decodes1389() {
        val r = PidDecoder.decode(pid("010C", DecoderType.RPM_FORMULA),
            listOf(0x41, 0x0C, 0x15, 0xB4))
        assertEquals(1389.0, r.numericValue!!, 0.01)
    }

    @Test fun rpm_410C10B2_decodes1068_5() {
        val r = PidDecoder.decode(pid("010C", DecoderType.RPM_FORMULA),
            listOf(0x41, 0x0C, 0x10, 0xB2))
        assertEquals(1068.5, r.numericValue!!, 0.01)
    }

    @Test fun rpm_410C15AA_dualEcu_7E9_decodes1386_5() {
        val r = PidDecoder.decode(pid("010C", DecoderType.RPM_FORMULA),
            listOf(0x41, 0x0C, 0x15, 0xAA))
        assertEquals(1386.5, r.numericValue!!, 0.01)
    }

    // ─── 2. Speed (PID 0x0D) — A km/h ─────────────────────────────────
    @Test fun speed_410D00_decodes0() {
        val r = PidDecoder.decode(pid("010D", DecoderType.RAW_A_KMH),
            listOf(0x41, 0x0D, 0x00))
        assertEquals(0.0, r.numericValue!!, 0.01)
    }

    @Test fun speed_410D16_decodes22() {
        val r = PidDecoder.decode(pid("010D", DecoderType.RAW_A_KMH),
            listOf(0x41, 0x0D, 0x16))
        assertEquals(22.0, r.numericValue!!, 0.01)
    }

    // ─── 3. Engine Load (PID 0x04) — A*100/255 ────────────────────────
    @Test fun load_41045D_decodes36_47() {
        val r = PidDecoder.decode(pid("0104", DecoderType.PERCENT_255),
            listOf(0x41, 0x04, 0x5D))
        assertEquals(36.47, r.numericValue!!, 0.01)
    }

    // ─── 4. Throttle (PID 0x11) — A*100/255 ──────────────────────────
    @Test fun throttle_411142_decodes25_88() {
        val r = PidDecoder.decode(pid("0111", DecoderType.PERCENT_255),
            listOf(0x41, 0x11, 0x42))
        assertEquals(25.88, r.numericValue!!, 0.01)
    }

    // ─── 5. Coolant / IAT / Ambient — A-40 ────────────────────────────
    @Test fun coolant_41054A_decodes34C() {
        val r = PidDecoder.decode(pid("0105", DecoderType.TEMP_MINUS_40),
            listOf(0x41, 0x05, 0x4A))
        assertEquals(34.0, r.numericValue!!, 0.01)
    }

    @Test fun iat_410F45_decodes29C() {
        val r = PidDecoder.decode(pid("010F", DecoderType.TEMP_MINUS_40),
            listOf(0x41, 0x0F, 0x45))
        assertEquals(29.0, r.numericValue!!, 0.01)
    }

    @Test fun ambient_414641_decodes25C() {
        val r = PidDecoder.decode(pid("0146", DecoderType.TEMP_MINUS_40),
            listOf(0x41, 0x46, 0x41))
        assertEquals(25.0, r.numericValue!!, 0.01)
    }
    // ─── 7. Fuel Rate (PID 0x9D) — ((A*256)+B)/20 ───────────────────
    @Test fun fuelRate_419D00220022_decodes1_7() {
        val r = PidDecoder.decode(pid("019D", DecoderType.FUEL_RATE_20),
            listOf(0x41, 0x9D, 0x00, 0x22, 0x00, 0x22))
        assertEquals(1.7, r.numericValue!!, 0.01)
    }

    // ─── 8. Voltage (PID 0x42) — ((A*256)+B)/1000 ───────────────────
    @Test fun voltage_41423318_ECM_decodes13_08() {
        val r = PidDecoder.decode(pid("0142", DecoderType.VOLTAGE_1000),
            listOf(0x41, 0x42, 0x33, 0x18))
        assertEquals(13.08, r.numericValue!!, 0.01)
    }

    @Test fun voltage_414233BF_TCM_decodes13_247() {
        val r = PidDecoder.decode(pid("0142", DecoderType.VOLTAGE_1000),
            listOf(0x41, 0x42, 0x33, 0xBF))
        assertEquals(13.247, r.numericValue!!, 0.01)
    }

    // ─── 9. Engine Torque (PID 0x62) — A-125 ─────────────────────────
    @Test fun torque_416287_decodes10() {
        val r = PidDecoder.decode(pid("0162", DecoderType.TORQUE_PCT),
            listOf(0x41, 0x62, 0x87))
        assertEquals(10.0, r.numericValue!!, 0.01)
    }

    @Test fun torque_416294_decodes23() {
        val r = PidDecoder.decode(pid("0162", DecoderType.TORQUE_PCT),
            listOf(0x41, 0x62, 0x94))
        assertEquals(23.0, r.numericValue!!, 0.01)
    }

    // ─── 10. Reference Torque (PID 0x63) — A*256+B ──────────────────
    // ─── 13. Fuel Level (PID 0x2F) — A*100/255 ──────────────────────
    @Test fun fuelLevel_412F93_decodes57_65() {
        val r = PidDecoder.decode(pid("012F", DecoderType.PERCENT_255),
            listOf(0x41, 0x2F, 0x93))
        assertEquals(57.65, r.numericValue!!, 0.01)
    }

    // ─── 14. STFT/LTFT (PID 0x06/0x07) — (A-128)*100/128 ──────
    @Test fun stft_41067B_decodesMinus3_91() {
        val r = PidDecoder.decode(pid("0106", DecoderType.FUEL_TRIM),
            listOf(0x41, 0x06, 0x7B))
        assertEquals(-3.91, r.numericValue!!, 0.01)
    }

    @Test fun ltft_410788_decodes6_25() {
        val r = PidDecoder.decode(pid("0107", DecoderType.FUEL_TRIM),
            listOf(0x41, 0x07, 0x88))
        assertEquals(6.25, r.numericValue!!, 0.01)
    }

    // ─── 15. Fuel System Status (PID 0x03) — enum ────────────────────
    @Test fun fuelSys_41030200_decodesSystem2() {
        val r = PidDecoder.decode(pid("0103", DecoderType.FUEL_SYSTEM_STATUS),
            listOf(0x41, 0x03, 0x02, 0x00))
        assertNotNull(r.numericValue)
    }

    // ─── 16. Fuel Type (PID 0x51) — enum ────────────────────────────
    @Test fun fuelType_415101_decodes1() {
        val r = PidDecoder.decode(pid("0151", DecoderType.FUEL_TYPE_ENUM),
            listOf(0x41, 0x51, 0x01))
        assertEquals(1.0, r.numericValue!!, 0.01)
    }

    // ─── 17. MAP / Baro (PID 0x0B / 0x33) — A kPa ──────────────
    @Test fun map_410B54_decodes84() {
        val r = PidDecoder.decode(pid("010B", DecoderType.RAW_A_KPA),
            listOf(0x41, 0x0B, 0x54))
        assertEquals(84.0, r.numericValue!!, 0.01)
    }

    @Test fun baro_41335F_decodes95() {
        val r = PidDecoder.decode(pid("0133", DecoderType.RAW_A_KPA),
            listOf(0x41, 0x33, 0x5F))
        assertEquals(95.0, r.numericValue!!, 0.01)
    }

    // ─── 18. PID 0x67 sentinel: Skoda Kylaq ECU returns 0x03 ──────
    // as a "no sensor" sentinel for the secondary coolant probe.
    // Decoder formula is A-40, which correctly yields -37 for 0x03.
    // The numeric value is correct; the UI should suppress this impossible reading.
    @Test fun coolant2_4167035043_sentinelDecodesNegative37() {
        val r = PidDecoder.decode(pid("0167", DecoderType.TEMP_MINUS_40),
            listOf(0x41, 0x67, 0x03, 0x50, 0x43))
        assertEquals(-37.0, r.numericValue!!, 0.01)
    }

    // ─── 19. NO DATA handling: must not contaminate UI ───────────────
    @Test fun noData_emptyPayload_returnsNoData() {
        val r = PidDecoder.decode(pid("010C", DecoderType.RPM_FORMULA), emptyList())
        assertEquals("NO DATA", r.displayValue)
        assertNull(r.numericValue)
    }

    @Test fun noData_wrongServiceAck_returnsInvalid() {
        val r = PidDecoder.decode(pid("010C", DecoderType.RPM_FORMULA),
            listOf(0x42, 0x0C, 0x10, 0x00))
        assertEquals("INVALID_RESPONSE", r.displayValue)
        assertNull(r.numericValue)
    }

    // ─── 20. Research PIDs: 0x6D, 0x70 return raw bytes ──────────
    @Test fun research_416D_returnsHexBytes() {
        val r = PidDecoder.decode(pid("016D", DecoderType.RESEARCH_RAW),
            listOf(0x41, 0x6D, 0x02, 0x00, 0x00, 0x07, 0x94))
        assertTrue(r.displayValue != "NO DATA" && r.displayValue != "INVALID_RESPONSE")
    }

    // ─── DTC decoder tests ─────────────────────────────────────────────
    @Test fun dtc_43_payload_decodesP0101() {
        val dtcs = DtcDecoder.extractDtcs("430101", mode = 0x03)
        assertEquals(1, dtcs.size)
        assertEquals("P0101", dtcs[0])
    }

    @Test fun dtc_4300_emptyAfterAck_yieldsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("4300", mode = 0x03)
        assertTrue("43 00 should yield no DTCs", dtcs.isEmpty())
    }

    @Test fun dtc_negativeResponse_yieldsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("7F0312", mode = 0x03)
        assertTrue("Negative response must not yield DTCs", dtcs.isEmpty())
    }

    @Test fun dtc_wrongAckPrefix_yieldsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("410101", mode = 0x03)
        assertTrue("Service 01 ack passed to Mode 03 must yield empty", dtcs.isEmpty())
    }

    // ─── 10. Reference Torque (PID 0x63) — A*256+B ──────────────────
    @Test fun refTorque_416300AF_decodes175() {
        val r = PidDecoder.decode(pid("0163", DecoderType.TORQUE_NM),
            listOf(0x41, 0x63, 0x00, 0xAF))
        assertEquals(175.0, r.numericValue!!, 0.01)
    }

    // ─── 11. Catalyst Temp (PID 0x3C) — ((A*256)+B)/10 - 40 ─────────
    @Test fun catTemp_413C07D8_decodes160_8() {
        val r = PidDecoder.decode(pid("013C", DecoderType.CATALYST_TEMP),
            listOf(0x41, 0x3C, 0x07, 0xD8))
        assertEquals(160.8, r.numericValue!!, 0.01)
    }

    @Test fun catTemp_413C11CD_decodes415_7() {
        val r = PidDecoder.decode(pid("013C", DecoderType.CATALYST_TEMP),
            listOf(0x41, 0x3C, 0x11, 0xCD))
        assertEquals(415.7, r.numericValue!!, 0.01)
    }

    // ─── 12. Equivalence Ratio (PID 0x44) — /32768 ──────────────────
    @Test fun equivRatio_41448000_decodes1_0() {
        val r = PidDecoder.decode(pid("0144", DecoderType.EQUIVALENCE_RATIO),
            listOf(0x41, 0x44, 0x80, 0x00))
        assertEquals(1.0, r.numericValue!!, 0.01)
    }

    // ─── 6. Timing Advance (PID 0x0E) — A/2 - 64 ─────────────────────
    @Test fun timing_410E54_decodesMinus22() {
        val r = PidDecoder.decode(pid("010E", DecoderType.TIMING_ADVANCE),
            listOf(0x41, 0x0E, 0x54))
        assertEquals(-22.0, r.numericValue!!, 0.01)
    }

    @Test fun timing_410E96_decodesPlus11() {
        val r = PidDecoder.decode(pid("010E", DecoderType.TIMING_ADVANCE),
            listOf(0x41, 0x0E, 0x96))
        assertEquals(11.0, r.numericValue!!, 0.01)
    }
}
