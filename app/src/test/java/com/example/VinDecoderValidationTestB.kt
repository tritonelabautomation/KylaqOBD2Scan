package com.example

import com.example.protocol.IsoTpParser
import org.junit.Assert.*
import org.junit.Test

/** Rigorous VIN validation tests - Part B. */
class VinDecoderValidationTestB {

    @Test
    fun testVinWithQ_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58 4B 51 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("'Q' rejected", valid)
        }
    }

    @Test
    fun testVinWithSpace_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58 4B 20 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("space rejected", valid)
        }
    }

    @Test
    fun testIncomplete_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.isComplete && it.reconstructedBytes.size == 20 }
        assertNull("incomplete rejected", msg)
    }

    @Test
    fun testMalformed_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58","7E8 23 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.isComplete && !it.isMalformed && it.reconstructedBytes.size == 20 }
        assertNull("malformed rejected", msg)
    }

    @Test
    fun testDifferentCanIds_NotCombined() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58","7E9 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msgs = IsoTpParser.reassembleLines(rawLines).filter { it.isComplete && it.reconstructedBytes.size == 20 }
        assertEquals(1, msgs.size)
        assertEquals("7E8", msgs.first().canId)
    }

    @Test
    fun testLowercase_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 6D 45 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("lowercase rejected", valid)
        }
    }

    @Test
    fun testControlChar_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 00 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("control char rejected", valid)
        }
    }
}
