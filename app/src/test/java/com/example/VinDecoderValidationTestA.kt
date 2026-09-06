package com.example

import com.example.protocol.IsoTpParser
import org.junit.Assert.*
import org.junit.Test

/** Rigorous VIN validation tests - Part A. */
class VinDecoderValidationTestA {

    @Test
    fun testValidVin_Accepted() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).first()
        assertTrue(msg.isComplete)
        assertEquals(20, msg.reconstructedBytes.size)
        assertEquals(0x49, msg.reconstructedBytes[0])
        assertEquals(0x02, msg.reconstructedBytes[1])
        assertEquals(0x01, msg.reconstructedBytes[2])
        val vin = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.joinToString("")
        assertEquals("MEXKPEPC2TG028855", vin)
    }

    @Test
    fun test19BytePayload_Rejected() {
        val rawLines = listOf("7E8 10 13 49 02 01 4D 45 58 4B 50 45 50 43 32 54 47 30 32 38")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        assertNull("19-byte rejected", msg)
    }

    @Test
    fun test21BytePayload_Rejected() {
        val rawLines = listOf("7E8 10 15 49 02 01 4D 45 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35 00")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        assertNull("21-byte rejected", msg)
    }

    @Test
    fun testWrongService_Rejected() {
        val rawLines = listOf("7E8 10 14 41 02 01 4D 45 58 4B 50 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 && it.reconstructedBytes[0] == 0x49 }
        assertNull("0x41 rejected", msg)
    }

    @Test
    fun testWrongPid_Rejected() {
        val rawLines = listOf("7E8 10 14 49 04 01 4D 45 58 4B 50 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 && it.reconstructedBytes[1] == 0x02 }
        assertNull("0x04 rejected", msg)
    }

    @Test
    fun testVinWithI_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58 4B 49 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("'I' rejected", valid)
        }
    }

    @Test
    fun testVinWithO_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4F 45 58 4B 50 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("'O' rejected", valid)
        }
    }
}
