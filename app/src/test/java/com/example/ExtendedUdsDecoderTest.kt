package com.example

import com.example.model.DefaultPidDefinitions
import com.example.protocol.PidDecoder
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification of Extended UDS Diagnostic Queries (Service 0x22) for Škoda Kylaq (VAG MQB-A0-IN).
 */
class ExtendedUdsDecoderTest {

    private val defaults = DefaultPidDefinitions.getDefaults().associateBy { it.id }

    @Test
    fun `decodes Engine Oil Temperature DID 0202`() {
        val def = defaults.getValue("220202")
        // 0x62 0x02 0x02 0x84 -> 132 - 40 = 92 °C
        val res = PidDecoder.decode(def, listOf(0x62, 0x02, 0x02, 0x84))
        assertTrue(res.isKnown)
        assertEquals(92.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("92", res.displayValue)
    }

    @Test
    fun `decodes Boost Pressure Target and Actual DIDs 0203 and 0204`() {
        val targetDef = defaults.getValue("220203")
        val actualDef = defaults.getValue("220204")

        // 0x62 0x02 0x03 0x07 0xD0 -> 2000 hPa
        val resTarget = PidDecoder.decode(targetDef, listOf(0x62, 0x02, 0x03, 0x07, 0xD0))
        assertTrue(resTarget.isKnown)
        assertEquals(2000.0, resTarget.numericValue ?: 0.0, 0.001)

        // 0x62 0x02 0x04 0x07 0xD5 -> 2005 hPa
        val resActual = PidDecoder.decode(actualDef, listOf(0x62, 0x02, 0x04, 0x07, 0xD5))
        assertTrue(resActual.isKnown)
        assertEquals(2005.0, resActual.numericValue ?: 0.0, 0.001)
    }

    @Test
    fun `decodes Cylinder Misfire Counters DIDs 0205 to 0207`() {
        val cyl1Def = defaults.getValue("220205")
        val cyl3Def = defaults.getValue("220207")

        val resCyl1 = PidDecoder.decode(cyl1Def, listOf(0x62, 0x02, 0x05, 0x00, 0x00))
        assertTrue(resCyl1.isKnown)
        assertEquals(0.0, resCyl1.numericValue ?: -1.0, 0.001)
        assertEquals("0", resCyl1.displayValue)

        val resCyl3 = PidDecoder.decode(cyl3Def, listOf(0x62, 0x02, 0x07, 0x00, 0x05))
        assertTrue(resCyl3.isKnown)
        assertEquals(5.0, resCyl3.numericValue ?: 0.0, 0.001)
        assertEquals("5", resCyl3.displayValue)
    }

    @Test
    fun `decodes Knock Retard DID 0208 with Signed16 math`() {
        val cyl1Knock = defaults.getValue("220208")
        // 0x62 0x02 0x08 0xFF 0xEC -> 65516 - 65536 = -20 -> -2.0 °CA
        val res = PidDecoder.decode(cyl1Knock, listOf(0x62, 0x02, 0x08, 0xFF, 0xEC))
        assertTrue(res.isKnown)
        assertEquals(-2.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("-2.0 °CA", res.displayValue)
    }

    @Test
    fun `decodes High Pressure Fuel Rail DID 020B in bar`() {
        val railDef = defaults.getValue("22020B")
        // 0x62 0x02 0x0B 0x05 0xDC -> 1500 -> 150.0 bar
        val res = PidDecoder.decode(railDef, listOf(0x62, 0x02, 0x0B, 0x05, 0xDC))
        assertTrue(res.isKnown)
        assertEquals(150.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("150.0 bar", res.displayValue)
    }

    @Test
    fun `decodes Transmission ATF Fluid Temperature DID 0220`() {
        val atfDef = defaults.getValue("220220")
        // 0x62 0x02 0x20 0x78 -> 120 - 40 = 80 °C
        val res = PidDecoder.decode(atfDef, listOf(0x62, 0x02, 0x20, 0x78))
        assertTrue(res.isKnown)
        assertEquals(80.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("80", res.displayValue)
    }

    @Test
    fun `decodes 4-Wheel Speed Sensors and Brake Pressure on Module 03`() {
        val speedFlDef = defaults.getValue("2202B0")
        val brakePressDef = defaults.getValue("2202B3")
        val latGDef = defaults.getValue("2202B4")

        // FL Speed: 0x62 0x02 0xB0 0x17 0x70 -> 6000 / 100.0 = 60.00 km/h
        val resSpeed = PidDecoder.decode(speedFlDef, listOf(0x62, 0x02, 0xB0, 0x17, 0x70))
        assertTrue(resSpeed.isKnown)
        assertEquals(60.0, resSpeed.numericValue ?: 0.0, 0.001)
        assertEquals("60.00 km/h", resSpeed.displayValue)

        // Brake Pressure: 0x62 0x02 0xB3 0x01 0x2C -> 300 / 10.0 = 30.0 bar
        val resBrake = PidDecoder.decode(brakePressDef, listOf(0x62, 0x02, 0xB3, 0x01, 0x2C))
        assertTrue(resBrake.isKnown)
        assertEquals(30.0, resBrake.numericValue ?: 0.0, 0.001)
        assertEquals("30.0 bar", resBrake.displayValue)

        // Lat G: 0x62 0x02 0xB4 0x00 0x1E -> 30 / 100.0 = +0.30 G
        val resLatG = PidDecoder.decode(latGDef, listOf(0x62, 0x02, 0xB4, 0x00, 0x1E))
        assertTrue(resLatG.isKnown)
        assertEquals(0.30, resLatG.numericValue ?: 0.0, 0.001)
        assertEquals("+0.30 G", resLatG.displayValue)
    }

    @Test
    fun `decodes Climatronic Refrigerant Pressure DID 0280`() {
        val acPressDef = defaults.getValue("220280")
        // 0x62 0x02 0x80 0x00 0x78 -> 120 / 10.0 = 12.0 bar
        val res = PidDecoder.decode(acPressDef, listOf(0x62, 0x02, 0x80, 0x00, 0x78))
        assertTrue(res.isKnown)
        assertEquals(12.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("12.0 bar", res.displayValue)
    }

    @Test
    fun `decodes 12V Battery State of Charge DID 0260`() {
        val socDef = defaults.getValue("220260")
        // 0x62 0x02 0x60 0xCC -> 204 * 100 / 255 = 80.0 %
        val res = PidDecoder.decode(socDef, listOf(0x62, 0x02, 0x60, 0xCC))
        assertTrue(res.isKnown)
        assertEquals(80.0, res.numericValue ?: 0.0, 0.001)
        assertEquals("80.0 %", res.displayValue)
    }

    @Test
    fun `rejects Negative Response NRC 7F 22 31 on Extended UDS without emitting false data`() {
        val oilDef = defaults.getValue("220202")
        val res = PidDecoder.decode(oilDef, listOf(0x7F, 0x22, 0x31))
        assertFalse(res.isKnown)
        assertNull(res.numericValue)
        assertEquals("INVALID_RESPONSE", res.displayValue)
    }
}
